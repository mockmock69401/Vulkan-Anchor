package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Frame generation's screenshot burst (Task 10, the pilot's fgburst): the pacer composes each of the next N presents a second time into
 * an offscreen image of the swapchain's colour format, copies it to a host buffer, and a worker thread writes
 * {@code <gameDir>/<name>_<i>_<real|gen>.png} (the game directory itself: the runner collects files that start with the run label). The pacer thread never writes a file and never builds anything: this object,
 * its image, framebuffer and buffers are made on the render thread while the pacer is stopped (FrameGen), and freed there.
 *
 * The framebuffer is made for the swapchain's present render pass (the burst image and the swapchain's depth), so the compose pipeline
 * already prepared for that pass serves the burst unchanged. Each capture ends with its image in TRANSFER_SRC_OPTIMAL and the next one
 * discards it. A capture waits for a free host buffer; with none free (the worker is behind) the present is not captured, counted
 * and logged at the end (that present does not count toward N). The worker waits for the pacer timeline value of the submit that holds a capture before it reads the buffer.
 */
public final class FrameBurst {
    /** Host readback buffers (each width x height x 4 bytes): the worker frees one per file written. */
    static final int BUFFERS = 8;

    private record Item(int buffer, int index, boolean real, long value) {
    }

    private static final Item END = new Item(-1, -1, false, 0);

    private final String name;
    private final Path dir;
    private final int width, height;
    private final boolean bgra;
    private final VkImage2D image;
    private final long framebuffer;
    private final VkGpuBuffer[] buffers;
    private final BlockingQueue<Integer> free;
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final long timeline;
    private final Thread worker;

    /** Pacer thread only. */
    private int remaining, index, skipped;
    private boolean ended;
    private volatile boolean aborted, done;
    private volatile int written;

