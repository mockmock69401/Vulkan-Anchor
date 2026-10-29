package net.vulkanmod.visuals.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A device-local 2D image with all mip levels in one view; its layout is tracked on the CPU (whole image). Depth
 * images keep a second, depth-only view for sampling: a view of a depth/stencil format with both aspects can be an
 * attachment but cannot be sampled.
 */
public final class VkImage2D {
    private final long image;
    private final long allocation;
    private boolean freed;
    private final long view;
    private final long sampledView;
    private final int aspect;
    public final int width, height, levels;
    private final LayoutTracker layout = new LayoutTracker(VK_IMAGE_LAYOUT_UNDEFINED);

    private VkImage2D(long image, long allocation, long view, long sampledView, int aspect, int width, int height, int levels) {
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.sampledView = sampledView;
        this.aspect = aspect;
        this.width = width;
        this.height = height;
        this.levels = levels;
    }

    public static VkImage2D create(int format, int width, int height, int levels, int usage) {
        return allocate(format, width, height, levels, usage, VK_IMAGE_ASPECT_COLOR_BIT);
    }

    /** A depth (or depth/stencil) attachment that can also be sampled through sampledView(). */
    public static VkImage2D createDepth(int format, int width, int height, int usage) {
        int aspect = aspectsOf(format);
        if ((aspect & VK_IMAGE_ASPECT_DEPTH_BIT) == 0) throw new IllegalArgumentException("not a depth format: " + format);
        return allocate(format, width, height, 1, usage, aspect);
    }

    private static VkImage2D allocate(int format, int width, int height, int levels, int usage, int aspect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(levels).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(VkContext.allocator(), info, alloc, pImage, pAlloc, null), "vmaCreateImage " + width + "x" + height);
            long image = pImage.get(0);
            //2026-10-30 (audit): the image and its first view no longer leak when a view cannot be made
            long view = 0;
            try {
                view = createView(image, format, aspect, levels);
                long sampledView = (aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0 ? createView(image, format, VK_IMAGE_ASPECT_DEPTH_BIT, levels) : view;
                return new VkImage2D(image, pAlloc.get(0), view, sampledView, aspect, width, height, levels);
            } catch (RuntimeException e) {
                if (view != 0) vkDestroyImageView(VkContext.device(), view, null);
                Vma.vmaDestroyImage(VkContext.allocator(), image, pAlloc.get(0));
                throw e;
            }
        }
    }

    /** A 2D view of the first levels mip levels; also used for Reforged's images (VkContext). */
    public static long createView(long image, int format, int aspect, int levels) {
        return createView(image, format, aspect, 0, levels);
    }

    /** A 2D view of levels mip levels starting at baseLevel (VkHiZ renders and samples one level at a time). */
    public static long createView(long image, int format, int aspect, int baseLevel, int levels) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().set(aspect, baseLevel, levels, 0, 1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(VkContext.device(), viewInfo, null, pView), "vkCreateImageView");
            return pView.get(0);
        }
    }

    public static void destroyView(long view) {
        vkDestroyImageView(VkContext.device(), view, null);
    }

    /** The aspects of format that its attachment view and its barriers cover. */
    public static int aspectsOf(int format) {
        return switch (format) {
            case VK_FORMAT_D16_UNORM, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT -> VK_IMAGE_ASPECT_DEPTH_BIT;
            case VK_FORMAT_D16_UNORM_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT -> VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            case VK_FORMAT_S8_UINT -> VK_IMAGE_ASPECT_STENCIL_BIT;
            default -> VK_IMAGE_ASPECT_COLOR_BIT;
        };
    }

    public long handle() { return this.image; }
    public long view() { return this.view; }
    public long sampledView() { return this.sampledView; }
    public int layout() { return this.layout.current(); }

    /** Moves every mip level from the tracked layout to newLayout. */
    public void transition(VkCommandBuffer cmd, int newLayout, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        this.barrier(cmd, this.layout.current(), newLayout, srcStages, srcAccess, dstStages, dstAccess);
    }

    /** Discards the contents (old layout UNDEFINED) and moves every mip level to newLayout, for images rewritten whole. */
    public void transitionFromUndefined(VkCommandBuffer cmd, int newLayout, int srcStages, int dstStages, int dstAccess) {
        this.barrier(cmd, VK_IMAGE_LAYOUT_UNDEFINED, newLayout, srcStages, 0, dstStages, dstAccess);
    }

    /** A render pass left the image in newLayout (its finalLayout): track it without recording a barrier. */
    public void assumeLayout(int newLayout) {
        this.layout.recorded(VkFrames.serial(), newLayout);
    }

    /** Call when a frame starts recording: transitions of a dropped frame are undone. */
    public void tick() {
        this.layout.tick(VkFrames.completedSerial(), VkFrames::isDropped);
    }

    private void barrier(VkCommandBuffer cmd, int oldLayout, int newLayout, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(this.image);
            b.subresourceRange().set(this.aspect, 0, this.levels, 0, 1);
            vkCmdPipelineBarrier(cmd, srcStages, dstStages, 0, null, null, b);
        }
        this.layout.recorded(VkFrames.serial(), newLayout);
    }

    /** Only once no submitted frame can still use it. 2026-10-30 (audit): a second call does nothing. */
    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.sampledView != this.view) vkDestroyImageView(VkContext.device(), this.sampledView, null);
        vkDestroyImageView(VkContext.device(), this.view, null);
        Vma.vmaDestroyImage(VkContext.allocator(), this.image, this.allocation);
    }

    /** 2026-10-30 (audit): whether free ran (VkUploadStream drops copies into a freed image). */
    public boolean freed() {
        return this.freed;
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: " + result);
    }
}
