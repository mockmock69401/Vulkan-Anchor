package net.vulkanmod.vulkan.queue;

import net.vulkanmod.vulkan.framebuffer.SwapchainFunctions;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The one lock behind every queue submit, present and wait-idle. Vulkan requires host access to a VkQueue to be
 * externally synchronised, and vkDeviceWaitIdle touches every queue, so the render thread and the frame generation pacer
 * thread must share a single lock for all of them (FG design 3.2). Reentrant so a caller may nest, e.g. a wait-idle
 * inside a flush.
 */
public final class QueueLock {
    private static final ReentrantLock LOCK = new ReentrantLock();
    /**
     * 2026-10-30 (audit): the present queue's own lock when it is a VkQueue VulkanMod never submits to (another family): the frame
     * generation pacer's present can block for up to a refresh under FIFO, and under LOCK every render-thread submit waited behind
     * it. Device-wide waits take both, LOCK first; nothing that holds PRESENT_LOCK takes LOCK.
     */
    private static final ReentrantLock PRESENT_LOCK = new ReentrantLock();
    private static volatile long separatePresentQueue;

    private QueueLock() {
    }

    /** DeviceManager, once the queues exist: whether present has a VkQueue of its own (none of the others is the same handle). */
    public static void presentQueue(VkQueue present, VkQueue... others) {
        long p = present.address();
        for (VkQueue o : others) {
            if (o != null && o.address() == p) {
                separatePresentQueue = 0L;
                return;
            }
        }
        separatePresentQueue = p;
    }

    static boolean separate(long queue) {
        return queue != 0L && queue == separatePresentQueue;
    }

    public static int submit(VkQueue q, VkSubmitInfo info, long fence) {
        LOCK.lock();
        try {
            return VK10.vkQueueSubmit(q, info, fence);
        } finally {
            LOCK.unlock();
        }
    }

    public static int submit(VkQueue q, VkSubmitInfo.Buffer infos, long fence) {
        LOCK.lock();
        try {
            return VK10.vkQueueSubmit(q, infos, fence);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * FSR design 4.3: vkQueueSubmit with raw pointers, for FSR's submits to the game queue from its own threads (its
     * queue submit callback).
     */
    public static int submit(VkQueue q, int submitCount, long pSubmits, long fence) {
        LOCK.lock();
        try {
            return VK10.nvkQueueSubmit(q, submitCount, pSubmits, fence);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * VulkanMod's one present path: through {@link SwapchainFunctions#current()}, Vulkan's present under the lock, or FSR's
     * replacement present while its swapchain is live. FSR's present is not called under the lock: it can block for up to a frame
     * waiting for its presenter thread, which would hold every other submitter out that long. FSR's game-queue submits inside it
     * run on this thread through the submit callback, which takes the (reentrant) lock itself; FSR's presenter and interpolation
     * threads use only FSR's own present and acquire queues, never VulkanMod's.
     */
    public static int present(VkQueue q, VkPresentInfoKHR info) {
        return SwapchainFunctions.current().present(q, info);
    }

    /** vkQueuePresentKHR under the lock ({@link SwapchainFunctions#NATIVE}). */
    public static int presentNative(VkQueue q, VkPresentInfoKHR info) {
        ReentrantLock lock = separate(q.address()) ? PRESENT_LOCK : LOCK;
        lock.lock();
        try {
            return KHRSwapchain.vkQueuePresentKHR(q, info);
        } finally {
            lock.unlock();
        }
    }

    /** Runs r holding the lock (FSR design 4.4: FSR's wait for presents, which waits on the game queue and the device itself). */
    public static void locked(Runnable r) {
        LOCK.lock();
        PRESENT_LOCK.lock();
        try {
            r.run();
        } finally {
            PRESENT_LOCK.unlock();
            LOCK.unlock();
        }
    }

    public static int queueWaitIdle(VkQueue q) {
        ReentrantLock lock = separate(q.address()) ? PRESENT_LOCK : LOCK;
        lock.lock();
        try {
            return VK10.vkQueueWaitIdle(q);
        } finally {
            lock.unlock();
        }
    }

    public static int deviceWaitIdle(VkDevice d) {
        LOCK.lock();
        PRESENT_LOCK.lock();
        try {
            return VK10.vkDeviceWaitIdle(d);
        } finally {
            PRESENT_LOCK.unlock();
            LOCK.unlock();
        }
    }
}