    /** Render thread, pacer stopped. Throws when the image, the buffers or the framebuffer cannot be made (all freed then). */
    FrameBurst(String name, int count, Path dir, SwapChain sc, long presentRenderPass, long pacerTimeline, boolean pairs) {
        this.name = name;
        this.dir = dir;
        this.width = sc.getWidth();
        this.height = sc.getHeight();
        this.bgra = sc.isBGRAformat;
        this.timeline = pacerTimeline;
        this.remaining = count;
        int n = Math.min(BUFFERS, pairs ? 2 * count : count);
        this.buffers = new VkGpuBuffer[n];
        this.free = new ArrayBlockingQueue<>(n);
        VkImage2D img = null;
        long fb = 0;
        try {
            img = VkImage2D.create(sc.getFormat(), this.width, this.height, 1, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            for (int i = 0; i < n; i++) {
                this.buffers[i] = VkGpuBuffer.host((long) this.width * this.height * 4, 0);
                this.free.add(i);
            }
            fb = createFramebuffer(presentRenderPass, img.view(), sc.getDepthAttachment().getImageView());
        } catch (RuntimeException e) {
            if (fb != 0) vkDestroyFramebuffer(VkContext.device(), fb, null);
            if (img != null) img.free();
            for (VkGpuBuffer b : this.buffers) if (b != null) b.free();
            throw e;
        }
        this.image = img;
        this.framebuffer = fb;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            VisualsLog.warn("Frame generation burst: cannot create " + dir, e);
        }
        this.worker = new Thread(this::work, "VulkanMod Frame Burst");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private long createFramebuffer(long renderPass, long colourView, long depthView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                    .pAttachments(stack.longs(colourView, depthView)).width(this.width).height(this.height).layers(1);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateFramebuffer(VkContext.device(), info, null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("burst vkCreateFramebuffer failed: " + result);
            return p.get(0);
        }
    }

    // ---- pacer thread ----

    /** Captures are still wanted. */
    boolean wanted() {
        return this.remaining > 0 && !this.aborted;
    }

    /**
     * Claims n host buffers for one capture group (a present, or the pair of view 1): their indices, or null when fewer are free (the
     * group is counted as skipped and does not count toward the burst's N: it goes on until N groups are captured, not always consecutive).
     */
    int[] claim(int n) {
        int[] got = new int[n];
        for (int i = 0; i < n; i++) {
            Integer b = this.free.poll();
            if (b == null) {
                for (int j = 0; j < i; j++) this.free.add(got[j]);
                this.skipped++;
                return null;
            }
            got[i] = b;
        }
        this.remaining--;
        return got;
    }

    /** The index the group claimed last gets in the file names. */
    int nextIndex() {
        return this.index++;
    }

    /**
     * Records one capture into cmd, outside a render pass: draw (the compose draw) runs inside the swapchain's present pass on the burst
     * image, then the image is copied to host buffer buffer. The swapchain depth is the pass's depth attachment, discarded as the pacer's own
     * frame does.
     */
    void record(VkCommandBuffer cmd, MemoryStack stack, long renderPass, long depthImage, int depthAspect, int buffer, Runnable draw) {
        VkImageMemoryBarrier.Buffer in = VkImageMemoryBarrier.calloc(1, stack);
        FramePacer.barrier(in.get(0), this.image.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, in);
        VkImageMemoryBarrier.Buffer depth = VkImageMemoryBarrier.calloc(1, stack);
        FramePacer.barrier(depth.get(0), depthImage, depthAspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        int tests = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        vkCmdPipelineBarrier(cmd, tests, tests, 0, null, null, depth);

        VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(this.framebuffer)
                .pClearValues(VkClearValue.calloc(2, stack));
        begin.renderArea().offset().set(0, 0);
        begin.renderArea().extent().set(this.width, this.height);
        vkCmdBeginRenderPass(cmd, begin, VK_SUBPASS_CONTENTS_INLINE);
        draw.run();
        vkCmdEndRenderPass(cmd);

        VkImageMemoryBarrier.Buffer out = VkImageMemoryBarrier.calloc(1, stack);
        FramePacer.barrier(out.get(0), this.image.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, out);

        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
        copy.get(0).imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        copy.get(0).imageExtent().set(this.width, this.height, 1);
        vkCmdCopyImageToBuffer(cmd, this.image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, this.buffers[buffer].handle(), copy);
        VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, host, null, null);
    }

    /** After the submit that holds a capture: the worker writes it once the pacer timeline reaches value. */
    void submitted(int buffer, int index, boolean real, long value) {
        this.queue.add(new Item(buffer, index, real, value));
    }

    /** After a present's submit: once the N groups are claimed (and submitted), the worker may end after what is queued. */
    void endIfSpent() {
        if (this.remaining <= 0 && !this.ended) {
            this.ended = true;
            this.queue.add(END);
        }
    }

    // ---- worker ----

    private void work() {
        ByteBuffer rgba = MemoryUtil.memAlloc(this.width * this.height * 4);
        try {
            while (true) {
                Item it = this.queue.take();
                if (it == END) break;
                if (!this.aborted) {
                    try {
                        this.waitTimeline(it.value());
                        ByteBuffer src = this.buffers[it.buffer()].mapped();
                        toRgba(src, rgba, this.width * this.height, this.bgra);
                        Path out = this.dir.resolve(this.name + "_" + it.index() + (it.real() ? "_real" : "_gen") + ".png");
                        writePng(out, rgba, this.width, this.height);
                        this.written++;
                    } catch (IOException | RuntimeException e) {
                        VisualsLog.warn("Frame generation burst: cannot write " + this.name + "_" + it.index(), e);
                    }
                }
                this.free.add(it.buffer());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            MemoryUtil.memFree(rgba);
            this.done = true;
        }
    }

    private void waitTimeline(long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int r = vkWaitSemaphores(VkContext.device(), VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                    .pSemaphores(stack.longs(this.timeline)).pValues(stack.longs(value)), 10_000_000_000L);
            if (r != VK_SUCCESS) throw new IllegalStateException("burst vkWaitSemaphores failed: " + r);
        }
    }

    // ---- render thread ----

    /** Every file is written (or given up on): the object may be freed. Any thread. */
    public boolean done() {
        return this.done;
    }

    /** The pacer is stopped: the worker ends after the items already queued (those are skipped, not written). Joins it. */
    /** Whether the worker ended; false when it still runs after the wait (2026-10-30 audit: its buffers must then not be freed). */
    boolean abort() {
        this.aborted = true;
        this.queue.add(END);
        try {
            this.worker.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !this.worker.isAlive();
    }

    /** Logs the outcome. */
    void report() {
        VisualsLog.info("Frame generation burst " + this.name + ": " + this.written + " images written to " + this.dir + (this.skipped > 0
                ? ", " + this.skipped + " presents not captured (no free buffer)" : ""));
    }

    /** Render thread, pacer stopped, GPU done with every capture (done() or abort() first). */
    void free() {
        vkDestroyFramebuffer(VkContext.device(), this.framebuffer, null);
        this.image.free();
        for (VkGpuBuffer b : this.buffers) b.free();
    }

    // ---- pixels and PNG (pure) ----

    /** n pixels of src as RGBA into dst (both from position 0; absolute access): the swapchain's BGRA bytes swapped, RGBA copied. */
    static void toRgba(ByteBuffer src, ByteBuffer dst, int n, boolean bgra) {
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            byte a = src.get(o), b = src.get(o + 1), c = src.get(o + 2);
            dst.put(o, bgra ? c : a);
            dst.put(o + 1, b);
            dst.put(o + 2, bgra ? a : c);
            dst.put(o + 3, src.get(o + 3));
        }
    }

    /** A non-interlaced 8-bit RGBA PNG of rgba (width x height, from position 0, absolute access). */
    static void writePng(Path out, ByteBuffer rgba, int width, int height) throws IOException {
        java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream(1 << 20);
        Deflater deflater = new Deflater(1);
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, deflater, 1 << 16)) {
            byte[] row = new byte[1 + width * 4];
            for (int y = 0; y < height; y++) {
                rgba.get(y * width * 4, row, 1, width * 4);
                z.write(row);
            }
        } finally {
            deflater.end();
        }
        try (OutputStream os = Files.newOutputStream(out)) {
            os.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            ByteBuffer ihdr = ByteBuffer.allocate(13);
            ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
            chunk(os, "IHDR", ihdr.array());
            chunk(os, "IDAT", idat.toByteArray());
            chunk(os, "IEND", new byte[0]);
        }
    }

    private static void chunk(OutputStream os, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        os.write(ByteBuffer.allocate(4).putInt(data.length).array());
        os.write(t);
        os.write(data);
        os.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
