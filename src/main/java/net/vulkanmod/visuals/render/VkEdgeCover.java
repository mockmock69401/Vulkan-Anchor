package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.vulkanmod.visuals.VisualsLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 2026-10-16 (CTE2 report: an x-ray look through chunks not built yet): EdgeCover's quads over the sides of built sections next to
 * holes, drawn into the main pass after the LOD composite. A hole is a section VulkanMod's traversal saw this frame but has not built
 * (not empty air), one at the render distance itself (never built), or, since 2026-10-17 (second CTE2 report, the loaded area's own
 * edge after an L-shaped move), no section at all. From inside the built section the hole's LODs win (shader). Gate GEC: the quads
 * go into a host buffer per frame slot, never through the shared upload stream (a frame's cells overflowed it and stopped the
 * render system). 2026-10-17 (CTE2 log: 0.5-1 ms a frame): quads are kept relative to an origin that moves only with the camera
 * far away, a slot's buffer is rewritten only when the faces changed, and the grid is searched for new faces every SCAN_FRAMES.
 */
public final class VkEdgeCover {
    static final String PROGRAM = "edgeCover";
    static final VkGraphicsProgram.State STATE = new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkGraphicsProgram.RGBA, false,
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
    static final int MVP = 0, CAMERA_FRAC = 1, ATLAS = 0, LIGHTMAP = 1, QUADS = 2, LOD_DEPTH = 3;
    /** Time a frame may spend building new faces' quads (render thread). */
    static final long BUILD_NANOS = 500_000;
    /** How often the cost line is logged. */
    static final long STATS_NANOS = 10_000_000_000L;
    /** At most this many quads a frame (48 bytes each): about 6 MB of host memory a frame slot. */
    static final int MAX_QUADS = 1 << 17;
    /** A frame slot's first buffer (about 21000 quads). */
    static final long INITIAL_BYTES = 1 << 20;
    /** The grid is searched for new faces every this many frames; faces whose hole was built go every frame. */
    static final int SCAN_FRAMES = 4;
    /** The quads' origin moves when the camera is this far (blocks) from it, and every face is rebuilt. */
    static final int ORIGIN_REACH = 1024;

    /** A face's quads (origin relative) and the section across: null when it is outside the grid. */
    private record Built(float[] quads, RenderSection across) {
    }

    private static final class Slot {
        VkGpuBuffer buffer;
        long version = -1;
        int quads;
    }

