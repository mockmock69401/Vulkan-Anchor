package net.vulkanmod.vulkan.framebuffer;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.queue.QueueLock;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.JNI.callPJJJJPI;
import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * FSR design 4.4: the swapchain calls VulkanMod makes (acquire, present, create, destroy, get images). {@link #NATIVE} is
 * Vulkan's own; while FSR's frame-generation swapchain is live, {@link #current()} returns its replacement functions.
 * Render thread, except present, which goes through {@link QueueLock#present}.
 */
public interface SwapchainFunctions {
    int acquire(long device, long swapchain, long timeout, long semaphore, long fence, IntBuffer index);

    int present(VkQueue q, VkPresentInfoKHR info);

    /** Creates a swapchain; throws when it cannot. */
    long create(VkDevice d, VkSwapchainCreateInfoKHR info);

    void destroy(VkDevice d, long swapchain);

    long[] images(VkDevice d, long swapchain);

    /**
     * True: create takes the previous swapchain as oldSwapchain and the caller destroys it afterwards. False (FSR): the
     * previous swapchain is destroyed first and create gets no oldSwapchain.
     */
    default boolean retiresOldSwapchain() {
        return true;
    }

    SwapchainFunctions NATIVE = new SwapchainFunctions() {
        @Override
        public int acquire(long device, long swapchain, long timeout, long semaphore, long fence, IntBuffer index) {
            //vkAcquireNextImageKHR with the raw device handle (VulkanMod has one device)
            long fn = Vulkan.getVkDevice().getCapabilities().vkAcquireNextImageKHR;
            return callPJJJJPI(device, swapchain, timeout, semaphore, fence, memAddress(index), fn);
        }

        @Override
        public int present(VkQueue q, VkPresentInfoKHR info) {
            return QueueLock.presentNative(q, info);
        }

        @Override
        public long create(VkDevice d, VkSwapchainCreateInfoKHR info) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pSwapchain = stack.longs(0L);
                Vulkan.checkResult(KHRSwapchain.vkCreateSwapchainKHR(d, info, null, pSwapchain), "Failed to create swap chain");
                return pSwapchain.get(0);
            }
        }

        @Override
        public void destroy(VkDevice d, long swapchain) {
            KHRSwapchain.vkDestroySwapchainKHR(d, swapchain, null);
        }

        @Override
        public long[] images(VkDevice d, long swapchain) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.ints(0);
                KHRSwapchain.vkGetSwapchainImagesKHR(d, swapchain, count, null);
                LongBuffer images = stack.mallocLong(count.get(0));
                KHRSwapchain.vkGetSwapchainImagesKHR(d, swapchain, count, images);
                long[] result = new long[images.capacity()];
                images.get(result);
                return result;
            }
        }
    };

    /** The functions in use: {@link #NATIVE} unless FSR's swapchain is live. */
    static SwapchainFunctions current() {
        return Holder.current;
    }

    /** Switches the functions; FsrSwapchain calls this with the device idle, around creating and destroying its contexts. */
    static void use(SwapchainFunctions functions) {
        Holder.current = functions;
    }

    final class Holder {
        private static volatile SwapchainFunctions current = NATIVE;

        private Holder() {
        }
    }
}
