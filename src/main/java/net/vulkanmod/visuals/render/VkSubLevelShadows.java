package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkSamplers;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;

/**
 * 2026-11-07: the draws of Sable's sub-levels this frame kept (SubLevelShadowCasters) again, into a shadow map's pass. The cascades are
 * refreshed every few frames and mixed, which would leave a moving structure's shadow behind, and they draw before Sable's cutout
 * layer; this runs in the post-process into a map cleared every frame (VkEntityShadows), when all the layers were drawn.
 */
final class VkSubLevelShadows {
    /** Sable's section buffers are in the block format: position at 0, uv at 16, 32 bytes a vertex. */
    static final VkGraphicsProgram.VertexLayout BLOCK = new VkGraphicsProgram.VertexLayout(32, new int[]{0, 1},
            new int[]{VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32_SFLOAT}, new int[]{0, 16});
    static final int LIGHT_VIEW_PROJECTION = 0, CHUNK_OFFSET = 1, ATLAS = 0;

    private final VkGraphicsProgram program;
    private final LooseUniformWriter loose;
    private final long sampler;
    private final Matrix4f toWorld = new Matrix4f(), toMap = new Matrix4f();
    private boolean disabled;

    private VkSubLevelShadows() {
        this.program = VkGraphicsProgram.of("subLevelShadow", VkShadowMaps.SHADOW_STATE, BLOCK);
        this.loose = new LooseUniformWriter(this.program.meta());
        this.sampler = VkSamplers.nearestClamp();
    }

    /** Built with its map; null (logged) when it cannot be: the map then holds the entities only. */
    static VkSubLevelShadows create() {
        try {
            return new VkSubLevelShadows();
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals sub-level shadows not created: " + e);
            return null;
        }
    }

    boolean disabled() {
        return this.disabled;
    }

    /** Inside the map's pass: the kept draws that can reach it. invModelView: this frame's view space to camera-relative world. */
    void draw(VkCommandBuffer cmd, long renderPass, ShadowMath.Cascade cascade, Matrix4fc invModelView) {
        if (this.disabled) return;
        try {
            long atlas = VkVanillaShadows.atlasView();
            if (atlas == 0) return;
            Matrix4f viewProjection = cascade.viewProjection();
            this.loose.putMat4(LIGHT_VIEW_PROJECTION, viewProjection).putVec4(CHUNK_OFFSET, 0, 0, 0, 0);
            this.program.bind(cmd, renderPass, new VkBindings().sampledView(ATLAS, atlas, this.sampler), this.loose);
            SubLevelShadowCasters.drawAll(c -> {
                SubLevelShadowMath.toCameraWorld(invModelView, c.modelView, this.toWorld);
                if (!SubLevelShadowMath.reaches(viewProjection, this.toWorld, c.ox, c.oy, c.oz)) return false;
                this.loose.putMat4(LIGHT_VIEW_PROJECTION, viewProjection.mul(this.toWorld, this.toMap)).putVec4(CHUNK_OFFSET, c.ox, c.oy, c.oz, c.cutout);
                this.program.pushConstants(cmd, this.loose);
                return c.drawable.recordShadowDraw(cmd);
            });
        } catch (RuntimeException | LinkageError e) {
            this.disabled = true;
            VisualsLog.warn("Visuals sub-level shadows disabled, the map keeps the entities only: " + e);
        }
    }

    void free() {
        this.program.free();
        VkSamplers.free(this.sampler);
    }
}
