package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.DhApi;
import net.vulkanmod.visuals.api.FrameContext;
import net.vulkanmod.visuals.api.LodInputs;
import net.vulkanmod.visuals.api.ShadowCascadeContext;
import net.vulkanmod.visuals.api.VisualsExtension;
import net.vulkanmod.visuals.lod.LodComposite;
import net.vulkanmod.visuals.lod.LodCompositeUniforms;
import net.vulkanmod.visuals.lod.LodTarget;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.PostProcessSettings;
import net.vulkanmod.visuals.render.ShadowMath;
import net.vulkanmod.visuals.render.FogParameters;
import net.vulkanmod.visuals.render.VisualsRenderer;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.PerRenderPassCache;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * DH's part of the visuals frame. prepare takes the frame DH's terrain callback kept (DhFrameCapture), sizes the LOD target to
 * the main one and uploads the composite's uniforms (its fog: compositeFog, ruling R20); recordOffscreen records DH's staged copies
 * first, then draws the opaque LOD quads into the LOD target (Vulkan 0..1 depth, cleared to 1.0), keeps the opaque depth (the band
 * depth) and draws the transparent ones (water) over them, blended, in DH's order; drawIntoMain lays the target into the main pass
 * (LodComposite: no LOD where vanilla drew); postInputs hands the target (depth, band depth, LOD light) to the edge cover, the
 * atmosphere and the post-process. A failure turns DH drawing off with one warning; the rest of the frame goes on.
 * <p>
 * The LOD colour carries vanilla's lightmap at the vertex's light and the light attachment that light (DhVertexFormat.lightAttachment),
 * the contract the post-process relights by (target over vanilla lightmap, so the lighting applies once, ruling R19).
 * <p>
 * Stage 3: the opaque LODs cast into the sun's cascades (drawShadowCasters, depth only, in the visuals' shadow pass), from the
 * frame prepare took, once recordOffscreen has flushed this frame's copies (ruling R15; the visuals frame records the extensions'
 * offscreen work before the cascades). They cast outside the square the vanilla terrain casts in and only where their section
 * reaches the cascade (DhShadowCasters); the main view draws only what DH's camera frustum sees (DhCasterFrustum).
 * <p>
 * Task 5b: every draw is culled by its buffer's own box (DhCulling): the main view skips a box outside the frame's view or wholly
 * inside the near discard, a cascade one outside its box; the opaque pass culls back faces, as DH's GL renderer does. Ruling R26b: a cascade redrawn on a frame DH gave no frame for draws
 * the casters of the latest frame DH gave, for up to CASTER_REUSE_FRAMES frames (casterSource).
 */
public final class DhExtension implements VisualsExtension {
    public static final DhExtension INSTANCE = new DhExtension();

