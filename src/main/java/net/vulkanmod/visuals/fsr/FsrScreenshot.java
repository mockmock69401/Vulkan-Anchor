package net.vulkanmod.visuals.fsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.File;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 2026-11-27 (stability test, Nightfall: every F2 with FSR's swapchain live gave UNASSIGNED-CoreValidation-DrawState-InvalidImageLayout).
 * F2 runs Screenshot.grab between frames, when the swapchain image is FSR's: handed over in SHADER_READ_ONLY_OPTIMAL (DefaultMainPass.end),
 * then copied and read by FSR's present and generation on FSR's own queues, which leave it in TRANSFER_SRC_OPTIMAL or in
 * SHADER_READ_ONLY_OPTIMAL as their last use was (both were seen, even after waiting for FSR's presents). Its layout between frames is
 * not known, so the download from the tracked hand-over layout was wrong, and it raced FSR's reads. While FSR is live such a grab is
 * kept for the next frame instead ({@link #defer}): that frame copies the swapchain image to a buffer while the image is still the
 * game's, before the hand-over ({@link #copyBeforeHandOver}), and right after its endFrame the grab runs with that copy as its picture
 * ({@link #afterEndFrame}, {@link #read}). The picture is the next frame, as with frame generation's paced screenshots
 * (FrameGen.deferScreenshot). Render thread.
 */
public final class FsrScreenshot {
    private record Grab(File dir, String name, RenderTarget target, Consumer<Component> messages) {}

    private static final List<Grab> GRABS = new ArrayList<>();
    private static boolean grabbing;
    private static long buffer, allocation;
    private static int width, height;
    private static boolean bgra;

    private FsrScreenshot() {}

    /** ScreenshotFsrMixin, at Screenshot.grab: true when the grab was kept for the next frame (FSR live, between frames, the swapchain read). */
    public static boolean defer(File dir, String name, RenderTarget target, Consumer<Component> messages) {
        if (grabbing || Renderer.isRecording() || !FsrSwapchain.live() || !readsSwapchain(target)) return false;
        GRABS.add(new Grab(dir, name, target, messages));
        return true;
    }

    private static boolean readsSwapchain(RenderTarget target) {
        GlTexture texture = GlTexture.getTexture(target.getColorTextureId());
        VulkanImage image = texture == null ? null : texture.getVulkanImage();
        return image != null && Vulkan.getSwapChain().getImages().contains(image);
    }

    /**
     * DefaultMainPass.end while FSR is live, before the hand-over: with a grab waiting, the finished swapchain image is copied to a host
     * buffer and left in SHADER_READ_ONLY_OPTIMAL with the hand-over's stages (FSR's copy and generation read it after this submit).
     */
    public static void copyBeforeHandOver(MemoryStack stack, VkCommandBuffer cmd, VulkanImage image) {
        if (GRABS.isEmpty() || buffer != 0L) return;
        long size = (long) image.width * image.height * image.formatSize;
        LongBuffer pBuffer = stack.mallocLong(1);
        PointerBuffer pAllocation = stack.pointers(0L);
        MemoryManager.getInstance().createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, pBuffer, pAllocation);
        if (image.getCurrentLayout() == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            VulkanImage.transitionLayout(stack, cmd, image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT);
        } else {
            image.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        }
        ImageUtil.copyImageToBuffer(cmd, pBuffer.get(0), image.getId(), 0, image.width, image.height, 0, 0, 0, 0, 0);
        VkBufferMemoryBarrier.Buffer toHost = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(pBuffer.get(0)).offset(0).size(VK_WHOLE_SIZE);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, toHost, null);
        VulkanImage.transitionLayout(stack, cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
        buffer = pBuffer.get(0);
        allocation = pAllocation.get(0);
        width = image.width;
        height = image.height;
        bgra = Vulkan.getSwapChain().isBGRAformat;
    }

    /**
     * MinecraftMixin, right after Renderer.endFrame: once a frame copied the image, the game's own queues are waited for and the kept
     * grabs run with the copy. A grab kept while FSR then turned off runs as usual.
     */
    public static void afterEndFrame() {
        if (GRABS.isEmpty()) return;
        if (buffer == 0L && FsrSwapchain.live()) return;
        if (buffer != 0L) Vulkan.waitOwnQueuesIdle();
        Grab[] waiting = GRABS.toArray(new Grab[0]);
        GRABS.clear();
        grabbing = true;
        try {
            for (Grab g : waiting) Screenshot.grab(g.dir(), g.name(), g.target(), g.messages());
        } finally {
            grabbing = false;
            if (buffer != 0L) {
                MemoryManager.freeBuffer(buffer, allocation);
                buffer = 0L;
                allocation = 0L;
            }
        }
    }

    /** ScreenshotRecorderM: while a kept grab runs, the copied frame (opaque RGBA, rows top-down) as its picture; null otherwise. */
    public static NativeImage read(int w, int h) {
        if (!grabbing || buffer == 0L) return null;
        NativeImage out = new NativeImage(w, h, false);
        int cw = Math.min(w, width), ch = Math.min(h, height);
        int rowPixels = width;
        boolean swap = bgra;
        MemoryManager.MapAndCopy(allocation, data -> {
            long src = data.get(0);
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    int v = MemoryUtil.memGetInt(src + ((long) y * rowPixels + x) * 4L);
                    if (swap) v = ColorUtil.BGRAtoRGBA(v);
                    out.setPixelRGBA(x, y, v | 0xFF000000);
                }
            }
        });
        return out;
    }
}
