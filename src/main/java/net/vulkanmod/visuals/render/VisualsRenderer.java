package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.dev.FrameRecorder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.IForgeDimensionSpecialEffects;
import net.vulkanmod.mixin.visuals.LevelRendererTicksAccessor;
import net.vulkanmod.visuals.Visuals;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.api.FrameContext;
import net.vulkanmod.visuals.api.LodInputs;
import net.vulkanmod.visuals.api.VisualsExtension;
import net.vulkanmod.visuals.api.VisualsExtensions;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.ImageViewCache;
import net.vulkanmod.visuals.vk.VkBarriers;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkDownloadStream;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The visuals' frame flow on Reforged's frame, on vanilla terrain alone: the shading choice, the sun's shadow cascades, the
 * edge cover, the main depth copies around the translucent terrain, the atmosphere of the frame, and the full-screen
 * post-process (light volume, held light, lighting, reflections, clouds, bloom, TAA and SMAA). A mod that draws more geometry
 * (LODs) joins through the VisualsExtension hooks; with none registered every pass reads the main depth and the lightmap.
 * The render thread calls everything here.
 */
public final class VisualsRenderer {
    /** The streams are shared with the extensions (an LOD mod uploads its geometry through them). */
    private static final long UPLOAD_STREAM_BYTES = 64L << 20;
    private static final long DOWNLOAD_STREAM_BYTES = 4L << 20;
    /** The far clouds' and the light shafts' reach without an LOD distance: 16 sections of 512 blocks, the LOD default it was tuned at. */
    static final float FAR_SECTIONS = 16;

    /** The far clouds' and the shafts' reach in LOD sections: the extension's LOD distance when it supplied inputs this frame, else the default. */
    static float farSections(LodInputs lod) {
        return lod == null ? FAR_SECTIONS : lod.lodDistanceSections();
    }

    /** Ruling R18: the edge cover follows the master switch (its option greys out with it) and its own switch. */
    static boolean edgeCoverOn(PostProcessSettings post, ShadingSettings shading) {
        return post.enabled() && shading.edgeCover();
    }

    /** Each extension's F3 lines; an extension that throws costs its own lines only (warned once per extension). */
    static void addExtensionDebugInfo(List<VisualsExtension> extensions, List<String> lines) {
        for (VisualsExtension e : extensions) {
            try {
                e.addDebugInfo(lines);
            } catch (RuntimeException ex) {
                if (DEBUG_WARNED.add(e)) VisualsLog.warn("A visuals extension failed in addDebugInfo; its F3 lines are replaced by a note", ex);
                lines.add("Visuals: extension debug info failed");
            }
        }
    }

    private static final Set<VisualsExtension> DEBUG_WARNED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final long STATS_NANOS = 5_000_000_000L;

    /**
     * What a dimension and the settings allow this frame. Without sky light there are no sun shadows, no sky and no far clouds;
     * the per-pixel light (light volume, held light) and the water reflections follow their settings.
     */
    public record Plan(boolean shadows, boolean clouds, boolean sky, boolean lighting, boolean reflections) {
        public static Plan of(boolean hasSkyLight, PostProcessSettings s) {
            return new Plan(hasSkyLight && s.sunShadows(), hasSkyLight && s.farClouds(), hasSkyLight, s.perPixelLight(), LightingChoice.reflectionPass(s));
        }

        /**
         * 2026-10-19 (clouds dark with Sun Shading): whether vanilla's cloud layer is taken this frame, with sky light whatever the far
         * clouds' setting; the copy finds vanilla's clouds by it and keeps them out of the terrain's sun shading.
         */
        public boolean cloudLayer() {
            return this.sky;
        }
    }

    private final Runnable frameListener = this::onRecordingStarted;
    private final DeferredFree deferred = new DeferredFree();
    private final ImageViewCache mainDepthViews = new ImageViewCache(VkContext::createMainDepthView, VkImage2D::destroyView);
    private final Matrix4f vanillaMvp = new Matrix4f();
    private final SmoothDayTime shadowDayTime = new SmoothDayTime();
    private final SkyExposure skyExposure = new SkyExposure();
    private final RippleClock rippleClock = new RippleClock();
    //2026-10-29: the flow clock's seconds at the last atmosphere capture (the raindrops' rings keep time with the ripples)
    private double flowSeconds;
    /** 2026-11-13 (session 19): the nether portals around the camera (their violet light and glow). */
    private final PortalLights portals = new PortalLights();
    /** 2026-11-09 (session 5): the aurora's climate share at the camera (CelestialUniforms.follow) and when it was last followed. */
    private float climate = Float.NaN;
    private long climateNanos;
    private final FlowClock flowClock = new FlowClock();
    /**
     * 2026-11-09 (session 6): the shader weather's precipitation map, the level it reads (rebuilt for a new level), and the camera's
     * velocity the streaks follow (WeatherUniforms.followVelocity) with the last position and flow time it was followed from.
     */
    private final PrecipitationMap precipitation = new PrecipitationMap();
    private LevelPrecipitation precipitationLevel;
    private ClientLevel precipitationOf;
    /** 2026-11-10 (session 7): System.nanoTime of the map's last update (PrecipitationMap.due). */
    private long precipitationNanos = Long.MIN_VALUE;
    private float[] weatherVelocity = new float[3];
    private double weatherLastX = Double.NaN, weatherLastY, weatherLastZ, weatherLastSeconds;
    private VkUploadStream upload;
    private VkDownloadStream download;
    private LightVolumeSystem lightVolumes;
    private HeldLightSystem heldLight;
    private TerrainLightTarget terrainLight;
    private VkEdgeCover edgeCover;
    private VkShadowMaps shadows;
    //2026-10-29: the entity shadow map; null without sun shadows or when it could not be built
    private VkEntityShadows entityShadows;
    private VkPostProcess post;
    /** After 3b: vanilla's cloud mask, the size and count the uploaded noise map was built for, and a rebuild in progress (refreshCloudMap). */
    private byte[] cloudMask;
    private float cloudMapSize = 1, cloudMapCount = 1;
    private CompletableFuture<byte[]> cloudMapBuild;
    private VkDepthCapture depthCapture;
    private LightingChoice lightingChoice = LightingChoice.NONE;
    //2026-11-09 (session 2b): this frame's camera is in water with the post-process on: the depth before the translucent terrain is
    // taken for the composite (what lies behind the surface seen from below) even though the lighting pass is off
    private boolean captureUnderwater;
    private ShadowFrame shadowFrame;
    private AtmosphereFrame atmosphere;
    /** The extensions' LOD inputs, valid only for their serial. */
    private LodInputs lod;
    private long lodSerial = Long.MIN_VALUE;
    private long lastFrameSerial = Long.MIN_VALUE;
    private long frameLightmap;
    private int frameId;
    private long litFrames, copiedFrames, shadowFrames, lastStats = Long.MIN_VALUE;
    private boolean listening, failed, postFailed, captureFailed, shadowsFailed, atmosphereWarned, closed, freed;
    //2026-10-30 (audit): post-process failures in a row; a few are skipped frames (a full upload stream, an allocation in a resize)
    private int postFailures;
    public static final int POST_FAILURES_ALLOWED = 3;

    /** A renderer that holds nothing (no device needed): shutdown and the gates only. */
    private VisualsRenderer() {
    }