    private final VkGraphicsProgram program;
    private final LooseUniformWriter loose;
    private final long atlasSampler = VkSamplers.nearestClamp(), lightSampler = VkSamplers.linearClamp();
    private final Map<EdgeCover.Face, Built> built = new HashMap<>();
    private final Map<Integer, Slot> slots = new HashMap<>();
    /**
     * A 1x1 depth bound in place of the LOD depth when there is none (never read: the compare flag is 0). It is its own image, kept
     * in the read layout, so the main pass's depth attachment is never also a descriptor.
     */
    private final VkImage2D standIn = VkImage2D.createDepth(VkContext.depthFormat(), 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
    private long version;
    private int frame;
    private int originX, originY, originZ;
    private boolean hasOrigin;
    private Slot frameSlot;
    private int quadCount;
    private boolean disabled;
    private long statSince, statNanos, statMax;
    private int statFrames;

    public VkEdgeCover() {
        this.program = VkGraphicsProgram.of(PROGRAM, STATE);
        this.loose = new LooseUniformWriter(this.program.meta());
    }

    /** Before the main pass: this frame's faces into this frame slot's buffer. */
    public void prepare(DeferredFree deferred, boolean on, double cameraX, double cameraY, double cameraZ) {
        long start = System.nanoTime();
        this.standIn.tick();
        try {
            this.prepare0(deferred, on, cameraX, cameraY, cameraZ);
        } finally {
            if (on) this.stat(System.nanoTime() - start);
        }
    }

    /** Final review I4: the cost on the render thread, logged every STATS_NANOS like the other Visuals stat lines. */
    private void stat(long nanos) {
        this.statFrames++;
        this.statNanos += nanos;
        this.statMax = Math.max(this.statMax, nanos);
        long now = System.nanoTime();
        if (this.statSince == 0) this.statSince = now;
        if (now - this.statSince < STATS_NANOS) return;
        VisualsLog.info(String.format("Visuals edge cover: %d frames, cpu avg %.3f ms max %.3f ms, faces %d, quads %d", this.statFrames,
                this.statNanos / 1e6 / this.statFrames, this.statMax / 1e6, this.built.size(), this.quadCount));
        this.statSince = now;
        this.statFrames = 0;
        this.statNanos = 0;
        this.statMax = 0;
    }

    private void clear() {
        if (!this.built.isEmpty()) this.version++;
        this.built.clear();
    }

    private void prepare0(DeferredFree deferred, boolean on, double cameraX, double cameraY, double cameraZ) {
        this.quadCount = 0;
        if (this.disabled) return;
        try {
            net.vulkanmod.render.chunk.WorldRenderer world = net.vulkanmod.render.chunk.WorldRenderer.getInstance();
            SectionGrid grid = world == null ? null : world.getSectionGrid();
            ClientLevel level = Minecraft.getInstance().level;
            if (!on || grid == null || grid.sections == null || level == null) {
                this.clear();
                return;
            }
            //Final review I2: vanilla's environmental fog (in water or lava, blindness, darkness) hides the terrain but not the
            // covers, which have no fog of their own; there they would stand out lit, so none are drawn
            if (environmentalFog()) return;
            net.vulkanmod.render.chunk.graph.SectionGraph graph = world.getSectionGraph();
            if (graph == null) return;
            int cx = (int) Math.floor(cameraX), cy = (int) Math.floor(cameraY), cz = (int) Math.floor(cameraZ);
            if (!this.hasOrigin || Math.abs(cx - this.originX) > ORIGIN_REACH || Math.abs(cy - this.originY) > ORIGIN_REACH
                    || Math.abs(cz - this.originZ) > ORIGIN_REACH) {
                this.clear();
                this.originX = cx;
                this.originY = cy;
                this.originZ = cz;
                this.hasOrigin = true;
            }
            //Every frame: faces whose hole was built go (their stone would stand in the new terrain)
            for (Iterator<Built> it = this.built.values().iterator(); it.hasNext(); ) {
                RenderSection across = it.next().across();
                //Built and drawn: the hole is filled (a build alone can be stale where the chunk is not ready)
                if (across != null && across.isCompiled() && net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE != null
                        && net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE.chunkRenderReady(across.xOffset() >> 4, across.zOffset() >> 4)) {
                    it.remove();
                    this.version++;
                }
            }
            if (this.frame++ % SCAN_FRAMES == 0) this.scan(grid, graph, level, cx, cz);
            this.write(deferred);
        } catch (RuntimeException | LinkageError e) {
            this.disabled = true;
            this.quadCount = 0;
            VisualsLog.warn("Visuals edge cover disabled: " + e);
        }
    }

    /** The grid's faces next to holes: those gone are dropped, new ones built within the time budget. */
    private void scan(SectionGrid grid, net.vulkanmod.render.chunk.graph.SectionGraph graph, ClientLevel level, int cx, int cz) {
        Holes holes = new Holes((short) graph.getLastFrame(), level, cx >> 4, cz >> 4, Minecraft.getInstance().options.getEffectiveRenderDistance());
        Map<EdgeCover.Face, RenderSection> faces = new HashMap<>();
        for (RenderSection s : grid.sections) {
            if (s == null || !s.isCompiled() || s.isCompletelyEmpty()) continue;
            //Gate L-move: a section VulkanMod draws: built and its chunk render-ready (a section the ring moved keeps a stale build)
            if (!holes.ready(s.xOffset() >> 4, s.zOffset() >> 4)) continue;
            //VulkanMod's west, east, north, south: EdgeCover's NEG_X, POS_X, NEG_Z, POS_Z (no array a section: GC pauses)
            this.face(faces, holes, s, s.adjWest, EdgeCover.NEG_X);
            this.face(faces, holes, s, s.adjEast, EdgeCover.POS_X);
            this.face(faces, holes, s, s.adjNorth, EdgeCover.NEG_Z);
            this.face(faces, holes, s, s.adjSouth, EdgeCover.POS_Z);
        }
        if (this.built.keySet().retainAll(faces.keySet())) this.version++;
        if (faces.isEmpty()) return;
        LevelBlocks blocks = new LevelBlocks(level);
        float[] stone = blocks.spriteOf(Blocks.STONE.defaultBlockState());
        FloatBuffer scratch = FloatBuffer.allocate(16 * EdgeCover.FLOATS_PER_QUAD);
        long buildUntil = System.nanoTime() + BUILD_NANOS;
        for (Map.Entry<EdgeCover.Face, RenderSection> e : faces.entrySet()) {
            if (this.built.containsKey(e.getKey())) continue;
            if (System.nanoTime() > buildUntil) break;
            EdgeCover.Face f = e.getKey();
            scratch.clear();
            EdgeCover.quads(f, blocks, stone, this.originX, this.originY, this.originZ, scratch);
            float[] q = new float[scratch.position()];
            scratch.flip();
            scratch.get(q);
            this.built.put(f, new Built(q, e.getValue()));
            this.version++;
        }
    }

    private void face(Map<EdgeCover.Face, RenderSection> faces, Holes holes, RenderSection s, RenderSection n, int dir) {
        //VulkanMod's grid is a ring: at the loaded area's own edge the link points at a section on the far side of it
        if (n != null && (n.xOffset() != s.xOffset() + 16 * DX[dir] || n.zOffset() != s.zOffset() + 16 * DZ[dir] || n.yOffset() != s.yOffset())) n = null;
        int ncx = (s.xOffset() >> 4) + DX[dir], ncz = (s.zOffset() >> 4) + DZ[dir];
        if (holes.hole(n, ncx, s.yOffset() >> 4, ncz)) faces.put(new EdgeCover.Face(s.xOffset() >> 4, s.yOffset() >> 4, s.zOffset() >> 4, dir), n);
    }

    private static final int[] DX = {-1, 1, 0, 0}, DZ = {0, 0, -1, 1};

    /** This frame slot's buffer, rewritten when the faces changed since it was last written. */
    private void write(DeferredFree deferred) {
        int quads = 0;
        for (Built b : this.built.values()) quads += b.quads().length / EdgeCover.FLOATS_PER_QUAD;
        quads = Math.min(quads, MAX_QUADS);
        if (quads == 0) return;
        int slotId = VkFrames.recordingSlot();
        Slot slot = this.slots.computeIfAbsent(slotId, k -> new Slot());
        if (slot.version != this.version) {
            long bytes = (long) quads * EdgeCover.FLOATS_PER_QUAD * 4;
            if (slot.buffer == null || slot.buffer.size() < bytes) {
                //Final review I4: a host allocation took up to 18 ms when the buffer grew in small steps; 1 MB, then doubling
                long size = slot.buffer == null ? INITIAL_BYTES : slot.buffer.size();
                while (size < bytes) size *= 2;
                if (slot.buffer != null) {
                    VkGpuBuffer old = slot.buffer;
                    deferred.retire(old::free, VkFrames.serial());
                }
                slot.buffer = VkGpuBuffer.host(size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            }
            //The slot's fence was waited before this frame recorded: no frame on the GPU still reads this buffer
            FloatBuffer out = slot.buffer.mapped().asFloatBuffer();
            int written = 0;
            for (Built b : this.built.values()) {
                int n = Math.min(b.quads().length / EdgeCover.FLOATS_PER_QUAD, quads - written);
                if (n <= 0) break;
                out.put(b.quads(), 0, n * EdgeCover.FLOATS_PER_QUAD);
                written += n;
            }
            slot.quads = written;
            slot.version = this.version;
        }
        this.frameSlot = slot;
        this.quadCount = slot.quads;
    }

    /**
     * Inside the resumed main pass, after the composite; mvp is Reforged's (camera-relative world, 0..1 depth); lodDepth the LOD
     * pass's depth (where the hole's LODs win from inside). Null: there are no LODs, the stand-in depth is bound in its place and the
     * shader does no depth compare (recordStandIn must have run outside the pass; otherwise nothing is drawn).
     */
    public void draw(VkCommandBuffer cmd, Matrix4fc mvp, long lightmapView, VkImage2D lodDepth, double cameraX, double cameraY, double cameraZ) {
        if (this.quadCount == 0 || lightmapView == 0 || this.frameSlot == null) return;
        long atlas = atlasView();
        if (atlas == 0) return;
        //Without LODs the stand-in must already be in the read layout (recordStandIn, outside the pass): a barrier cannot be recorded here
        if (lodDepth == null && this.standIn.layout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) return;
        DepthBinding depth = depthBinding(lodDepth != null, lodDepth == null ? 0 : lodDepth.sampledView(), this.standIn.sampledView());
        //The quads are relative to the origin: the camera relative to it
        this.loose.putMat4(MVP, mvp).putVec4(CAMERA_FRAC, (float) (cameraX - this.originX), (float) (cameraY - this.originY), (float) (cameraZ - this.originZ),
                depth.compare());
        this.program.bind(cmd, VkContext.boundRenderPassHandle(), VkContext.boundRenderPassKey(),
                new VkBindings().sampledView(ATLAS, atlas, this.atlasSampler).sampledView(LIGHTMAP, lightmapView, this.lightSampler)
                        .sampledView(LOD_DEPTH, depth.view(), this.atlasSampler).storage(QUADS, this.frameSlot.buffer), this.loose);
        vkCmdDraw(cmd, 6, this.quadCount, 0, 0);
    }

    /**
     * Outside any render pass, once before the first draw without LODs: moves the stand-in depth to the read layout (a no-op after
     * that). Call it every frame outside the pass, as the edge cover is prepared.
     */
    public void recordStandIn(VkCommandBuffer cmd) {
        if (this.standIn.layout() == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) return;
        this.standIn.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /** The view bound at LOD_DEPTH and the shader's compare switch (cameraFrac.w). */
    record DepthBinding(long view, float compare) {
    }

    static DepthBinding depthBinding(boolean hasLodDepth, long lodView, long standInView) {
        return hasLodDepth ? new DepthBinding(lodView, 1f) : new DepthBinding(standInView, 0f);
    }

    /** The shader's LOD-depth compare switch: 1 with a LOD depth, 0 without. */
    static float compareFlag(VkImage2D lodDepth) {
        return lodDepth == null ? 0f : 1f;
    }

    public int lastQuads() {
        return this.quadCount;
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        for (Slot s : this.slots.values()) if (s.buffer != null) s.buffer.free();
        this.program.free();
        VkSamplers.free(this.atlasSampler);
        VkSamplers.free(this.lightSampler);
        this.standIn.free();
    }

    private static boolean environmentalFog() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) return true;
        return mc.player != null && (mc.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) || mc.player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS));
    }

    private static long atlasView() {
        int id = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
        GlTexture texture = GlTexture.getTexture(id);
        VulkanImage image = texture == null ? null : texture.getVulkanImage();
        return image == null ? 0 : image.getImageView();
    }

    /**
     * Which neighbours are holes. frame: the traversal's last frame, which a section it reached carries in lastFrame. Empty air
     * comes from the level's chunk: VulkanMod's isCompletelyEmpty is true for every section it has not built yet (gate GEC).
     */
    private record Holes(short frame, ClientLevel level, int camChunkX, int camChunkZ, int renderDistance,
                         IdentityHashMap<RenderSection, Boolean> memo, it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap readyMemo) {
        Holes(short frame, ClientLevel level, int camChunkX, int camChunkZ, int renderDistance) {
            this(frame, level, camChunkX, camChunkZ, renderDistance, new IdentityHashMap<>(), new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap());
        }

        /** VulkanMod draws the chunk's sections (its ChunkStatusMap: data, light and neighbours ready). Once a scan a chunk. */
        boolean ready(int cx, int cz) {
            long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
            if (this.readyMemo.containsKey(key)) return this.readyMemo.get(key);
            net.vulkanmod.render.chunk.ChunkStatusMap map = net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE;
            boolean r = map != null && map.chunkRenderReady(cx, cz);
            this.readyMemo.put(key, r);
            return r;
        }

        /**
         * Whether the neighbour at section (sx, sy, sz) is a hole: its chunk not drawn by VulkanMod (the loaded area's edge, gate
         * L-move: sections over chunks not ready can carry an old build), or drawn but this section seen and not built yet.
         */
        boolean hole(RenderSection n, int sx, int sy, int sz) {
            if (!this.ready(sx, sz)) return true;
            if (n == null) return true;
            if (n.isCompiled()) return false;
            //Final review I4: up to four built neighbours ask about the same section; its chunk is looked at once a scan
            Boolean known = this.memo.get(n);
            if (known != null) return known;
            boolean h = n.lastFrame == this.frame && this.notAir(sx, sy, sz);//never seen: deep underground, never shown
            this.memo.put(n, h);
            return h;
        }

        private boolean notAir(int sx, int sy, int sz) {
            net.minecraft.world.level.chunk.LevelChunk chunk = this.level.getChunkSource().getChunk(sx, sz, false);
            if (chunk == null) return true;
            int index = this.level.getSectionIndexFromSectionY(sy);
            return index >= 0 && index < chunk.getSections().length && !chunk.getSections()[index].hasOnlyAir();
        }
    }

    private static final class LevelBlocks implements EdgeCover.Blocks {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        LevelBlocks(ClientLevel level) {
            this.level = level;
        }

        @Override
        public boolean full(int x, int y, int z) {
            BlockState state = this.level.getBlockState(this.pos.set(x, y, z));
            return state.isSolidRender(this.level, this.pos);
        }

        /**
         * Clients have no MOTION_BLOCKING_NO_LEAVES heightmap: MOTION_BLOCKING's, then down to the first block that heightmap counts
         * (final review I3: vines, flowers and snow under a canopy are not it).
         */
        @Override
        public int surface(int x, int z) {
            int y = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            int min = this.level.getMinBuildHeight();
            while (y > min && !Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(this.level.getBlockState(this.pos.set(x, y - 1, z)))) {
                y--;
            }
            return y;
        }

        @Override
        public int sky(int x, int y, int z) {
            return this.level.getBrightness(LightLayer.SKY, this.pos.set(x, y, z));
        }

        @Override
        public int block(int x, int y, int z) {
            return this.level.getBrightness(LightLayer.BLOCK, this.pos.set(x, y, z));
        }

        @Override
        public float[] sprite(int x, int y, int z) {
            return this.spriteOf(this.level.getBlockState(this.pos.set(x, y, z)));
        }

        float[] spriteOf(BlockState state) {
            TextureAtlasSprite s = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            return new float[]{s.getU0(), s.getV0(), s.getU1(), s.getV1()};
        }
    }
}
