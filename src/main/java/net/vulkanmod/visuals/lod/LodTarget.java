package net.vulkanmod.visuals.lod;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.render.VkPostProcess;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkImage2D;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkViewport;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The offscreen target the LODs are drawn into: colour, depth and the LOD light (each fragment's light in the low byte, sky in the low
 * nibble and block in the high; its face in the high byte, see LodInputs), the size of the main target and with Reforged's depth
 * format. Drawn with a positive viewport, so the images have GL's
 * memory layout (row 0 = bottom of the picture), which the composite and the post-process read. After the opaque LODs
 * {@link #snapshotBeforeTranslucents} keeps a copy of the depth (the band depth, which the lighting's water detection reads) and,
 * for a consumer that blends the LOD without its translucents, of the colour; the translucents then draw on top in a load pass.
 * <p>
 * It holds only these: no ambient occlusion image, no bounding-box depth, no vanilla distance or chunk fade map (the LOD/vanilla
 * boundary is the LOD renderer's own) and no HiZ depth chain.
 */
public final class LodTarget {
    public static final int COLOUR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    /** The format the post-process expects of the LOD light view. */
    public static final int LIGHT_FORMAT = VkPostProcess.LOD_LIGHT_FORMAT;
    /** The band snapshot's readers: the lighting pass (compute) and a consumer that blends the opaque LOD (fragment). */
    static final int SNAPSHOT_READERS = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    /** What may have written the attachments before the snapshot copies read them. */
    static final int ATTACHMENT_WRITERS = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
            | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    private static final float CLEAR_DEPTH = 1.0f;//no reverse z

    private final int depthFormat;
    private final DeferredFree deferred;
    private final long clearPass, loadPass;
    private VkImage2D colour, depth, bandColour, bandDepth, light;
    private long framebuffer;
    private int width, height;
    private boolean open;

    public LodTarget(DeferredFree deferred) {
        this.depthFormat = VkContext.depthFormat();
        this.deferred = deferred;
        long clear = createRenderPass(this.depthFormat, true);
        long load;
        try {
            load = createRenderPass(this.depthFormat, false);
        } catch (RuntimeException | LinkageError e) {
            vkDestroyRenderPass(VkContext.device(), clear, null);
            throw e;
        }
        this.clearPass = clear;
        this.loadPass = load;
    }

    /** Recreates the images at a new size; the old ones are freed once the frames that may use them have completed. */
    public void ensureSize(int width, int height) {
        if (this.colour != null && this.width == width && this.height == height) return;
        if (this.colour != null) {
            VkImage2D oldColour = this.colour, oldDepth = this.depth, oldBandColour = this.bandColour, oldBandDepth = this.bandDepth, oldLight = this.light;
            long oldFramebuffer = this.framebuffer;
            this.deferred.retire(() -> destroy(oldFramebuffer, oldColour, oldDepth, oldBandColour, oldBandDepth, oldLight), VkFrames.serial());
        }
        this.colour = VkImage2D.create(COLOUR_FORMAT, width, height, 1,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        this.depth = VkImage2D.createDepth(this.depthFormat, width, height,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        //The snapshot's copies of the LOD before the translucents (snapshotBeforeTranslucents)
        this.bandColour = VkImage2D.create(COLOUR_FORMAT, width, height, 1, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        this.bandDepth = VkImage2D.createDepth(this.depthFormat, width, height, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        this.light = VkImage2D.create(LIGHT_FORMAT, width, height, 1, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        this.framebuffer = createFramebuffer(this.clearPass, this.colour.view(), this.depth.view(), this.light.view(), width, height);
        this.width = width;
        this.height = height;
        VisualsLog.info("Visuals LOD target " + width + "x" + height);
    }

    /** Outside a render pass, the start of the frame: clears the colour (0), the depth (1.0) and the light (0), with a positive viewport. */
    public void beginPass(VkCommandBuffer cmd) {
        this.beginPass(cmd, true);
    }

    /** {@code clear} false: the load pass over what is there, for the translucents after {@link #snapshotBeforeTranslucents}. */
    public void beginPass(VkCommandBuffer cmd, boolean clear) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clears = VkClearValue.calloc(3, stack);
            clears.get(0).color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            clears.get(1).depthStencil().set(CLEAR_DEPTH, 0);
            clears.get(2).color().uint32(0, 0).uint32(1, 0).uint32(2, 0).uint32(3, 0);
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(clear ? this.clearPass : this.loadPass)
                    .framebuffer(this.framebuffer).pClearValues(clears);
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(this.width, this.height);
            vkCmdBeginRenderPass(cmd, info, VK_SUBPASS_CONTENTS_INLINE);
            this.open = true;
            float[] v = viewport(this.width, this.height);
            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).x(v[0]).y(v[1]).width(v[2]).height(v[3]).minDepth(0).maxDepth(1));
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(this.width, this.height);
            vkCmdSetScissor(cmd, 0, scissor);
        }
    }

    public void endPass(VkCommandBuffer cmd) {
        vkCmdEndRenderPass(cmd);
        this.open = false;
        this.colour.assumeLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.light.assumeLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        this.depth.assumeLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    /** After a failure inside a pass: ends it, so Reforged's main pass is not begun inside ours. */
    public void endOpenPass(VkCommandBuffer cmd) {
        if (!this.open) return;
        this.endPass(cmd);
    }

    /**
     * Outside a render pass, after the opaque LODs and before the translucents: a copy of the LOD depth without the translucents
     * (the band depth), and with {@code band} the colour too (the band colour), for a consumer that blends the opaque LOD alone
     * because vanilla water is drawn over it afterwards. The attachments are back in their attachment layouts when this returns,
     * ready for {@code beginPass(cmd, false)}.
     */
    public void snapshotBeforeTranslucents(VkCommandBuffer cmd, boolean band) {
        this.depth.transition(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, ATTACHMENT_WRITERS, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        //Last frame's readers; the copy rewrites it whole
        this.bandDepth.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, SNAPSHOT_READERS,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        copy(cmd, this.depth, this.bandDepth, VK_IMAGE_ASPECT_DEPTH_BIT, this.width, this.height);
        this.bandDepth.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                SNAPSHOT_READERS, VK_ACCESS_SHADER_READ_BIT);
        this.depth.transition(cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        if (!band) return;
        this.colour.transition(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        //Last frame's reader; the copy rewrites it whole
        this.bandColour.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        copy(cmd, this.colour, this.bandColour, VK_IMAGE_ASPECT_COLOR_BIT, this.width, this.height);
        this.bandColour.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.colour.transition(cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }

    private static void copy(VkCommandBuffer cmd, VkImage2D src, VkImage2D dst, int aspect, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.get(0).srcSubresource().set(aspect, 0, 0, 1);
            region.get(0).dstSubresource().set(aspect, 0, 0, 1);
            region.get(0).extent().set(width, height, 1);
            vkCmdCopyImage(cmd, src.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    /** Outside a render pass, after the last LOD draw: what the composite (fragment) and the post-process (compute) sample. */
    public void toShaderRead(VkCommandBuffer cmd) {
        this.colour.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.depth.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, SNAPSHOT_READERS, VK_ACCESS_SHADER_READ_BIT);
        //The scene copy and the reflection read each LOD pixel's light
        this.light.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, SNAPSHOT_READERS, VK_ACCESS_SHADER_READ_BIT);
    }

    public long clearPass() { return this.clearPass; }
    public long loadPass() { return this.loadPass; }
    public VkImage2D color() { return this.colour; }
    public VkImage2D depth() { return this.depth; }
    public VkImage2D bandColour() { return this.bandColour; }
    public VkImage2D bandDepth() { return this.bandDepth; }
    public VkImage2D light() { return this.light; }
    public int width() { return this.width; }
    public int height() { return this.height; }

    /** Call when a frame starts recording (VkFrames listener). */
    public void tick() {
        if (this.colour == null) return;
        this.colour.tick();
        this.depth.tick();
        this.bandColour.tick();
        this.bandDepth.tick();
        this.light.tick();
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        if (this.colour != null) destroy(this.framebuffer, this.colour, this.depth, this.bandColour, this.bandDepth, this.light);
        this.colour = null;
        vkDestroyRenderPass(VkContext.device(), this.clearPass, null);
        vkDestroyRenderPass(VkContext.device(), this.loadPass, null);
    }

    /** GL's memory layout: no y flip (Reforged flips its own viewport). */
    static float[] viewport(int width, int height) {
        return new float[]{0, 0, width, height};
    }

    static int loadOp(boolean clear) {
        return clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    }

    static int initialLayout(boolean clear, int attachmentLayout) {
        return clear ? VK_IMAGE_LAYOUT_UNDEFINED : attachmentLayout;
    }

    private static void destroy(long framebuffer, VkImage2D colour, VkImage2D depth, VkImage2D bandColour, VkImage2D bandDepth, VkImage2D light) {
        vkDestroyFramebuffer(VkContext.device(), framebuffer, null);
        colour.free();
        depth.free();
        bandColour.free();
        bandDepth.free();
        light.free();
    }

    private static long createRenderPass(int depthFormat, boolean clear) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(3, stack);
            attachments.get(0).format(COLOUR_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(loadOp(clear)).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(initialLayout(clear, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachments.get(1).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(loadOp(clear)).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(initialLayout(clear, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL))
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            attachments.get(2).format(LIGHT_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(loadOp(clear)).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(initialLayout(clear, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference.Buffer colourRef = VkAttachmentReference.calloc(2, stack);
            colourRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colourRef.get(1).attachment(2).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(2).pColorAttachments(colourRef).pDepthStencilAttachment(depthRef);
            //Earlier passes of this frame (attachment writes) and the previous frame's composite (fragment reads) and post-process
            // (compute reads of the light and depth) come first
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(ATTACHMENT_WRITERS | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(ATTACHMENT_WRITERS)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachments).pSubpasses(subpass).pDependencies(dependency);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateRenderPass(VkContext.device(), info, null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateRenderPass failed: " + result);
            return p.get(0);
        }
    }

    private static long createFramebuffer(long renderPass, long colourView, long depthView, long lightView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                    .pAttachments(stack.longs(colourView, depthView, lightView)).width(width).height(height).layers(1);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateFramebuffer(VkContext.device(), info, null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateFramebuffer failed: " + result);
            return p.get(0);
        }
    }
}