    /** Built when a world opens (WorldRenderer.allChanged); a part that cannot be built leaves the game drawn without it. */
    public VisualsRenderer(ClientLevel level) {
        try {
            this.upload = new VkUploadStream(UPLOAD_STREAM_BYTES);
            this.download = new VkDownloadStream(DOWNLOAD_STREAM_BYTES);
            this.lightVolumes = new LightVolumeSystem(this.deferred);
            this.heldLight = new HeldLightSystem();
            this.terrainLight = new TerrainLightTarget(this.deferred);
            this.edgeCover = createEdgeCover();
            //P7j final review: a dimension without sky light never draws them
            this.shadows = level.dimensionType().hasSkyLight() ? createShadowMaps() : null;
            //2026-10-29: with the sun shadows only
            this.entityShadows = this.shadows != null ? VkEntityShadows.create() : null;
            this.post = createPostProcess(this.deferred);
            if (this.post != null) {
                //After 3b: the noise map follows the cloud size and count (refreshCloudMap rebuilds it when they change)
                PostProcessSettings clouds = PostProcessSettings.from(VisualsConfig.get());
                this.cloudMask = loadCloudMask();
                this.cloudMapSize = clouds.cloudSize();
                this.cloudMapCount = clouds.cloudCount();
                this.post.queueCloudMap(this.cloudMask != null ? CloudMapCache.withNoise(this.cloudMask, this.cloudMapSize, this.cloudMapCount) : null);
            }
            this.depthCapture = this.post == null ? null : createDepthCapture(this.deferred);
            VkFrames.addListener(this.frameListener);
            this.listening = true;
            VisualsLog.info("Visuals renderer created: sun shadows " + (this.shadows != null ? "on" : "off") + ", edge cover "
                    + (this.edgeCover != null ? "on" : "off") + ", " + ShadingSettings.from(VisualsConfig.get()).describe() + ", post-process "
                    + (this.post != null ? PostProcessSettings.from(VisualsConfig.get()).describe() : "unavailable"));
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.error("Visuals renderer creation failed, releasing partially created resources", e);
            this.shutdown();
            throw e;
        }
    }

    /** A renderer without resources, for code paths that need one before the device is up (and the unit tests). */
    public static VisualsRenderer empty() {
        return new VisualsRenderer();
    }

    /** The post-process exists and has not failed since the world loaded (the moonlight follows it). */
    public boolean active() {
        return !this.closed && this.post != null && !this.postFailed;
    }

    /** Gate G7j round 6: air blocks straight above the camera block, up to SkyExposure.OPEN_AIR_FULL (past the build height counts as air). */
    private static int airAbove(ClientLevel level, BlockPos camera) {
        BlockPos.MutableBlockPos pos = camera.mutable();
        for (int i = 1; i <= SkyExposure.OPEN_AIR_FULL; i++) {
            pos.move(0, 1, 0);
            if (pos.getY() >= level.getMaxBuildHeight()) return SkyExposure.OPEN_AIR_FULL;
            if (!level.getBlockState(pos).isAir()) return i - 1;
        }
        return SkyExposure.OPEN_AIR_FULL;
    }

