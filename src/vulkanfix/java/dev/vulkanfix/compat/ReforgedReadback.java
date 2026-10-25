package dev.vulkanfix.compat;

import dev.vulkanfix.mixin.reforged.GlBufferAccessor;
import dev.vulkanfix.mixin.reforged.GlFramebufferAccessor;
import net.vulkanmod.gl.GlBuffer;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * glGetTexImage into a pixel pack buffer, done the way GL orders it: the image is copied to a staging buffer inside
 * the frame being recorded (after the draws issued so far), and the pack buffer is filled, converted to the requested
 * format/type, when it is next read (glMapBuffer / glMapBufferRange / glGetBufferSubData). Render thread only.
 */
public final class ReforgedReadback {
    private static final int GL_PIXEL_PACK_BUFFER = 35051;
    private static final int GL_FRAMEBUFFER = 36160;
    private static final int LAYOUT_UNDEFINED = 0, LAYOUT_SHADER_READ_ONLY = 5, LAYOUT_TRANSFER_SRC = 6;
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37, VK_FORMAT_R8G8B8A8_SRGB = 43;
    private static final int VK_FORMAT_B8G8R8A8_UNORM = 44, VK_FORMAT_B8G8R8A8_SRGB = 50;

    private record Pending(long buffer, long allocation, long size, long serial, long offset,
                           boolean sourceIsBgra, int format, int type) {
    }

    private static final Map<GlBuffer, Pending> PENDING = new IdentityHashMap<>();
    private static final List<Pending> RETIRED = new ArrayList<>();
    private static long frameSerial;

    private ReforgedReadback() {
    }

    public static void onBeginFrame() {
        frameSerial++;
        for (Iterator<Pending> it = RETIRED.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (ReadbackSchedule.state(p.serial, frameSerial, Renderer.getFramesNum()) == ReadbackSchedule.State.COMPLETE) {
                MemoryManager.freeBuffer(p.buffer, p.allocation);
                it.remove();
            }
        }
    }

    /** @return true if the readback was taken over (Reforged's own synchronous download must not run). */
    public static boolean requestIntoPackBuffer(int format, int type, long offset) {
        GlBuffer pack = GlBuffer.getPixelPackBufferBound();
        GlTexture texture = GlTexture.getBoundTexture();
        VulkanImage image = texture == null ? null : texture.getVulkanImage();
        if (pack == null || image == null || !Renderer.isRecording() || !ReadbackFormat.isSupported(format, type)) {
            return false;
        }
        boolean bgra = image.format == VK_FORMAT_B8G8R8A8_UNORM || image.format == VK_FORMAT_B8G8R8A8_SRGB;
        if (image.formatSize != 4 || !(bgra || image.format == VK_FORMAT_R8G8B8A8_UNORM || image.format == VK_FORMAT_R8G8B8A8_SRGB)) {
            return false;
        }

        long[] copy = recordCopy(image);
        Pending recorded = new Pending(copy[0], copy[1], copy[2], frameSerial, offset, bgra, format, type);

        Pending replaced = PENDING.put(pack, recorded);
        if (replaced != null) {
            RETIRED.add(replaced);
        }
        return true;
    }

    /**
     * Records a copy of {@code image} into a new host-visible buffer inside the frame being built, ending the active
     * render pass for it and resuming that pass afterwards. @return {buffer, allocation, size}
     */
    private static long[] recordCopy(VulkanImage image) {
        Renderer renderer = Renderer.getInstance();
        GlFramebuffer boundGl = GlFramebufferAccessor.vulkanfix$getBoundFramebuffer();
        RenderPass activePass = renderer.getBoundRenderPass();
        renderer.endRenderPass();

        long size = (long) image.width * image.height * image.formatSize;
        long[] copy;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = Renderer.getCommandBuffer();
            int previousLayout = image.getCurrentLayout();
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(size, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, pBuffer, pAllocation);
            image.transitionImageLayout(stack, cmd, LAYOUT_TRANSFER_SRC);
            ImageUtil.copyImageToBuffer(cmd, pBuffer.get(0), image.getId(), 0, image.width, image.height, 0, 0, 0, 0, 0);
            image.transitionImageLayout(stack, cmd, previousLayout == LAYOUT_UNDEFINED ? LAYOUT_SHADER_READ_ONLY : previousLayout);
            copy = new long[]{pBuffer.get(0), pAllocation.get(0), size};
        }

        if (activePass != null) {
            if (boundGl != null) {
                int[] viewport = ViewportTracker.last();
                GlFramebuffer.beginRendering(boundGl);
                if (viewport != null) {
                    ReforgedViewport.apply(viewport[0], viewport[1], viewport[2], viewport[3]);
                }
            } else {
                GlFramebuffer.bindFramebuffer(GL_FRAMEBUFFER, 0);
            }
        }
        return copy;
    }

    /** Fills the pack buffer bound to {@code target} if a deferred readback into it is waiting. */
    public static void resolve(int target) {
        if (target != GL_PIXEL_PACK_BUFFER) {
            return;
        }
        GlBuffer pack = GlBuffer.getPixelPackBufferBound();
        Pending p = pack == null ? null : PENDING.get(pack);
        if (p == null) {
            return;
        }
        ReadbackSchedule.State state = ReadbackSchedule.state(p.serial, frameSerial, Renderer.getFramesNum());
        if (state == ReadbackSchedule.State.NOT_SUBMITTED) {
            return;
        }
        if (state == ReadbackSchedule.State.SUBMITTED) {
            net.vulkanmod.vulkan.queue.QueueLock.queueWaitIdle(DeviceManager.getGraphicsQueue().queue());
        }
        PENDING.remove(pack);

        ByteBuffer data = ((GlBufferAccessor) pack).vulkanfix$getData();
        if (data != null && p.offset >= 0 && p.offset + p.size <= data.capacity()) {
            ByteBuffer dst = MemoryUtil.memByteBuffer(MemoryUtil.memAddress0(data) + p.offset, (int) p.size);
            MemoryManager.MapAndCopy(p.allocation, mapped ->
                    ReadbackFormat.convert(MemoryUtil.memByteBuffer(mapped.get(0), (int) p.size), p.sourceIsBgra, dst, p.format, p.type));
        }
        MemoryManager.freeBuffer(p.buffer, p.allocation);
    }
}
