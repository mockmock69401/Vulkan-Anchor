package net.vulkanmod.vulkan.pass;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);


    default void onResize() {}


    default void cleanUp() {}

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    default void bindAsTexture() {}

    default void resolveRenderScaleForGui() {}


    default RenderPass resumeRenderPass(RenderPass interrupted) {
        return interrupted;
    }

    /** True while {@code pass} is the frame generation UI layer pass; its blending is remapped. */
    default boolean isUiLayerPass(RenderPass pass) {
        return false;
    }

    /**
     * True for a frame recorded for the frame generation pacer (FG design 3.1): the world goes to an offscreen framebuffer and the
     * swapchain is the pacer's. Latched when the frame begins.
     */
    default boolean paced() {
        return false;
    }

    /** Paced frames: switches the main target to the UI layer of capture slot slot, cleared to transparent black. */
    default void beginUiLayer(int slot) {}

    /** Frame generation frees or replaces its UI layer framebuffers: drop every render pass and GL id made for them. */
    default void releaseUiLayer() {}

    /** The swapchain-format pass the pacer composes into (final layout COLOR_ATTACHMENT_OPTIMAL), or null. */
    default RenderPass presentRenderPass() {
        return null;
    }

    default int getColorAttachmentGlId() {
        return -1;
    }

    default int getDepthAttachmentGlId() {
        return -1;
    }

    default VulkanImage getColorAttachment() {
        return null;
    }

    default VulkanImage getDepthAttachment() {
        return null;
    }
}
