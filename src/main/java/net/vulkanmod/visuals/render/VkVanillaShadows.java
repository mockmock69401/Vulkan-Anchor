package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.vulkanmod.visuals.VisualsLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * P7o design 4.3: VulkanMod's compiled vanilla terrain (every layer but the translucent one) into each sun shadow cascade (fork
 * design 4.3: the far one too), after the extensions' casters, inside the cascade's pass. VulkanMod's buffers are read again every frame and used only in its command buffer.
 */
public final class VkVanillaShadows {
    /**
     * VulkanMod's compressed terrain vertex (CustomVertexFormat.COMPRESSED_TERRAIN): short4 position, ubyte4 colour, ushort2 uv0,
     * short2 uv2, 20 bytes (gate G7o: a 16-byte stride read every vertex after the first from the wrong place).
     */
    static final int TERRAIN_STRIDE = 20;
    static final VkGraphicsProgram.VertexLayout TERRAIN = new VkGraphicsProgram.VertexLayout(TERRAIN_STRIDE, new int[]{0, 1, 2},
            new int[]{VK_FORMAT_R16G16B16A16_SINT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R16G16_UINT}, new int[]{0, 8, 12});
    static final int VIEW_PROJECTION = 0, AREA_OFFSET = 1, ATLAS = 0;
    /** VulkanMod's DrawBuffers.POS_OFFSET: the area origin it pushes is 4 blocks past the origin. */
    static final float POS_OFFSET = 4;

    private final VkGraphicsProgram program;
    private final LooseUniformWriter loose;
    private final long sampler;
    private boolean disabled;
    private int lastSections, lastSkipped;

    public VkVanillaShadows() {
        //Another terrain format (VulkanMod can switch it) would be read as garbage: no vanilla shadows then
        if (PipelineManager.TERRAIN_VERTEX_FORMAT != CustomVertexFormat.COMPRESSED_TERRAIN
                || PipelineManager.TERRAIN_VERTEX_FORMAT.getVertexSize() != TERRAIN_STRIDE) {
            throw new IllegalStateException("VulkanMod's terrain vertex format is not the 20-byte compressed one");
        }
        this.program = VkGraphicsProgram.of("vanillaShadow", VkShadowMaps.SHADOW_STATE, TERRAIN);
        this.loose = new LooseUniformWriter(this.program.meta());
        this.sampler = VkSamplers.nearestClamp();
    }

