package net.vulkanmod.visuals.lod;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkFeatures;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Inside Reforged's resumed main pass: the LOD target's colour is blended over the frame where it has a surface, and its depth is
 * written in the main projection (lod_composite.frag). The depth bounds test [1, 1] lets LOD land only where the main depth is still
 * the clear value, so no LOD is drawn over a pixel vanilla drew; without the feature a LOD pixel behind vanilla fails the depth test and
 * a nearer one would not. There is no vanilla distance band or chunk fade here.
 */
public final class LodComposite {
    static final String PROGRAM = "lodComposite";
    // lod_composite.frag bindings; the loose uniform block is set 1 binding 0
    static final int DEPTH_TEX = 0, COLOUR_TEX = 1, LOOSE_UNIFORMS = 0;
    /** Depth test only: the fallback without the depthBounds device feature. */
    static final VkGraphicsProgram.State STATE = new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkGraphicsProgram.RGBA, true,
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);
    /** The composite runs after vanilla's terrain: the depth bounds [1, 1] keep LOD out of every pixel vanilla has drawn on. */
    static final VkGraphicsProgram.State BOUNDED_STATE = STATE.withDepthBoundsAtFar();

    /** Pure seam: the state to build the pipeline with, given whether the device has the depthBounds feature. */
    static VkGraphicsProgram.State stateFor(boolean depthBoundsFeature) {
        return depthBoundsFeature ? BOUNDED_STATE : STATE;
    }

    private final VkGraphicsProgram program;
    private final LooseUniformWriter uniforms;
    private final VkGpuBuffer uniformBuffer;
    private final long sampler;

    public LodComposite() {
        boolean bounds = VkFeatures.depthBounds();
        if (!bounds) VisualsLog.info("Visuals LOD composite: depthBounds device feature missing, vanilla terrain is protected by the depth test only");
        this.program = VkGraphicsProgram.of(PROGRAM, stateFor(bounds));
        this.uniforms = this.program.looseUniforms();
        VkGpuBuffer buffer = null;
        long sampler;
        try {
            buffer = this.uniforms.inUniformBuffer() ? VkGpuBuffer.device(this.uniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) : null;
            sampler = VkSamplers.nearestClamp();
        } catch (RuntimeException | LinkageError e) {
            //The owner frees only what its fields hold; nothing here is assigned yet
            this.program.free();
            if (buffer != null) buffer.free();
            throw e;
        }
        this.uniformBuffer = buffer;
        this.sampler = sampler;
    }

    /** CPU side, before the first upload commit. */
    public void prepare(VkUploadStream upload, LodCompositeUniforms.Values values) {
        values.write(this.uniforms);
        if (this.uniformBuffer != null) {
            upload.upload(this.uniformBuffer, 0, this.uniforms.buffer());
        }
    }

    /** Inside the resumed main pass, after LodTarget.toShaderRead; uses Reforged's viewport and scissor. */
    public void draw(VkCommandBuffer cmd, LodTarget target) {
        VkBindings bindings = new VkBindings().sampled(DEPTH_TEX, target.depth(), this.sampler).sampled(COLOUR_TEX, target.color(), this.sampler);
        if (this.uniformBuffer != null) bindings.uniform(LOOSE_UNIFORMS, this.uniformBuffer);
        this.program.bind(cmd, VkContext.boundRenderPassHandle(), VkContext.boundRenderPassKey(), bindings, this.uniforms);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        this.program.free();
        if (this.uniformBuffer != null) this.uniformBuffer.free();
        VkSamplers.free(this.sampler);
    }
}