    static final String PROGRAM = "dhTerrain";
    static final String SHADOW_PROGRAM = "dhShadow";
    // terrain.vert bindings and loose uniform locations
    static final int LIGHTMAP = 0, VIEW_PROJECTION = 0, MODEL_OFFSET = 1, PARAMS = 2;
    /**
     * Opaque quads: depth tested and written, not blended; the light attachment written where colour is. Back faces culled (Task 5b),
     * as DH's GL renderer draws its terrain (face culling on, GL's default back faces).
     */
    static final VkGraphicsProgram.State STATE = new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkGraphicsProgram.RGBA, false,
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).withLight().withBackFaceCulling();
    /**
     * Transparent quads, as DH's GL renderer draws them: blended (src alpha, one minus src alpha; alpha one, one minus src alpha, the
     * visuals' blend), depth tested and written, so the LOD depth has the water and the band depth the floor; the light written.
     */
    static final VkGraphicsProgram.State TRANSLUCENT_STATE = new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL,
            VkGraphicsProgram.RGBA, true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).withLight();
    /**
     * The sun's cascades: depth only, as every visuals shadow draw (VkShadowMaps). 2026-10-21 (performance): back faces culled, as the
     * main view culls them (DH's LODs are closed toward every view: the nearest surface along a ray from the sun is a face turned to
     * the sun, so the map keeps the same depths with half the triangles; the far cascade draws some 17 million quads).
     */
    static final VkGraphicsProgram.State SHADOW_STATE = VkGraphicsProgram.State.depthOnly(VK_COMPARE_OP_LESS_OR_EQUAL, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            .withBackFaceCulling();
    /** DH's GL renderer keeps its discard circle this far past its near clip plane, so the two never touch. */
    static final float DISCARD_MARGIN = 16;
    static final float LOD_SECTION_BLOCKS = 512;
    /**
     * Ruling R26b: how many frames the casters may draw from the latest frame DH gave, on frames it gave none for (a cascade redrawn
     * then would otherwise have no LOD casters until its next refresh, a distant-shadow blink). Past that DH has stopped drawing
     * (its rendering turned off, a level change): the casters stop too, so no shadow outlives its LOD.
     */
    static final int CASTER_REUSE_FRAMES = 8;

    /** One main-view pass: what was drawn and what was culled, by reason. */
    static final class PassCounts {
        int buffers, quads, containers, listedContainers, dhBuffers, dhQuads, viewBuffers, viewQuads, nearBuffers, nearQuads;

        void clear() {
            this.buffers = this.quads = this.containers = this.listedContainers = 0;
            this.dhBuffers = this.dhQuads = this.viewBuffers = this.viewQuads = this.nearBuffers = this.nearQuads = 0;
        }

        void copy(PassCounts o) {
            this.buffers = o.buffers;
            this.quads = o.quads;
            this.containers = o.containers;
            this.listedContainers = o.listedContainers;
            this.dhBuffers = o.dhBuffers;
            this.dhQuads = o.dhQuads;
            this.viewBuffers = o.viewBuffers;
            this.viewQuads = o.viewQuads;
            this.nearBuffers = o.nearBuffers;
            this.nearQuads = o.nearQuads;
        }
    }

    private final DeferredFree deferred = new DeferredFree();
    private final Runnable listener = this::onRecordingStarted;
    private LodTarget target;
    private LodComposite composite;
    private VkGraphicsProgram program, translucent, shadow;
    private LooseUniformWriter loose, translucentLoose, shadowLoose;
    private QuadIndexBuffer quads;
    private long sampler;
    /** The cascades' render pass compatibility (shadowPassKey), which the depth-only pipeline is found by. */
    private long shadowPassKey;
    /** prepare's frame, drawn by recordOffscreen of the same serial. */
    private DhFrameCapture.Frame frame;
    /** 2026-10-21: vanilla's terrain fog held on while the LODs are not drawn (DhVanillaFog). */
    private final DhVanillaFog vanillaFog = new DhVanillaFog();
    /**
     * The latest frame DH gave, for the shadow pass (the visuals' step 7, after recordOffscreen); take hands a frame out once. Kept on
     * frames DH gives none for: casterSource draws from it within CASTER_REUSE_FRAMES (R26b).
     */
    private DhFrameCapture.Frame casterFrame;
    /** Cascade redraws on frames DH gave no frame for: drawn from the latest one, or without LOD casters (it was too old). */
    private long reusedCasterRedraws, emptyCasterRedraws;
    /** The serial whose staged copies recordOffscreen has recorded: DH buffers are drawable from then on in that frame (R15). */
    private long flushedSerial = Long.MIN_VALUE;
    private boolean castersFailed;
    /** Each cascade's last caster draw: buffers and quads. */
    private final Map<String, int[]> casterStats = new LinkedHashMap<>();
    /** The serial the target holds a drawn frame for, and that frame's projection and LOD distance. */
    private long drawnSerial = Long.MIN_VALUE;
    private Matrix4f drawnProjection;
    private float lodSections;
    /** What drawAll did: the pass being recorded, and the last frame's opaque and transparent passes. */
    private final PassCounts pass = new PassCounts(), lastOpaque = new PassCounts(), lastTranslucent = new PassCounts();
    /** A draw's box, for the culling (render thread). */
    private final float[] box = new float[6];

    private DhExtension() {
    }

    /** LodInputs' unit: sections of 512 blocks; DH's LOD distance is a radius in chunks. */
    static float lodDistanceSections(int lodChunks) {
        return lodChunks * 16 / LOD_SECTION_BLOCKS;
    }

    /** DH's far plane is (LOD blocks + 512) * 2 (RenderUtil.getFarClipPlaneDistanceInBlocks without Iris). */
    static int lodChunksFromFar(float far) {
        return Math.max(0, Math.round(far / 2 - 512) / 16);
    }

    /** The distance terrain.frag discards nearer LOD fragments at: DH's near clip distance plus DISCARD_MARGIN, 0 for none. */
    static float discardDistance(float near) {
        return Float.isFinite(near) && near > 0 ? near + DISCARD_MARGIN : 0;
    }

    /** Task 6: DH LODs are drawn only when the setting is on and drawing has not failed. */
    static boolean drawsLods(boolean dhLodsSetting, boolean disabled) {
        return dhLodsSetting && !disabled;
    }

    /** The opaque LODs cast into the cascades only when they are drawn and the shadow setting is on. */
    static boolean castsShadows(boolean dhLodsSetting, boolean dhShadowsSetting, boolean disabled) {
        return drawsLods(dhLodsSetting, disabled) && dhShadowsSetting;
    }

    /** postInputs answers only for a frame this extension drew, and only while the LOD setting is on. */
    static boolean providesInputs(boolean dhLodsSetting, long drawnSerial, long serial) {
        return dhLodsSetting && drawnSerial == serial;
    }

    /** A cascade's last caster counts, created on first use (the F3 line lists them). */
    int[] casterStats(String name) {
        return this.casterStats.computeIfAbsent(name, k -> new int[2]);
    }

    private void clearCasterStats(String name) {
        int[] stats = this.casterStats(name);
        stats[0] = 0;
        stats[1] = 0;
    }

    /**
     * The cascades' pass (VkShadowMaps: one depth attachment of the device's depth format, no colour) is made again on every world
     * join, render-distance change and dimension change; compatible passes share a pipeline, so the depth-only one is found by this
     * key rather than the pass handle (no pipeline per rebuild, no stale entry for a recycled handle).
     */
    static long shadowPassKey(int depthFormat) {
        return PerRenderPassCache.compatibilityKey(VK_FORMAT_UNDEFINED, depthFormat);
    }

    /** The quads of a buffer the shared index buffer can draw. */
    static int drawableQuads(int quads, int capacity) {
        return Math.max(0, Math.min(quads, capacity));
    }

    /**
     * Vanilla's FogRenderer.setupFog uses another fog than the terrain fog when the camera is in a fluid (water, lava, powder snow), a
     * mob effect has a fog (blindness, darkness) or the level has thick fog (LevelRenderer: DimensionSpecialEffects.isFoggyAt or the
     * boss overlay's world fog). These stay in place; DH moves only the terrain fog out of the way, and so does the composite.
     */
    static boolean environmentalFog(boolean inFluid, boolean mobEffectFog, boolean thickFog) {
        return inFluid || mobEffectFog || thickFog;
    }

    /** environmentalFog for this frame's camera. */
    private static boolean environmentalFogNow() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        boolean fluid = camera.getFluidInCamera() != FogType.NONE;
        boolean effect = camera.getEntity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
        boolean thick = mc.level != null && (mc.level.effects().isFoggyAt(Mth.floor(camera.getPosition().x), Mth.floor(camera.getPosition().z))
                || mc.gui.getBossOverlay().shouldCreateWorldFog());
        return environmentalFog(fluid, effect, thick);
    }

    /**
     * Ruling R20: the composite's fog. Only an environmental fog reaches the composite: one that ends nearer than the vanilla render
     * distance covers everything, so every LOD takes the fog colour (the range starts behind the camera and is full at 0: fog-only
     * shading); any other (water clamped to the render distance at RD 2-6) goes in with vanilla's range as a linear fog, full from its
     * end on. The terrain fog, which DH moves out of the way, gives
     * none (passed as it is, it would paint every LOD past the render distance in the fog colour); the distance fog on LODs is the
     * post-process atmosphere's, through the LOD depth.
     */
    static FogParameters compositeFog(FogParameters fog, boolean environmental, float vanillaRenderDistance) {
        if (!environmental || fog.alpha() <= 0 || !(fog.environmentalEnd() > fog.environmentalStart())) return FogParameters.NONE;
        if (fog.environmentalEnd() < vanillaRenderDistance) return new FogParameters(fog.red(), fog.green(), fog.blue(), fog.alpha(), -1, 0, -1, 0);
        return fog;
    }

    /** DH's LOD distance setting, or the one its far plane was built from when the config cannot be read. */
    private static int lodChunks(float far) {
        try {
            return DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
        } catch (RuntimeException | LinkageError e) {
            return lodChunksFromFar(far);
        }
    }

    @Override
    public void prepare(FrameContext ctx) {
        this.frame = null;
        boolean lods = drawsLods(VisualsConfig.get().dhLods, DhRegistration.disabled());
        this.vanillaFog.update(lods);
        if (!lods) {
            //Setting off or drawing failed: nothing is drawn or kept for the casters; DH's frame of this serial is taken and dropped
            this.casterFrame = null;
            this.drawnSerial = Long.MIN_VALUE;
            this.lastOpaque.clear();
            this.lastTranslucent.clear();
            DhFrameCapture.INSTANCE.take(ctx.serial());
            return;
        }
        DhFrameCapture.Frame f = DhFrameCapture.INSTANCE.take(ctx.serial());
        //No frame from DH: the casters keep the latest one (casterSource)
        if (f == null) return;
        try {
            this.ensureResources();
            this.target.ensureSize(ctx.width(), ctx.height());
            int most = 0;
            for (DhFrameCapture.Draw d : f.opaque()) most = Math.max(most, d.buffer().quadCount());
            for (DhFrameCapture.Draw d : f.transparent()) most = Math.max(most, d.buffer().quadCount());
            //Its upload is staged like DH's own and recorded by the flush at the start of recordOffscreen
            if (most > 0) this.quads.ensure(most);
            //Ruling R20: this frame's vanilla fog (after DH's MixinFogRenderer), through compositeFog
            FogParameters fog = compositeFog(FogParameters.fromRenderSystem(), environmentalFogNow(), VisualsRenderer.vanillaRenderDistance());
            this.composite.prepare(ctx.upload(), LodCompositeUniforms.compute(f.dhProjectionVk(), DhDepth.toVulkan(ctx.projection()), fog));
            this.lodSections = lodDistanceSections(lodChunks(f.far()));
            this.frame = f;
            this.casterFrame = f;
        } catch (RuntimeException | LinkageError e) {
            this.fail("preparing the LOD frame failed", e);
        }
    }

    /**
     * The flush comes first: DH buffers are drawable only in commands recorded after it in the same frame. Then the opaque LODs in
     * the LOD target's clear pass; the snapshot keeps their depth (the band depth, which the lighting tells LOD water by); then the
     * transparent LODs in a load pass, blended over them in DH's order; then everything is left readable.
     */
    @Override
    public void recordOffscreen(VkCommandBuffer cmd, FrameContext ctx) {
        DhStaging.INSTANCE.flush(cmd);
        this.flushedSerial = ctx.serial();
        DhFrameCapture.Frame f = this.frame;
        this.frame = null;
        if (f == null || f.serial() != ctx.serial() || !drawsLods(VisualsConfig.get().dhLods, DhRegistration.disabled())) return;
        try {
            this.target.beginPass(cmd);
            this.drawAll(cmd, ctx, f, f.opaque(), this.program, this.loose, this.target.clearPass());
            this.lastOpaque.copy(this.pass);
            this.target.endPass(cmd);
            this.target.snapshotBeforeTranslucents(cmd, false);
            this.pass.clear();
            if (!f.transparent().isEmpty()) {
                this.target.beginPass(cmd, false);
                this.drawAll(cmd, ctx, f, f.transparent(), this.translucent, this.translucentLoose, this.target.loadPass());
                this.target.endPass(cmd);
            }
            this.lastTranslucent.copy(this.pass);
            this.target.toShaderRead(cmd);
            this.drawnProjection = f.dhProjectionVk();
            this.drawnSerial = ctx.serial();
        } catch (RuntimeException | LinkageError e) {
            this.target.endOpenPass(cmd);
            this.fail("drawing the LODs failed", e);
        }
    }

    /**
     * Inside the target's pass: each buffer of draws that can show (DhCulling.mainView, with the matrix the draws are recorded with,
     * the frame's own) in the order given, with its corner relative to the camera; the counts go to pass.
     */
    private void drawAll(VkCommandBuffer cmd, FrameContext ctx, DhFrameCapture.Frame f, List<DhFrameCapture.Draw> draws, VkGraphicsProgram program,
                         LooseUniformWriter loose, long renderPass) {
        PassCounts counts = this.pass;
        counts.clear();
        long indices = this.quads.handle();
        if (indices == 0 || draws.isEmpty()) return;
        Matrix4f viewProjection = DhCulling.viewProjection(f);
        float discard = discardDistance(f.near());
        loose.putMat4(VIEW_PROJECTION, viewProjection).putVec4(MODEL_OFFSET, 0, 0, 0, 0).putVec4(PARAMS, DhVertexFormat.MICRO_OFFSET, discard, 0, 0);
        program.bind(cmd, renderPass, new VkBindings().sampledView(LIGHTMAP, ctx.lightmapView(), this.sampler), loose);
        vkCmdBindIndexBuffer(cmd, indices, 0, VK_INDEX_TYPE_UINT32);
        int capacity = this.quads.capacityQuads();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer vertices = stack.mallocLong(1);
            LongBuffer offsets = stack.longs(0);
            DhFrameCapture.Draw listed = null, drawn = null;
            for (int i = 0; i < draws.size(); i++) {
                DhFrameCapture.Draw d = draws.get(i);
                if (!DhCulling.sameContainer(listed, d)) counts.listedContainers++;
                listed = d;
                long handle = d.buffer().handle();
                int n = drawableQuads(d.buffer().quadCount(), capacity);
                if (handle == 0 || n == 0) continue;
                switch (DhCulling.mainView(d, viewProjection, ctx.cameraX(), ctx.cameraY(), ctx.cameraZ(), discard, this.box)) {
                    case DhCulling.DH_FRUSTUM -> {
                        //Kept in DH's list for the shadow casters only
                        counts.dhBuffers++;
                        counts.dhQuads += n;
                        continue;
                    }
                    case DhCulling.OUT_OF_VIEW -> {
                        counts.viewBuffers++;
                        counts.viewQuads += n;
                        continue;
                    }
                    case DhCulling.NEAR -> {
                        counts.nearBuffers++;
                        counts.nearQuads += n;
                        continue;
                    }
                    default -> {
                    }
                }
                loose.putVec4(MODEL_OFFSET, (float) (d.originX() - ctx.cameraX()), (float) (d.originY() - ctx.cameraY()),
                        (float) (d.originZ() - ctx.cameraZ()), 0);
                program.pushConstants(cmd, loose);
                vertices.put(0, handle);
                vkCmdBindVertexBuffers(cmd, 0, vertices, offsets);
                vkCmdDrawIndexed(cmd, QuadIndexBuffer.indexCount(n), 1, 0, 0, 0);
                counts.buffers++;
                counts.quads += n;
                if (!DhCulling.sameContainer(drawn, d)) counts.containers++;
                drawn = d;
            }
        }
    }

    /**
     * The frame a cascade of frame serial draws its casters from: last (the latest frame DH gave) when it is this frame's or at most
     * CASTER_REUSE_FRAMES older (ruling R26b), and only once this frame's copies are flushed (R15: flushedSerial is serial). Its
     * buffers are read when drawn: a closed one has no handle and is skipped, a re-uploaded one is drawn as this frame's flush left it.
     */
    static DhFrameCapture.Frame casterSource(DhFrameCapture.Frame last, long serial, long flushedSerial) {
        if (last == null || flushedSerial != serial) return null;
        long age = serial - last.serial();
        return age >= 0 && age <= CASTER_REUSE_FRAMES ? last : null;
    }

    /**
     * Inside the cascade's depth-only pass (VkShadowMaps begins and ends it): the opaque LODs of this frame (or of the latest frame
     * DH gave, casterSource), only after this frame's flush (ruling R15). Nothing is uploaded and no barrier is placed: the shadow
     * upload stream is closed by now (visuals R12), and the shared index buffer's copy went with the flush. A failure turns the
     * casters off with one warning; the LODs stay drawn.
     */
    @Override
    public void drawShadowCasters(VkCommandBuffer cmd, long renderPass, ShadowCascadeContext c) {
        if (this.castersFailed) return;
        VisualsConfig config = VisualsConfig.get();
        if (!castsShadows(config.dhLods, config.dhShadows, DhRegistration.disabled())) {
            this.clearCasterStats(c.name());
            return;
        }
        DhFrameCapture.Frame last = this.casterFrame;
        DhFrameCapture.Frame f = casterSource(last, c.serial(), this.flushedSerial);
        if (last != null && last.serial() != c.serial()) {
            if (f != null) {
                this.reusedCasterRedraws++;
            } else {
                this.emptyCasterRedraws++;
            }
        }
        if (f == null) {
            //R28: an empty redraw reports no casters, not the previous draw's counts
            this.clearCasterStats(c.name());
            return;
        }
        try {
            this.drawCasters(cmd, renderPass, c, f);
        } catch (RuntimeException | LinkageError e) {
            this.castersFailed = true;
            VisualsLog.warn("Distant Horizons: the LOD shadow casters failed (" + e + "); LODs cast no sun shadows until the game restarts", e);
        }
    }

    /**
     * 2026-10-29: the LODs cast past the shadow distance (the vanilla terrain then stops there) when drawShadowCasters would draw them
     * this frame: the casters on and casterSource giving a frame with opaque LODs (audit: the last frame alone stayed true after DH
     * stopped handing frames, and the shadows past the shadow distance went missing).
     */
    @Override
    public boolean castsFarTerrain() {
        VisualsConfig config = VisualsConfig.get();
        if (this.castersFailed || !castsShadows(config.dhLods, config.dhShadows, DhRegistration.disabled())) return false;
        DhFrameCapture.Frame f = casterSource(this.casterFrame, VkFrames.serial(), this.flushedSerial);
        return f != null && !f.opaque().isEmpty();
    }

    /** Each opaque buffer that casts into the cascade (DhShadowCasters), with its corner relative to the cascade's camera. */
    private void drawCasters(VkCommandBuffer cmd, long renderPass, ShadowCascadeContext c, DhFrameCapture.Frame f) {
        ShadowMath.Cascade cascade = c.cascade();
        long indices = this.quads.handle();
        int[] stats = this.casterStats(c.name());
        stats[0] = 0;
        stats[1] = 0;
        if (cascade == null || indices == 0 || f.opaque().isEmpty()) return;
        Matrix4f viewProjection = cascade.viewProjection();
        float cutoff = DhShadowCasters.cutoff(c.name(), PostProcessSettings.from(VisualsConfig.get()).vanillaShadows(),
                Minecraft.getInstance().options.getEffectiveRenderDistance(), VisualsConfig.get().shadowDistance);
        int capacity = this.quads.capacityQuads();
        boolean bound = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer vertices = stack.mallocLong(1);
            LongBuffer offsets = stack.longs(0);
            for (DhFrameCapture.Draw d : f.opaque()) {
                long handle = d.buffer().handle();
                int n = drawableQuads(d.buffer().quadCount(), capacity);
                if (handle == 0 || n == 0 || !DhShadowCasters.inSlice(d, c.slice(), c.slices())
                        || !DhShadowCasters.casts(d, cascade, viewProjection, cutoff, this.box)) continue;
                if (!bound) {
                    this.shadowLoose.putMat4(VIEW_PROJECTION, viewProjection).putVec4(MODEL_OFFSET, 0, 0, 0, 0)
                            .putVec4(PARAMS, DhVertexFormat.MICRO_OFFSET, cutoff, 0, 0);
                    this.shadow.bind(cmd, renderPass, this.shadowPassKey, new VkBindings(), this.shadowLoose);
                    vkCmdBindIndexBuffer(cmd, indices, 0, VK_INDEX_TYPE_UINT32);
                    bound = true;
                }
                this.shadowLoose.putVec4(MODEL_OFFSET, (float) (d.originX() - cascade.cameraX()), (float) (d.originY() - cascade.cameraY()),
                        (float) (d.originZ() - cascade.cameraZ()), 0);
                this.shadow.pushConstants(cmd, this.shadowLoose);
                vertices.put(0, handle);
                vkCmdBindVertexBuffers(cmd, 0, vertices, offsets);
                vkCmdDrawIndexed(cmd, QuadIndexBuffer.indexCount(n), 1, 0, 0, 0);
                stats[0]++;
                stats[1] += n;
            }
        }
    }

    @Override
    public void drawIntoMain(VkCommandBuffer cmd, FrameContext ctx) {
        if (this.drawnSerial != ctx.serial()) return;
        try {
            this.composite.draw(cmd, this.target);
        } catch (RuntimeException | LinkageError e) {
            this.fail("compositing the LODs failed", e);
        }
    }

    /** The target's depth (the LOD projection, 0..1), the band depth, the LOD light and DH's LOD distance, on frames that drew. */
    @Override
    public LodInputs postInputs(FrameContext ctx) {
        if (!providesInputs(VisualsConfig.get().dhLods, this.drawnSerial, ctx.serial())) return null;
        LodTarget t = this.target;
        return new LodInputs(t.depth(), t.depth().sampledView(), t.bandDepth().sampledView(), t.light().view(), this.drawnProjection,
                t.width() == ctx.width() && t.height() == ctx.height(), this.lodSections);
    }

    @Override
    public void addDebugInfo(List<String> lines) {
        PassCounts o = this.lastOpaque, t = this.lastTranslucent;
        lines.add("DH: " + DhVertexBuffer.liveCount() + " buffers, " + (DhVertexBuffer.liveBytes() >> 20) + " MiB, drew " + o.buffers
                + " buffers / " + o.quads + " quads (" + o.containers + " of " + o.listedContainers + " containers) + " + t.buffers + " / " + t.quads
                + " translucent" + (DhRegistration.disabled() ? ", off (see the log)" : ""));
        lines.add("DH culled: DH frustum " + o.dhBuffers + " / " + o.dhQuads + ", view " + o.viewBuffers + " / " + o.viewQuads + ", near "
                + o.nearBuffers + " / " + o.nearQuads + " + translucent " + (t.dhBuffers + t.viewBuffers + t.nearBuffers) + " / "
                + (t.dhQuads + t.viewQuads + t.nearQuads));
        if (!this.casterStats.isEmpty() || this.castersFailed) {
            StringBuilder b = new StringBuilder("DH casters:");
            if (this.castersFailed) b.append(" failed (see the log)");
            this.casterStats.forEach((name, v) -> b.append(' ').append(name.substring(name.indexOf('.') + 1)).append(' ').append(v[0]).append(" / ").append(v[1]));
            b.append(" | redraws without a DH frame: ").append(this.reusedCasterRedraws).append(" from the latest, ").append(this.emptyCasterRedraws)
                    .append(" without LODs");
            lines.add(b.toString());
        }
        long late = DhFrameCapture.INSTANCE.lateCalls();
        if (late > 0) lines.add("DH late calls dropped: " + late);
        lines.add("DH " + DhStaging.INSTANCE.describe());
    }

    /** The pipeline, the target, the composite and the shared index buffer, made on the first frame DH hands over. */
    private void ensureResources() {
        if (this.target != null) return;
        VkGraphicsProgram program = null, translucent = null, shadow = null;
        long sampler = 0;
        LodTarget target = null;
        try {
            program = VkGraphicsProgram.of(PROGRAM, STATE, DhVertexFormat.LAYOUT);
            translucent = VkGraphicsProgram.of(PROGRAM, TRANSLUCENT_STATE, DhVertexFormat.LAYOUT);
            shadow = VkGraphicsProgram.of(SHADOW_PROGRAM, SHADOW_STATE, DhVertexFormat.LAYOUT);
            sampler = VkSamplers.nearestClamp();
            target = new LodTarget(this.deferred);
            this.composite = new LodComposite();
        } catch (RuntimeException | LinkageError e) {
            //Nothing here was used by a frame yet
            if (program != null) program.free();
            if (translucent != null) translucent.free();
            if (shadow != null) shadow.free();
            if (sampler != 0) VkSamplers.free(sampler);
            if (target != null) target.free();
            throw e;
        }
        this.program = program;
        this.loose = program.looseUniforms();
        this.translucent = translucent;
        this.translucentLoose = translucent.looseUniforms();
        this.shadow = shadow;
        this.shadowLoose = shadow.looseUniforms();
        this.sampler = sampler;
        this.shadowPassKey = shadowPassKey(VkContext.depthFormat());
        this.quads = new QuadIndexBuffer(DhStaging.INSTANCE.gpu());
        this.target = target;
        VkFrames.addListener(this.listener);
    }

    /** A frame starts recording: the target's layouts follow dropped frames, and retired images are freed once unused. */
    private void onRecordingStarted() {
        if (this.target != null) this.target.tick();
        this.deferred.tick(VkFrames.completedSerial());
    }

    private void fail(String what, Throwable e) {
        this.frame = null;
        this.casterFrame = null;
        this.drawnSerial = Long.MIN_VALUE;
        DhInternals.disable(what + " (" + e + ")", e);
    }
}
