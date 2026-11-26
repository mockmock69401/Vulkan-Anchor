package net.vulkanmod.vulkan.texture;

import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 2026-11-26 (Sunlit, validation layer: VUID-VkDescriptorImageInfo-imageLayout-00344 and a submit finding an image in UNDEFINED):
 * the image a descriptor samples, in SHADER_READ_ONLY_OPTIMAL by the time the draw being recorded runs (Pipeline's descriptor binds).
 *
 * VulkanImage.readOnlyLayout() records its barrier in a command buffer that runs before the frame's (Synchronization.addFrameUpload),
 * which is right for an image uploaded on the side, but not for one a render pass drew into earlier in the same frame: its barrier then
 * runs before the drawing and the layouts the GPU meets differ from the ones recorded. Such an image is moved in the frame's command
 * buffer instead, ending the render pass being recorded around the barrier and carrying it on after (Renderer.suspendRenderPass).
 *
 * An image that is an attachment of the render pass being recorded (watut's screen blur samples the target it draws into, a GL feedback
 * loop) cannot be read in that pass at all: its contents so far are copied into a second image and the draw samples the copy, which is
 * what such a draw reads under GL in practice (the target as it was before the draw).
 */
public final class AttachmentSampling {
    /** The copies of a sampled attachment, one for colour and one for depth (a draw can sample both of the pass it draws in). */
    private static VulkanImage colourCopy, depthCopy;

    private AttachmentSampling() {
    }

    /** True when sampling {@code image} now would read an attachment of the render pass being recorded (needs a copy: {@link #prepare}). */
    public static boolean isBoundAttachment(VulkanImage image) {
        if (!Renderer.isRecording())
            return false;

        Renderer renderer = Renderer.getInstance();
        Framebuffer framebuffer = renderer.getBoundFramebuffer();
        RenderPass pass = renderer.getBoundRenderPass();
        if (framebuffer == null || pass == null)
            return false;

        Framebuffer built = pass.getFramebuffer();
        return image == framebuffer.getColorAttachment() || image == framebuffer.getDepthAttachment()
                || (built != null && (image == built.getColorAttachment() || image == built.getDepthAttachment()));
    }

    /** True when a draw sampling {@code image} now samples a fresh copy of it instead (an attachment of the pass, copyable). */
    public static boolean needsCopy(VulkanImage image) {
        return (image.getUsage() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0 && isBoundAttachment(image);
    }

    /**
     * The image to sample in place of {@code image} (itself, or the copy of an attachment of the pass being recorded), in
     * SHADER_READ_ONLY_OPTIMAL for the draw being recorded.
     */
    public static VulkanImage prepare(VulkanImage image) {
        int layout = image.getCurrentLayout();
        if (layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            return image;

        if (Renderer.isRecording() && RenderSystem.isOnRenderThread()) {
            Renderer renderer = Renderer.getInstance();

            if (isBoundAttachment(image)) {
                VulkanImage copy = copyOfAttachment(renderer, image);
                if (copy != null)
                    return copy;
            } else if (layout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL || layout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                //drawn by a render pass, maybe earlier in this frame: the barrier goes after it, in the frame's command buffer
                if (renderer.getBoundRenderPass() == null) {
                    readOnlyInFrame(image);
                    return image;
                }
                if (renderer.suspendRenderPass()) {
                    readOnlyInFrame(image);
                    renderer.resumeRenderPass();
                    return image;
                }
            }
        }

        image.readOnlyLayout();
        return image;
    }

    private static void readOnlyInFrame(VulkanImage image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.readOnlyLayout(stack, Renderer.getCommandBuffer());
        }
    }

    /** The attachment's contents so far, copied into this aspect's copy image; null (nothing recorded) when it cannot be copied. */
    private static VulkanImage copyOfAttachment(Renderer renderer, VulkanImage image) {
        if ((image.getUsage() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0)
            return null;

        boolean depth = (image.aspect & VK_IMAGE_ASPECT_COLOR_BIT) == 0;
        VulkanImage copy = depth ? depthCopy : colourCopy;
        if (copy == null || copy.format != image.format || copy.width != image.width || copy.height != image.height) {
            if (copy != null)
                copy.free();
            copy = VulkanImage.builder(image.width, image.height)
                    .setFormat(image.format)
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .createVulkanImage();
            if (depth)
                depthCopy = copy;
            else
                colourCopy = copy;
        }

        if (!renderer.suspendRenderPass())
            return null;

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            //the pass's writes, whatever its final layout and dependencies were, before the copy reads them
            VulkanImage.transitionLayout(stack, commandBuffer, image, image.getCurrentLayout(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            //after the draws that read the copy before (fragment or earlier stages)
            copy.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(image.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstSubresource().aspectMask(copy.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.extent().set(image.width, image.height, 1);
            vkCmdCopyImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    copy.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            copy.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        //moves the attachment back to its attachment layout and loads it
        renderer.resumeRenderPass();
        return copy;
    }
}