    /**
     * Inside a cascade's render pass, after the extensions' casters. Sections farther than casterRadius blocks from the camera
     * (horizontally) are left out.
     */
    public void draw(VkCommandBuffer cmd, long renderPass, ShadowMath.Cascade cascade, float casterRadius) {
        this.lastSections = 0;
        this.lastSkipped = 0;
        if (this.disabled || cascade == null) return;
        try {
            WorldRenderer world = WorldRenderer.getInstance();
            SectionGrid grid = world == null ? null : world.getSectionGrid();
            if (grid == null || grid.sections == null) return;
            List<VanillaShadowCasters.Area> areas = VanillaShadowCasters.collect(sections(grid.sections, cascade.cameraX(), cascade.cameraZ(), casterRadius), cascade.viewProjection(),
                    cascade.cameraX(), cascade.cameraY(), cascade.cameraZ(), casterRadius);
            if (areas.isEmpty()) return;
            long atlas = atlasView();
            if (atlas == 0) return;
            IndexBuffer quads = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
            long quadIndices = (quads.getBufferSize() - quads.getOffset()) / quads.indexType.size;//final review M2: past the bind offset
            this.loose.putMat4(VIEW_PROJECTION, cascade.viewProjection()).putVec4(AREA_OFFSET, 0, 0, 0, 0);
            this.program.bind(cmd, renderPass, new VkBindings().sampledView(ATLAS, atlas, this.sampler), this.loose);
            vkCmdBindIndexBuffer(cmd, quads.getId(), quads.getOffset(), quads.indexType.type);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (VanillaShadowCasters.Area a : areas) {
                    ChunkArea area = (ChunkArea) a.area();
                    DrawBuffers buffers = area.getDrawBuffers();
                    if (buffers == null || !buffers.isAllocated()) continue;
                    Vector3i origin = area.getPosition();
                    for (TerrainRenderType type : TerrainRenderType.VALUES) {
                        if (type == TerrainRenderType.TRANSLUCENT) continue;
                        AreaBuffer vertices = buffers.getAreaBuffer(type);
                        if (vertices == null || vertices.getId() == 0) continue;
                        //The area's offset and the layer's own alpha cutout (final review I1)
                        this.loose.putVec4(AREA_OFFSET, (float) (origin.x + POS_OFFSET - cascade.cameraX()), (float) (origin.y + POS_OFFSET - cascade.cameraY()),
                                (float) (origin.z + POS_OFFSET - cascade.cameraZ()), type.alphaCutout);
                        this.program.pushConstants(cmd, this.loose);
                        vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertices.getId()), stack.longs(0));
                        for (VanillaShadowCasters.Section s : a.sections()) {
                            DrawBuffers.DrawParameters p = ((SectionView) s).section().getDrawParameters(type);
                            int count = p.indexCount();
                            if (count <= 0) continue;
                            int first = p.firstIndex() == -1 ? 0 : p.firstIndex();
                            //Past the shared quad indices VulkanMod has made so far the GPU would read beyond the buffer
                            if ((long) first + count > quadIndices) {
                                this.lastSkipped++;
                                continue;
                            }
                            vkCmdDrawIndexed(cmd, count, 1, first, p.vertexOffset(), p.baseInstance());
                            this.lastSections++;
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            this.disabled = true;
            VisualsLog.warn("Visuals vanilla shadows disabled, the shadow maps keep the extensions' casters only: " + e);
        }
    }

    /** The block atlas's view (VulkanMod keeps sampled textures in the read layout), 0 when it has none yet. */
    static long atlasView() {
        int id = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
        GlTexture texture = GlTexture.getTexture(id);
        VulkanImage image = texture == null ? null : texture.getVulkanImage();
        return image == null ? 0 : image.getImageView();
    }

    /**
     * The grid's sections that can cast, as the collector sees them: compiled, not empty, within the caster radius of the camera
     * (checked before any object is made: the grid holds every section of the render distance). 2026-10-30 (audit): and over a chunk
     * VulkanMod draws (ChunkStatusMap.chunkRenderReady, as the edge cover checks): a section over a chunk not ready can hold an old
     * build, which VulkanMod does not draw and so should not cast either.
     */
    private static List<VanillaShadowCasters.Section> sections(RenderSection[] grid, double camX, double camZ, float casterRadius) {
        List<VanillaShadowCasters.Section> out = new ArrayList<>();
        float reach = casterRadius + 16;
        net.vulkanmod.render.chunk.ChunkStatusMap ready = net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE;
        for (RenderSection s : grid) {
            if (s == null || !s.isCompiled() || s.isCompletelyEmpty()) continue;
            if (Math.abs(s.xOffset() + 8 - camX) > reach || Math.abs(s.zOffset() + 8 - camZ) > reach) continue;
            if (ready != null && !ready.chunkRenderReady(s.xOffset() >> 4, s.zOffset() >> 4)) continue;
            out.add(new SectionView(s));
        }
        return out;
    }

    private record SectionView(RenderSection section) implements VanillaShadowCasters.Section {
        @Override
        public boolean drawable() {
            return this.section.isCompiled() && !this.section.isCompletelyEmpty() && this.section.getChunkArea() != null;
        }

        @Override
        public int blockX() {
            return this.section.xOffset();
        }

        @Override
        public int blockY() {
            return this.section.yOffset();
        }

        @Override
        public int blockZ() {
            return this.section.zOffset();
        }

        @Override
        public Object area() {
            return this.section.getChunkArea();
        }
    }

    public boolean disabled() {
        return this.disabled;
    }

    /** Sections drawn into the last cascade, and draws skipped for lack of shared quad indices. */
    public String stats() {
        return this.lastSections + " draws" + (this.lastSkipped > 0 ? ", " + this.lastSkipped + " skipped" : "");
    }

    public void free() {
        this.program.free();
        VkSamplers.free(this.sampler);
    }
}