    /**
     * 2026-11-09 (session 2b): the water's open surface above the eye (blocks): up the eye's column while it holds water, to the top
     * water block's fluid height; 0 when the eye is in no water, the column is capped by a solid block or runs past SURFACE_SCAN.
     */
    static float surfaceAbove(ClientLevel level, double eyeX, double eyeY, double eyeZ) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(eyeX, eyeY, eyeZ).mutable();
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return 0;
        for (int i = 0; i < UnderwaterUniforms.SURFACE_SCAN; i++) {
            pos.move(0, 1, 0);
            if (pos.getY() >= level.getMaxBuildHeight()) return 0;
            if (level.getFluidState(pos).is(FluidTags.WATER)) continue;
            if (level.getBlockState(pos).isSolidRender(level, pos)) return 0;
            pos.move(0, -1, 0);
            FluidState top = level.getFluidState(pos);
            float up = (float) (pos.getY() + top.getHeight(level, pos) - eyeY);
            return up > 0 ? up : 0;
        }
        return 0;
    }

    /** The vanilla render distance in blocks, which the fog is judged against (the lighting, the atmosphere and DH's composite). */
    public static float vanillaRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    private boolean shadowsOn() {
        return this.shadows != null && !this.shadowsFailed;
    }

    /**
     * WorldRenderer.renderSectionLayer after the cutout terrain: this frame's shading choice and sun views, the extensions' and
     * the shadows' work outside the main pass, the extensions' composite and the edge cover inside it, then the atmosphere.
     */
    /** 2026-10-30 (audit): whether frame() runs at all (Visuals.frame otherwise records DH's staged uploads itself). */
    public boolean runsFrames() {
        return !this.failed && !this.closed && this.upload != null;
    }

    public void frame(Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        if (this.failed || this.closed || this.upload == null) return;
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width <= 0 || target.height <= 0) return;
        //A mod that renders the cutout layer twice in one frame must not rerun the frame flow
        if (!this.claimSerial(VkFrames.serial())) return;
        long cpuStart = System.nanoTime();
        try {
            //endMainPass forgets Reforged's bound framebuffer, so the main target is read first
            VkContext.MainTarget main = VkContext.captureMainTarget();
            this.lightingChoice = LightingChoice.NONE;
            this.shadowFrame = null;
            this.lod = null;
            if (main == null) return;
            long serial = VkFrames.serial();
            this.frameId = (this.frameId + 1) & 0x7fffffff;
            //1. The shading choice, the sky exposure and the sun views, from the conditions the atmosphere turns off by (P7d 3.3:
            // the last capture stands in for this frame's)
            ClientLevel level = Minecraft.getInstance().level;
            float partialTick = Minecraft.getInstance().getFrameTime();
            PostProcessSettings settings = PostProcessSettings.from(VisualsConfig.get());
            boolean hasSky = level != null && level.dimensionType().hasSkyLight();
            Plan plan = Plan.of(hasSky, settings);
            //2026-10-23: from inside water as when the water's fog covers everything (AtmosphereFrame.withCameraFluid): no switch once it clears
            boolean fogCoversAll = FogParameters.fromRenderSystem().environmentalEnd() < vanillaRenderDistance()
                    || cameraFluid(Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera()) == AtmosphereFrame.CameraFluid.WATER;
            this.vanillaMvp.set(ReforgedProjection.vulkanDepth(projection).mul(modelView));
            if (level != null) {
                BlockPos camera = BlockPos.containing(cameraX, cameraY, cameraZ);
                this.skyExposure.update(SkyExposure.target(level.getBrightness(LightLayer.SKY, camera) / 15f, airAbove(level, camera)), System.nanoTime());
            }
            this.lightingChoice = LightingChoice.of(serial, settings, this.post != null && !this.postFailed, this.atmosphere != null, fogCoversAll, hasSky);
            this.captureUnderwater = settings.enabled() && this.post != null && !this.postFailed
                    && cameraFluid(Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera()) == AtmosphereFrame.CameraFluid.WATER;
            //Sun-step crossfade (2026-10-16): the shadows' time of day moves between ticks while the day runs, never back (SmoothDayTime)
            float shadowTime = 0.5f;
            if (level != null) {
                //The client's doDaylightCycle follows the server's time packets (a negative day time stops it)
                boolean dayRuns = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT) && !level.dimensionType().hasFixedTime();
                shadowTime = this.shadowDayTime.timeOfDay(level.getTimeOfDay(partialTick), level.getDayTime(), partialTick, dayRuns, System.nanoTime());
                if (FrameRecorder.ON) {
                    FrameRecorder.set(FrameRecorder.DAY_TIME, (long) ((level.getDayTime() + (double) partialTick) * 1000));
                    FrameRecorder.set(FrameRecorder.SHADOW_TIME, (long) (shadowTime * 1e9));
                    var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
                    if (server != null) FrameRecorder.set(FrameRecorder.SERVER_DAY_TIME, server.overworld().getDayTime());
                }
            }
            this.shadowFrame = this.shadowsOn() ? ShadowFrame.of(serial, this.lightingChoice.sun(), plan.shadows(), shadowTime,
                    level != null ? level.getRainLevel(partialTick) : 0, this.skyExposure.value(), cameraX, cameraY, cameraZ, this.frameId) : null;
            //2026-10-29: the entities drawn after this cast into the entity shadow map when this frame has sun shadows
            EntityShadowCapture.beginFrame(serial, this.shadowFrame != null && this.entityShadows != null && VisualsConfig.get().entityShadows, modelView);

            List<VisualsExtension> ext = VisualsExtensions.all();
            long mainDepth = this.mainDepthViews.get(main.identity(), this.deferred, serial);
            FrameContext ctx = new FrameContext(serial, this.frameId, projection, modelView, new Matrix4f(this.vanillaMvp), cameraX, cameraY, cameraZ,
                    main.width(), main.height(), this.upload, this.download, this.deferred, VkContext.lightmapImageView(), mainDepth);
            //2. The extensions' uploads
            for (VisualsExtension e : ext) {
                e.prepare(ctx);
            }
            //3. The cascades drawn this frame (and the extensions' shadow uploads); the edge cover's faces
            boolean shadow = this.shadowFrame != null && this.prepareShadows(ext);
            if (this.edgeCover != null) {
                this.edgeCover.prepare(this.deferred, edgeCoverOn(settings, ShadingSettings.from(VisualsConfig.get())), cameraX, cameraY, cameraZ);
            }
            //4. Out of the main pass
            VkCommandBuffer cmd = VkContext.frameCommands();
            VkContext.endMainPass();
            try {
                VkGpuTimer.reset(cmd);
                VkGpuTimer.mark(cmd, "prepare");
                VkBarriers.frameStart(cmd);
                //5. The uploads
                this.upload.commit(cmd);
                this.frameLightmap = VkContext.lightmapView(cmd);
                //Task 3: without LODs the edge cover binds a stand-in depth, moved to the read layout here, outside the pass
                if (this.edgeCover != null) this.edgeCover.recordStandIn(cmd);
                //6. The extensions' offscreen work
                VkGpuTimer.mark(cmd, "offscreen");
                for (VisualsExtension e : ext) {
                    e.recordOffscreen(cmd, ctx);
                }
                //7. The cascades (they mark their own intervals)
                if (shadow) this.drawShadows(cmd, settings.vanillaShadows(), ext);
                //8. The downloads
                VkGpuTimer.mark(cmd, "downloads");
                this.download.commit(cmd);
                //9. Ruling R13: the extensions' afterDownloads once a frame, then the maps' own state
                for (VisualsExtension e : ext) {
                    e.afterDownloads(cmd);
                }
                if (this.shadowsOn()) this.shadows.afterDownloads(cmd);
            } finally {
                //A failure inside the shadow pass would otherwise leave it open under Reforged's main pass
                if (this.shadows != null) this.shadows.endOpenPass(cmd);
                //10. Back into the main pass
                VkContext.resumeMainPass();
                VkContext.invalidatePipelineCache();//the offscreen pipelines replaced the one Reforged has cached
            }
            try {
                //11. The extensions' composite, then their inputs for the edge cover, the atmosphere and the post-process
                VkGpuTimer.mark(cmd, "composite");
                for (VisualsExtension e : ext) {
                    e.drawIntoMain(cmd, ctx);
                }
                this.lod = lodInputs(ext, ctx);
                this.lodSerial = serial;
                //12. The edge cover, over built sections' sides next to unbuilt ones
                VkGpuTimer.mark(cmd, "edgeCover");
                if (this.edgeCover != null) {
                    this.edgeCover.draw(cmd, this.vanillaMvp, this.frameLightmap, this.lod == null ? null : this.lod.depth(), cameraX, cameraY, cameraZ);
                }
            } finally {
                VkContext.invalidatePipelineCache();
            }
            //P7f: from here to the post-process Reforged draws the rest of the level after cutout (entities, translucent terrain,
            // particles, weather)
            VkGpuTimer.mark(cmd, "vanilla.late");
            //13. The atmosphere of this frame
            this.atmosphere = this.captureAtmosphere(projection, modelView, plan, cameraX, cameraY, cameraZ);
            this.logStats();
        } catch (RuntimeException e) {
            this.failed = true;
            VisualsLog.error("Visuals frame failed; the shading, shadows and edge cover stop until the world is reloaded", e);
        }
        VkGpuTimer.cpu("frame", System.nanoTime() - cpuStart);
    }

    private static final AtomicBoolean LOD_WARNED = new AtomicBoolean();

    /**
     * The first LOD inputs an extension gives for this frame, or null. Only the first is used: when another extension gives inputs
     * too, one warning says so (Task 6; two LOD renderers cannot share the one LOD target the post passes read).
     */
    static LodInputs lodInputs(List<VisualsExtension> ext, FrameContext ctx) {
        LodInputs first = null;
        for (VisualsExtension e : ext) {
            LodInputs in = e.postInputs(ctx);
            if (in == null) continue;
            if (first == null) {
                first = in;
            } else if (LOD_WARNED.compareAndSet(false, true)) {
                VisualsLog.warn("More than one visuals extension gives LOD inputs; only the first is used (" + e.getClass().getName() + " is ignored)");
            }
        }
        return first;
    }

    static void resetLodWarningForTest() {
        LOD_WARNED.set(false);
    }

    /** Ruling R12: prepare runs before draw in the same frame. A failure turns the shadows off until the world is reloaded. */
    private boolean prepareShadows(List<VisualsExtension> ext) {
        try {
            this.shadows.prepare(this.upload, this.download, this.shadowFrame, this.frameId, ext);
            return true;
        } catch (RuntimeException e) {
            this.shadowsFailed(e);
            return false;
        }
    }

    /** Task 5: a failed draw ends its open pass; the maps' tracked layouts are stale after it, so they are never read again. */
    private void drawShadows(VkCommandBuffer cmd, boolean vanillaOn, List<VisualsExtension> ext) {
        try {
            this.shadows.draw(cmd, this.upload, this.download, vanillaOn, Minecraft.getInstance().options.getEffectiveRenderDistance(), ext);
            this.shadowFrames++;
        } catch (RuntimeException e) {
            this.shadows.endOpenPass(cmd);
            this.shadowsFailed(e);
        }
    }

    private void shadowsFailed(RuntimeException e) {
        this.shadowsFailed = true;
        this.shadowFrame = null;
        VisualsLog.error("Visuals sun shadows failed; they stay off until the world is reloaded", e);
    }

    /** P7f design 3: every STATS_NANOS the lighting's depth copies, the shadows, the light volume and the GPU time per interval. */
    private void logStats() {
        long now = System.nanoTime();
        if (this.lastStats != Long.MIN_VALUE && now - this.lastStats < STATS_NANOS) return;
        this.lastStats = now;
        VisualsLog.info("Visuals: fps " + Minecraft.getInstance().getFps() + ", lit frames with both depth copies " + this.copiedFrames + "/"
                + this.litFrames + (this.captureFailed ? ", depth copies failed" : ""));
        this.litFrames = 0;
        this.copiedFrames = 0;
        if (this.shadows != null) {
            VisualsLog.info("Visuals shadows: drawn " + this.shadowFrames + " frames, " + (this.shadowsFailed ? "failed" : this.shadows.stats()));
            this.shadowFrames = 0;
        }
        String lightStats = this.lightVolumes.stats();
        if (lightStats != null) VisualsLog.info(lightStats);
        if (VkGpuTimer.on()) VisualsLog.info("Visuals GPU ms (" + VkGpuTimer.STATS.line() + ")");
        VkGpuTimer.STATS.clear();
    }

    /** Final fix R2: the game camera's fluid for the frame (FogType: water, lava, powder snow or none). */
    static AtmosphereFrame.CameraFluid cameraFluid(FogType fog) {
        if (fog == null) return AtmosphereFrame.CameraFluid.NONE;
        return switch (fog) {
            case WATER -> AtmosphereFrame.CameraFluid.WATER;
            case LAVA -> AtmosphereFrame.CameraFluid.LAVA;
            case POWDER_SNOW -> AtmosphereFrame.CameraFluid.POWDER_SNOW;
            default -> AtmosphereFrame.CameraFluid.NONE;
        };
    }

    /**
     * The atmosphere's view of this frame (design P7c 3.1); postProcess() checks the serial so it is used in this frame only.
     * The LOD projection and whether LODs were drawn come from this frame's LOD inputs (ruling R11: else the vanilla projection).
     * A failure here (a modded dimension's effects, say) only loses the atmosphere.
     */
    private AtmosphereFrame captureAtmosphere(Matrix4fc projection, Matrix4fc modelView, Plan plan, double cameraX, double cameraY, double cameraZ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        try {
            float partialTick = Minecraft.getInstance().getFrameTime();
            float timeOfDay = level.getTimeOfDay(partialTick);
            float rain = level.getRainLevel(partialTick);
            //P7e-3 design 3.3: the ripples flow, faster in the rain and at the speed setting (revision 2.5); 2026-10-23: by FlowClock's
            // real time (stopped while paused), not the game time's bursts
            float rippleSpeed = PostProcessSettings.from(VisualsConfig.get()).waterRippleSpeed();
            double flow = this.flowClock.advance(System.nanoTime(), Minecraft.getInstance().isPaused());
            this.flowSeconds = flow;
            this.rippleClock.advance(flow, RippleMath.speedFactor(rain, rippleSpeed));
            //2026-10-21: the plants' wind keeps time with the ripples
            FoliageWind.update(flow, RippleMath.speedFactor(rain, rippleSpeed), VisualsConfig.get().foliageWind / 100f, rain,
                    VisualsConfig.get().hangingSway / 100f);
            //Session 20: the caustics keep time with the ripples' flow
            CausticMath.setClock(flow);
            if (FrameRecorder.ON) FrameRecorder.set(FrameRecorder.WIND_PHASE, (long) (FoliageWind.phase0() * 1e6));
            Matrix4fc lodProjection = this.lod != null && this.lod.projection() != null ? this.lod.projection() : projection;
            return AtmosphereFrame.of(VkFrames.serial(), projection, lodProjection, modelView, cameraY, level.getSeaLevel(),
                    FogParameters.fromRenderSystem(), vanillaRenderDistance(), this.lod != null, timeOfDay,
                    rain, level.getThunderLevel(partialTick), level.effects().getSunriseColor(timeOfDay, partialTick))
                    .withSky(plan.sky() ? skyColour(level, partialTick) : null, Minecraft.getInstance().gameRenderer.getRenderDistance())
                    .withRipple(RippleMath.offsets(cameraX, cameraZ, this.rippleClock.phase()))
                    .withClouds(plan.cloudLayer() ? cloudFrame(level, partialTick, cameraX, cameraZ) : null)
                    .withCameraFluid(cameraFluid(Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera()));
        } catch (RuntimeException e) {
            if (!this.atmosphereWarned) {
                this.atmosphereWarned = true;
                VisualsLog.warn("Visuals atmosphere unavailable in this dimension, drawn without it: " + e);
            }
            return null;
        }
    }

    /**
     * 2026-11-09 (session 5): the discs' shape and the aurora (CelestialUniforms) for this frame: the dimension's sky, the sun's height,
     * the weather and the camera's biome climate (followed over CelestialUniforms.CLIMATE_SECONDS), the moon phase and the flow clock.
     * A failure (a modded dimension's effects) draws square discs and no aurora.
     */
    private CelestialUniforms.Values sky(PostProcessSettings settings, ClientLevel level, Vec3 camera, long serial) {
        AtmosphereFrame frame = this.atmosphere;
        if (frame == null || frame.serial() != serial) return CelestialUniforms.off();
        try {
            CelestialUniforms.Sky sky = level.effects().skyType() == DimensionSpecialEffects.SkyType.END ? CelestialUniforms.Sky.END
                    : level.dimensionType().hasSkyLight() && level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL
                    ? CelestialUniforms.Sky.SUN : CelestialUniforms.Sky.NONE;
            long now = System.nanoTime();
            float dt = this.climateNanos == 0 ? Float.NaN : (now - this.climateNanos) / 1e9f;
            this.climateNanos = now;
            float target = CelestialUniforms.climate(level.getBiome(BlockPos.containing(camera)).value().getBaseTemperature());
            this.climate = Float.isFinite(dt) ? CelestialUniforms.follow(this.climate, target, Math.min(dt, 1)) : target;
            boolean taa = settings.taa() && this.post.taaAvailable();
            return CelestialUniforms.compute(settings, sky, SunMath.sunDirection(frame.timeOfDay()).y, frame.rain(), frame.thunder(), this.climate,
                    level.getMoonPhase(), this.flowSeconds, this.frameId, taa);
        } catch (RuntimeException e) {
            return CelestialUniforms.off();
        }
    }

    /** 2026-11-13 (session 19): the End's galaxy and the Nether's heat haze for this frame (DimensionUniforms); none in a modded level that fails. */
    private DimensionUniforms.Values dimension(PostProcessSettings settings, ClientLevel level, Vec3 camera) {
        try {
            DimensionUniforms.Kind kind = level.effects().skyType() == DimensionSpecialEffects.SkyType.END ? DimensionUniforms.Kind.END
                    : level.dimensionType().ultraWarm() ? DimensionUniforms.Kind.HOT : DimensionUniforms.Kind.OTHER;
            return DimensionUniforms.compute(settings, kind, camera.y, this.flowSeconds);
        } catch (RuntimeException e) {
            return DimensionUniforms.off();
        }
    }

    /**
     * 2026-11-09 (session 6): the shader weather's values for this frame (WeatherUniforms) and its precipitation map around the camera,
     * given to the post-process before the upload commit. The camera's velocity is followed every frame (the streaks lean with it); the map
     * is kept only while it rains or snows. A failure (a modded level) draws no shader weather this frame.
     */
    private void prepareWeather(PostProcessSettings settings, ClientLevel level, Vec3 camera, AtmosphereUniforms.Values atmosphere,
                                LightVolumeUniforms.Values light, ReflectionUniforms.Values reflection, int height, long serial) {
        WeatherUniforms.Values values = WeatherUniforms.off();
        int[] map = null;
        try {
            double now = this.flowSeconds;
            if (!Double.isNaN(this.weatherLastX)) {
                this.weatherVelocity = WeatherUniforms.followVelocity(this.weatherVelocity, camera.x - this.weatherLastX, camera.y - this.weatherLastY,
                        camera.z - this.weatherLastZ, now - this.weatherLastSeconds);
            }
            this.weatherLastX = camera.x;
            this.weatherLastY = camera.y;
            this.weatherLastZ = camera.z;
            this.weatherLastSeconds = now;
            AtmosphereFrame frame = this.atmosphere;
            if (WeatherUniforms.wanted(settings) && this.post.weatherAvailable() && frame != null && frame.serial() == serial && frame.rain() > 0) {
                if (this.precipitationOf != level) {
                    this.precipitationOf = level;
                    this.precipitationLevel = new LevelPrecipitation(level);
                    this.precipitation.clear();
                    this.precipitationNanos = Long.MIN_VALUE;
                }
                //Session 7: at most PrecipitationMap.UPDATE_NANOS apart; between updates the map and its rain / snow flags stay
                long nanos = System.nanoTime();
                boolean changed = false;
                if (PrecipitationMap.due(this.precipitationNanos, nanos)) {
                    this.precipitationNanos = nanos;
                    this.precipitationLevel.newFrame();
                    changed = this.precipitation.update(this.precipitationLevel, (int) Math.floor(camera.x), (int) Math.floor(camera.y),
                            (int) Math.floor(camera.z), PrecipitationMap.BUDGET);
                }
                if (changed || this.post.precipitationMapStale()) map = this.precipitation.texels();
                values = WeatherUniforms.compute(settings, frame.rain(), frame.thunder(), frame.cameraFluid() != AtmosphereFrame.CameraFluid.NONE,
                        WeatherUniforms.unjitteredInverse(atmosphere.invVanillaProjection(), TaaJitter.appliedNdc()), atmosphere.invLodProjection(),
                        atmosphere.invView(), atmosphere.lodValid(), this.post.halfWeather() ? (height + 1) / 2 : height, camera.x, camera.y, camera.z, this.weatherVelocity, now,
                        this.precipitation.hasRain(), this.precipitation.hasSnow(), waterRain(reflection));
            }
        } catch (RuntimeException e) {
            values = WeatherUniforms.off();
            map = null;
        }
        this.post.prepareWeather(this.upload, values, light, map);
    }

    /**
     * 2026-11-10: the rings on the water as the reflection pass draws them this frame (its rain time and rain on the water, the first ripple
     * octave's offset), for the crowns of their drops; null without the reflection pass (no rings, no crowns).
     */
    static float[] waterRain(ReflectionUniforms.Values reflection) {
        if (reflection == null || !reflection.on() || reflection.offsets() == null || reflection.offsets().length < 2) return null;
        return new float[]{reflection.rainTime(), reflection.rain(), reflection.offsets()[0], reflection.offsets()[1]};
    }

    /** P7e-2: vanilla's sky colour at the camera, the reflections' fallback zenith; null when it cannot be read. */
    private static float[] skyColour(ClientLevel level, float partialTick) {
        try {
            Vec3 sky = level.getSkyColor(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(), partialTick);
            return new float[]{(float) sky.x, (float) sky.y, (float) sky.z};
        } catch (RuntimeException e) {
            //a modded dimension's sky may throw; the reflections then use the fog colour (design 3.5)
            return null;
        }
    }

    /**
     * 2026-11-09 (after session 3b): when the cloud size or count setting changes, the noise map is rebuilt off the render thread (about a
     * tenth of a second) and uploaded when done; meanwhile the old map stays. A failed build keeps the old map.
     */
    private void refreshCloudMap(PostProcessSettings settings) {
        if (this.cloudMask == null || this.post == null) return;
        if (this.cloudMapBuild != null) {
            if (!this.cloudMapBuild.isDone()) return;
            byte[] built = this.cloudMapBuild.getNow(null);
            this.cloudMapBuild = null;
            if (built != null) this.post.updateCloudMap(built);
        }
        float size = settings.cloudSize(), count = settings.cloudCount();
        if (size == this.cloudMapSize && count == this.cloudMapCount) return;
        this.cloudMapSize = size;
        this.cloudMapCount = count;
        byte[] mask = this.cloudMask;
        this.cloudMapBuild = CompletableFuture.supplyAsync(() -> CloudMapCache.withNoise(mask, size, count)).exceptionally(e -> {
            VisualsLog.warn("Visuals cloud map rebuild failed, the clouds keep their old size and count: " + e);
            return null;
        });
    }

    /** P7h design 3.3, 3.7: vanilla's clouds.png (resource packs applied) as the cloud mask (CloudMath.mask), nearest-sampled to MAP x MAP; null when unreadable (vanilla's clouds stay). The realistic style's noise map goes in by CloudMath.withNoise (session 3). */
    private static byte[] loadCloudMask() {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(new ResourceLocation("minecraft", "textures/environment/clouds.png"));
             NativeImage image = NativeImage.read(in)) {
            int[] abgr = new int[CloudMath.MAP * CloudMath.MAP];
            for (int y = 0; y < CloudMath.MAP; y++) {
                for (int x = 0; x < CloudMath.MAP; x++) {
                    abgr[y * CloudMath.MAP + x] = image.getPixelRGBA(x * image.getWidth() / CloudMath.MAP, y * image.getHeight() / CloudMath.MAP);
                }
            }
            return CloudMath.mask(abgr);
        } catch (IOException | RuntimeException e) {
            VisualsLog.warn("Visuals far clouds off, clouds.png could not be read: " + e);
            return null;
        }
    }

    /** P7h task 4 review: a dimension whose effects draw their own clouds through Forge's hook keeps them; the far clouds stay off there. */
    private static final class OwnClouds extends ClassValue<Boolean> {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return FarClouds.overrides(type, IForgeDimensionSpecialEffects.class, "renderClouds", ClientLevel.class, int.class, float.class,
                    PoseStack.class, double.class, double.class, double.class, Matrix4f.class);
        }
    }

    private static final OwnClouds OWN_CLOUDS = new OwnClouds();

    /** P7h design 3.3, 3.5: vanilla's cloud layer this frame; null when the options turn clouds off, the dimension has none or draws its own. */
    private static CloudFrame cloudFrame(ClientLevel level, float partialTick, double cameraX, double cameraZ) {
        try {
            DimensionSpecialEffects effects = level.effects();
            float height = effects.getCloudHeight();
            if (Float.isNaN(height) || OWN_CLOUDS.get(effects.getClass()) || Minecraft.getInstance().options.getCloudsType() == CloudStatus.OFF) return null;
            int ticks = ((LevelRendererTicksAccessor) Minecraft.getInstance().levelRenderer).visuals$getTicks();
            Vec3 colour = level.getCloudColor(partialTick);
            float[] offset = CloudMath.offset(cameraX, cameraZ, ticks, partialTick);
            return new CloudFrame(height, offset[0], offset[1], new float[]{(float) colour.x, (float) colour.y, (float) colour.z});
        } catch (RuntimeException e) {
            //a modded dimension's clouds may throw; its own (or vanilla's) clouds then stay, the rest of the atmosphere is kept
            return null;
        }
    }

    /** A post-process that cannot be built leaves the game drawn without it (design 3.1). */
    private static VkPostProcess createPostProcess(DeferredFree deferred) {
        try {
            return new VkPostProcess(deferred);
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals post-process not created, the game is drawn without it: " + e);
            return null;
        }
    }

    /** P7j design 3.6: without the shadow maps the lightmap stays vanilla and the lighting has no sun visibility. */
    private static VkShadowMaps createShadowMaps() {
        try {
            return new VkShadowMaps();
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals sun shadows not created: " + e);
            return null;
        }
    }

    /** Task 3: built once the device is up (the world opens after it); without it the gaps beside unbuilt sections stay. */
    private static VkEdgeCover createEdgeCover() {
        try {
            return new VkEdgeCover();
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals edge cover not created: " + e);
            return null;
        }
    }

    /** Without the copies only the water is not told apart (design P7e-1 3.6); nothing else depends on them. */
    private static VkDepthCapture createDepthCapture(DeferredFree deferred) {
        try {
            return new VkDepthCapture(deferred);
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals depth copies not created, vanilla water is not told apart: " + e);
            return null;
        }
    }

    /**
     * WorldRenderer.renderSectionLayer, around vanilla's translucent terrain (design P7e-1 3.2): copies the main depth right before
     * (VkDepthCapture.BEFORE) or right after (AFTER) it, outside the main pass, for this frame's lighting pass. Only when the
     * lighting runs this frame; a failure turns the vanilla detection off for this world.
     */
    public void captureDepth(int slot) {
        if (this.failed || this.closed || this.depthCapture == null || this.captureFailed || !VkContext.recordingFrame()) return;
        if (this.lightingChoice.serial() != VkFrames.serial() || !(this.lightingChoice.any() || this.captureUnderwater)) return;
        //endMainPass forgets Reforged's bound framebuffer, so the main target is read first
        VkContext.MainTarget main = VkContext.captureMainTarget();
        if (main == null || main.width() <= 0 || main.height() <= 0) return;
        VkCommandBuffer cmd = VkContext.frameCommands();
        try {
            this.depthCapture.ensureSize(main.width(), main.height());
            VkContext.endMainPass();
            try {
                VkGpuTimer.mark(cmd, "depth.copy");
                VkContext.mainDepthToShaderRead(cmd, main);
                VkBarriers.mainDepthForCompute(cmd);
                this.depthCapture.capture(cmd, slot, this.mainDepthViews.get(main.identity(), this.deferred, VkFrames.serial()));
                //2026-10-29: the colour with it, for what is drawn over the translucent terrain later (no reflection there)
                if (slot == VkDepthCapture.AFTER && this.lightingChoice.reflections()) {
                    VkContext.mainColourToShaderRead(cmd, main);
                    VkBarriers.mainColourForCompute(cmd);
                    this.depthCapture.captureColour(cmd, VkContext.mainColourView(main));
                    VkBarriers.mainColourAfterCompute(cmd);
                    VkContext.mainColourToAttachment(cmd, main);
                }
            } finally {
                VkBarriers.mainDepthAfterCompute(cmd);
                VkContext.resumeMainPass();
                VkContext.invalidatePipelineCache();
                VkGpuTimer.mark(cmd, "vanilla.late");
            }
        } catch (RuntimeException e) {
            this.captureFailed = true;
            VisualsLog.error("Visuals depth copy failed; vanilla water is not told apart until the world is reloaded", e);
        }
    }

    /**
     * GameRenderer.renderLevel right after the level (GameRendererPostMixin), before the hand and the GUI: the main colour is
     * copied and processed in compute outside the pass, then one draw in the resumed pass replaces it. Every return before the
     * draw (no world, no size, off, not recording, failed) leaves this frame's flags vanilla.
     */
    public void postProcess() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        RenderTarget target = mc.getMainRenderTarget();
        PostProcessSettings settings = PostProcessSettings.from(VisualsConfig.get());
        boolean enabled = settings.enabled(), unavailable = this.closed || this.post == null || this.postFailed;
        if (!PostGate.shouldRun(level != null, target.width, target.height, enabled, VkContext.recordingFrame(), unavailable)) {
            //P7l design 3.1: no post-process, no volume (a frame only skipped by Reforged or a minimised window keeps it)
            if ((level == null || !enabled || unavailable) && this.lightVolumes != null) this.lightVolumes.update(this.upload, null, 0, 0, 0, 0, false);
            FrameFlags.resetForSkippedPost();
            return;
        }
        //Review focus 5: binds the main target when a mod left another framebuffer bound; does nothing while the main pass is bound
        VkContext.resumeMainPass();
        VkContext.MainTarget main = VkContext.captureMainTarget();
        if (main == null || main.width() <= 0 || main.height() <= 0) {
            FrameFlags.resetForSkippedPost();
            return;
        }
        VkCommandBuffer cmd = VkContext.frameCommands();
        long cpuStart = System.nanoTime();
        long serial = VkFrames.serial();
        try {
            this.post.ensureSize(main.width(), main.height());
            float partialTick = mc.getFrameTime();
            boolean hasSkyLight = level.dimensionType().hasSkyLight();
            Plan plan = Plan.of(hasSkyLight, settings);
            //P7l design 3.1: the light volume around the camera (read here, not cached by frame(), which stops once it has failed),
            // filled a budget of sections per frame
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            VkLightVolume lightVolume = this.lightVolumes.update(this.upload, level, camera.x, camera.y, camera.z,
                    mc.options.getEffectiveRenderDistance(), plan.lighting());
            //The extensions' LOD inputs of this frame; Task 4: none when the atmosphere does not take the LOD depth this frame
            LodInputs lod = this.lodSerial == serial ? this.lod : null;
            AtmosphereUniforms.Values atmosphere = AtmosphereUniforms.compute(settings, this.atmosphere, serial, hasSkyLight, lod != null && lod.sizeMatches());
            if (!atmosphere.lodValid()) lod = null;
            //2026-10-30 (audit): after the LOD inputs not drawn this frame are dropped (the far clouds and the shafts used their reach)
            float farSections = farSections(lod);
            boolean copies = this.depthCapture != null && this.depthCapture.captured(serial, main.width(), main.height());
            //2026-11-09 (session 2b): with the camera in water, the open surface above it and its sky light
            float surfaceUp = 0, skyAccess = 0;
            if (this.atmosphere != null && this.atmosphere.serial() == serial && this.atmosphere.cameraInWater()) {
                surfaceUp = surfaceAbove(level, camera.x, camera.y, camera.z);
                skyAccess = level.getBrightness(LightLayer.SKY, BlockPos.containing(camera)) / 15f;
            }
            this.post.prepareUnderwater(UnderwaterUniforms.compute(settings, this.atmosphere, serial, hasSkyLight, surfaceUp, skyAccess, copies));
            this.post.prepareSky(sky(settings, level, camera, serial));
            //2026-11-13 (session 19): the End's galaxy and the Nether's heat haze
            this.post.prepareDimension(dimension(settings, level, camera));
            //Session 19: the nether portals near the camera (their light and glow)
            this.post.preparePortals(settings.portalGlow() > 0 ? this.portals.update(level, camera.x, camera.y, camera.z) : java.util.List.of(),
                    camera.x, camera.y, camera.z, settings.portalGlow());
            //2026-11-10 (session 8): with the picked block light and moon colours (slots 31, 32)
            LightingUniforms.Values lighting = LightingUniforms.compute(settings, this.lightingChoice, this.atmosphere, serial,
                    atmosphere.lodValid(), this.skyExposure.value(), copies, hasSkyLight).tinted(settings.colours());
            //P7j design 3.3, P7f-2: the last valid cascades, rebased to this frame's sun views (which exist only when this frame
            // prepared the maps); never after a shadow failure
            boolean shadowsOn = this.shadowsOn();
            ShadowMath.Cascade shadowNow = this.shadowFrame != null && this.shadowFrame.serial() == serial ? this.shadowFrame.near() : null;
            VkShadowMaps.Maps near = shadowsOn ? this.shadows.near() : null;
            VkShadowMaps.Maps far = shadowsOn ? this.shadows.far() : null;
            VkShadowMaps.Maps close = shadowsOn ? this.shadows.close() : null;
            ShadowUniforms.Values shadow = ShadowUniforms.compute(lighting, this.atmosphere, shadowNow, near, far, close);
            //2026-10-29: the entity shadow map, from this frame's sun at the camera, when entities were captured
            ShadowMath.Cascade entityCascade = shadowNow != null && this.entityShadows != null && EntityShadowCapture.vertexCount(serial) > 0
                    ? VkEntityShadows.cascade(SunMath.sunDirection(this.shadowFrame.timeOfDay()), shadowNow.cameraX(), shadowNow.cameraY(), shadowNow.cameraZ()) : null;
            this.post.prepareEntityShadows(EntityShadowUniforms.compute(entityCascade, shadowNow, this.atmosphere, shadow.near() || shadow.far()),
                    this.entityShadows != null ? this.entityShadows.view() : 0);
            ReflectionUniforms.Values reflection = plan.reflections() ? ReflectionUniforms.compute(settings, this.lightingChoice, lighting,
                    this.atmosphere, atmosphere.night(), this.skyExposure.value(), this.frameId, this.flowSeconds) : ReflectionUniforms.off();
            refreshCloudMap(settings);
            CloudUniforms.Values clouds = CloudUniforms.compute(settings, this.atmosphere, atmosphere, serial, hasSkyLight,
                    this.post.cloudMapReady(), farSections);
            if (lighting.on()) {
                this.litFrames++;
                if (copies) this.copiedFrames++;
            }
            //Volumetric light pass (2026-10-15): with the sun's cascades the shafts follow them instead of the screen-space ones
            VolumetricLight.Values volumetric = AtmosphereUniforms.active(this.atmosphere, serial, hasSkyLight)
                    ? VolumetricLight.compute(settings, this.atmosphere, lighting, shadow, this.skyExposure.value(),
                    this.atmosphere.skyFogEnd() > 0 ? this.atmosphere.skyFogEnd() : ReflectionUniforms.DEFAULT_SKY_FOG_END,
                    farSections * CloudMath.LOD_BLOCKS_PER_SECTION) : VolumetricLight.off();
            PostUniforms.Values values = PostUniforms.compute(settings, main.width(), main.height(), level.getTimeOfDay(partialTick), hasSkyLight,
                    this.post.bloomLevels(), volumetric.on() ? volumetric.shaftLight() : atmosphere.shaftLight());
            //P7l design 3.3-3.5: the per-pixel light; the sun visibility is valid with this frame's lighting and a cascade
            LightVolumeUniforms.Values light = LightVolumeUniforms.compute(settings, lightVolume == null ? null : lightVolume.layout(),
                    camera.x, camera.y, camera.z, this.atmosphere, serial, hasSkyLight, lighting.on() && (shadow.near() || shadow.far()));
            //2026-10-23: the terrain light, where this frame's terrain filled it (the last frame cleared it at this size)
            boolean terrainOn = VisualsConfig.terrainLight && light.on();
            boolean terrainFilled = terrainOn && this.terrainLight.filled(main.width(), main.height());
            light = light.withTerrain(terrainFilled);
            //P7k design 3: the held light, with the per-pixel light it lifts
            this.heldLight.update(this.upload, settings.heldLight() && light.on());
            //2026-11-10 (session 9): PBR's wetness, and whether this frame's terrain wrote its materials (the composite's PBR build then)
            PbrUniforms.update(level, net.minecraft.core.BlockPos.containing(camera), partialTick);
            this.post.preparePbr(terrainFilled && this.terrainLight.materialFilled(main.width(), main.height()));
            this.post.prepare(this.upload, atmosphere, lighting, reflection, clouds, shadow, light, this.heldLight,
                    BloomFog.compute(settings, this.atmosphere, serial, hasSkyLight, this.skyExposure.value()), volumetric);
            //2026-11-09 (session 6): the shader weather (its precipitation map uploaded when it changed)
            this.prepareWeather(settings, level, camera, atmosphere, light, reflection, main.height(), serial);
            //TAA pass (2026-10-15): with this frame's camera (the post-process frame's matrices), which the next frame reprojects from
            boolean taa = settings.taa() && this.post.taaAvailable() && this.atmosphere != null && this.atmosphere.serial() == serial;
            this.post.prepareTaa(this.upload, taa ? new VkTaa.Frame(this.atmosphere.invVanillaProjection(), this.atmosphere.invLodProjection(),
                    this.atmosphere.invModelView(), camera.x, camera.y, camera.z, atmosphere.lodValid(),
                    mc.options.getEffectiveRenderDistance() * 16f) : null);
            //P7l design 3.4: with the per-pixel light the lightmap stays vanilla (the copy lifts the sky light where the sun reaches)
            SunShadows.set(light.on() ? 0 : SunShadows.strength(settings, this.lightingChoice, serial, shadowsOn));
            VkContext.endMainPass();
            try {
                VkGpuTimer.reset(cmd);//when frame() did not run this frame
                VkGpuTimer.mark(cmd, "post.upload");
                this.lightVolumes.recordClear(cmd);
                this.upload.commit(cmd);
                VkContext.mainColourToShaderRead(cmd, main);
                VkContext.mainDepthToShaderRead(cmd, main);
                VkBarriers.mainColourForCompute(cmd);
                //Global: also covers the compute read of an extension's depth, last made readable for fragment shaders
                VkBarriers.mainDepthForCompute(cmd);
                //P7l design 3.4: the lightmap view records a layout barrier, so it is taken here, outside the main pass
                long lightmap = VkContext.lightmapView(cmd);
                long mainDepth = this.mainDepthViews.get(main.identity(), this.deferred, serial);
                //P7e-1 3.2: a copy not valid this frame is replaced by the main depth, which the lighting pass then does not read
                long before = copies ? this.depthCapture.image(VkDepthCapture.BEFORE).sampledView() : mainDepth;
                long after = copies ? this.depthCapture.image(VkDepthCapture.AFTER).sampledView() : mainDepth;
                //Review focus 5: the lighting always binds valid images; without a cascade or LODs the main depth stands in (not read)
                VkPostProcess.Depths depths = PostInputs.depths(mainDepth, lod, lighting.on(), before, after, shadow.near() ? near : null,
                        shadow.far() ? far : null, shadow.close() ? close : null);
                if (terrainFilled) this.terrainLight.toCompute(cmd);
                //2026-10-29: the colour right after the translucent terrain (0: none this frame; the composite then compares with itself)
                this.post.colourAfterTranslucent(this.depthCapture != null ? this.depthCapture.colourAfter(serial, main.width(), main.height()) : 0);
                if (entityCascade != null) this.entityShadows.draw(cmd, entityCascade, EntityShadowCapture.data(), EntityShadowCapture.vertexCount(serial));
                this.post.compute(cmd, VkContext.mainColourView(main), depths,
                        new VkPostProcess.LightInputs(lightVolume == null ? null : lightVolume.data(), lightVolume == null ? null : lightVolume.ready(), lightmap,
                                lod != null ? lod.lightView() : 0, this.heldLight.on() ? this.heldLight.buffer() : null, this.terrainLight.view(),
                                this.terrainLight.albedoView(), this.terrainLight.normalView(), this.terrainLight.specularView()));
                //P7i: SMAA on the final colour, before the main pass resumes (its result is blitted by post.draw)
                this.post.antiAlias(cmd, values, settings.antiAliasing());//marks its own intervals
                //2026-10-30 (audit): the next frame is jittered only when TAA resolved this one (set before it ran, a failed TAA left a frame shaking)
                TaaJitter.setActive(taa && this.post.taaDrawn());
                //Read: cleared for the next frame's terrain (which writes only after this clear)
                //Session 9: with the material images while the terrain may draw its PBR build (PbrAtlas built with a map)
                this.terrainLight.prepareNext(cmd, terrainOn, terrainOn && PbrAtlas.wanted() && PbrAtlas.active(), main.width(), main.height(), main.identity());
            } finally {
                VkBarriers.mainColourAfterCompute(cmd);
                VkContext.mainColourToAttachment(cmd, main);
                VkContext.resumeMainPass();
            }
            try {
                VkGpuTimer.mark(cmd, "post.draw");
                this.post.draw(cmd, values);
                VkGpuTimer.mark(cmd, "end");
                //2026-10-30 (audit): vanilla's clouds are left out next frame only after this frame's far clouds were drawn
                FarClouds.set(clouds.on());
                //2026-11-09 (session 6): vanilla's rain and snow are left out from the next frame on while the shader can draw them (the
                // setting on, its pass built, this frame's atmosphere captured: without it, as in a modded dimension, vanilla's stays)
                ShaderWeather.set(WeatherUniforms.wanted(settings) && this.post.weatherAvailable() && this.atmosphere != null
                        && this.atmosphere.serial() == serial);
                this.postFailures = 0;
            } finally {
                VkContext.invalidatePipelineCache();//the post-process pipeline replaced the one Reforged has cached
            }
        } catch (RuntimeException e) {
            FrameFlags.resetForSkippedPost();
            //2026-10-30 (audit): a passing failure (the upload stream full in an LOD burst, an allocation in a resize) skips the frame;
            // only POST_FAILURES_ALLOWED in a row turn the post-process off
            if (++this.postFailures >= POST_FAILURES_ALLOWED) {
                this.postFailed = true;
                VisualsLog.error("Visuals post-process failed; it stays off until the world is reloaded", e);
            } else {
                VisualsLog.warn("Visuals post-process failed, frame skipped (" + this.postFailures + " in a row): " + e);
            }
        }
        VkGpuTimer.cpu("post", System.nanoTime() - cpuStart);
    }

    /**
     * Frame generation's capture (FG design 4): this serial's LOD depth view and inverse LOD projection (jittered as drawn), or null
     * when no extension gave LOD inputs this frame or their size does not match the main target's.
     */
    public Visuals.FrameGenLod frameGenLod() {
        LodInputs lod = this.lodSerial == VkFrames.serial() ? this.lod : null;
        if (this.closed || lod == null || !lod.sizeMatches() || lod.projection() == null) return null;
        return new Visuals.FrameGenLod(lod.depthView(), new Matrix4f(lod.projection()).invert());
    }

    /** F3: the renderer's state, then each extension's lines. */
    public void addDebugInfo(List<String> lines) {
        lines.add("Visuals: post " + (this.post == null ? "unavailable" : this.postFailed ? "failed" : PostProcessSettings.from(VisualsConfig.get()).enabled() ? "on" : "off")
                + ", shadows " + (this.shadows == null ? "off" : this.shadowsFailed ? "failed" : "on")
                + ", edge cover " + (this.edgeCover == null ? "off" : this.edgeCover.lastQuads() + " quads") + (this.failed ? ", frame failed" : ""));
        addExtensionDebugInfo(VisualsExtensions.all(), lines);
    }

    /** True for the first frame() of a serial; a repeat in the same serial is skipped. */
    boolean claimSerial(long serial) {
        if (serial == this.lastFrameSerial) return false;
        this.lastFrameSerial = serial;
        return true;
    }

    private void onRecordingStarted() {
        this.upload.tick();
        this.download.tick();
        this.deferred.tick(VkFrames.completedSerial());
        if (this.shadows != null) this.shadows.tick();
        if (this.post != null) this.post.tick();
        if (this.depthCapture != null) this.depthCapture.tick();
        if (this.terrainLight != null) this.terrainLight.tick();
    }

    /** Safe on a partially created renderer and when called again: every step checks what exists, the second call frees nothing. */
    public void shutdown() {
        close();
        free(true);
    }

    /** 2026-10-31: out of every frame from now on (Visuals.shutdown frees the rest once no frame can use it); safe to call again. */
    public void close() {
        FrameFlags.resetForSkippedPost();
        this.closed = true;
        if (this.listening) {
            VkFrames.removeListener(this.frameListener);
            this.listening = false;
        }
    }

    /**
     * What the renderer gave the device; after close, once no frame can still use it. gpuTimer: the device's GPU timer pool goes too
     * (not while the next renderer's frames may use it: it is shared). The second call frees nothing.
     */
    public void free(boolean gpuTimer) {
        if (this.freed) return;
        this.freed = true;
        //The streams are built first: without them nothing was given to the device
        if (this.upload != null) VkContext.waitIdle();
        if (gpuTimer) VkGpuTimer.free();
        if (this.post != null) this.post.free();
        if (this.depthCapture != null) this.depthCapture.free();
        if (this.edgeCover != null) this.edgeCover.free();
        if (this.shadows != null) this.shadows.free();
        if (this.entityShadows != null) this.entityShadows.free();
        if (this.lightVolumes != null) this.lightVolumes.free();
        if (this.heldLight != null) this.heldLight.free();
        if (this.terrainLight != null) this.terrainLight.free();
        this.mainDepthViews.free();
        this.deferred.freeAll();
        if (this.upload != null) this.upload.free();
        if (this.download != null) this.download.free();
        this.lod = null;
        if (this.upload != null) VisualsLog.info("Visuals renderer shut down");
    }
}
