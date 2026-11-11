package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class Synchronization {
    private static final int ALLOCATION_SIZE = 50;

    public static final Synchronization INSTANCE = new Synchronization(ALLOCATION_SIZE);

    private final LongBuffer fences;
    private int idx = 0;

    private ObjectArrayList<CommandPool.CommandBuffer> commandBuffers = new ObjectArrayList<>();

    /**
     * 2026-11-12 (shader roadmap session 14, the tick-frame stall session 7 left): graphics-queue image uploads made for a frame (the
     * animated sprites each tick, sub-image uploads while a frame records), by the frame index whose staging buffer they read. The
     * frame's submit no longer waits for them on the CPU: they run on the frame's own queue before its draws, and their layout
     * transitions (transfer write to shader read) order the draws after the copies on the GPU. Waiting there made the CPU wait for the
     * GPU to finish every frame still queued (2.3-2.9 ms on each tick frame, a04bbb5 too). They are waited where their staging
     * buffer is reused (waitFrameUploads, the frame index's next preInitFrame: long done by then) and by every full waitFences.
     */
    private final ObjectArrayList<ObjectArrayList<CommandPool.CommandBuffer>> frameUploads = new ObjectArrayList<>();

    Synchronization(int allocSize) {
        this.fences = MemoryUtil.memAllocLong(allocSize);
    }

    public synchronized void addCommandBuffer(CommandPool.CommandBuffer commandBuffer) {
        this.addFence(commandBuffer.getFence());
        this.commandBuffers.add(commandBuffer);
    }

    public synchronized void addFence(long fence) {
        if (idx == ALLOCATION_SIZE)
            waitFences();

        fences.put(idx, fence);
        idx++;
    }

    public synchronized void waitFences() {
        waitTransfers();
        for (int i = 0; i < this.frameUploads.size(); i++) {
            waitFrameUploads(i);
        }
    }

    /**
     * Before a frame's submit: submits the uploads deferred on the render thread, then waits for the transfers of other kinds (chunk
     * and buffer uploads, which the frame may read without a barrier). The frame's graphics-queue image uploads stay pending
     * (frameUploads).
     */
    public synchronized void waitTransfers() {
        //uploads deferred on the render thread are submitted first, so waiting for every transfer includes them
        net.vulkanmod.vulkan.queue.GraphicsQueue queue = net.vulkanmod.vulkan.device.DeviceManager.getGraphicsQueue();
        if (queue != null) queue.flushDeferred();

        if (idx == 0)
            return;

        VkDevice device = Vulkan.getVkDevice();

        fences.limit(idx);

        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.add(net.vulkanmod.visuals.dev.FrameRecorder.XFER_FENCES, idx);
        long waitStart = net.vulkanmod.visuals.dev.FrameRecorder.ON ? System.nanoTime() : 0;
        vkWaitForFences(device, fences, true, VUtil.UINT64_MAX);
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.time(net.vulkanmod.visuals.dev.FrameRecorder.XFER_WAIT, waitStart);

        this.commandBuffers.forEach(CommandPool.CommandBuffer::reset);
        this.commandBuffers.clear();

        fences.limit(ALLOCATION_SIZE);
        idx = 0;
    }

    /** A graphics-queue image upload of the current frame (see frameUploads), already submitted. */
    public synchronized void addFrameUpload(CommandPool.CommandBuffer commandBuffer) {
        int frame = Renderer.getCurrentFrame();
        while (this.frameUploads.size() <= frame) {
            this.frameUploads.add(new ObjectArrayList<>());
        }
        this.frameUploads.get(frame).add(commandBuffer);
    }

    /** Waits for (and recycles) the graphics-queue image uploads made with frame index frame's staging buffer. */
    public synchronized void waitFrameUploads(int frame) {
        if (frame >= this.frameUploads.size())
            return;
        ObjectArrayList<CommandPool.CommandBuffer> list = this.frameUploads.get(frame);
        if (list.isEmpty())
            return;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            LongBuffer pending = stack.mallocLong(list.size());
            for (int i = 0; i < list.size(); i++) {
                pending.put(i, list.get(i).getFence());
            }
            long waitStart = net.vulkanmod.visuals.dev.FrameRecorder.ON ? System.nanoTime() : 0;
            vkWaitForFences(Vulkan.getVkDevice(), pending, true, VUtil.UINT64_MAX);
            if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.time(net.vulkanmod.visuals.dev.FrameRecorder.XFER_WAIT, waitStart);
        }
        for (int i = 0; i < list.size(); i++) {
            list.get(i).reset();
        }
        list.clear();
    }

    public static void waitFence(long fence) {
        VkDevice device = Vulkan.getVkDevice();

        vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
    }

    public static boolean checkFenceStatus(long fence) {
        VkDevice device = Vulkan.getVkDevice();
        return vkGetFenceStatus(device, fence) == VK_SUCCESS;
    }

}

