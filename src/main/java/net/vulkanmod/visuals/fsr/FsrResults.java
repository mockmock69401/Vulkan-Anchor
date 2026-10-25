package net.vulkanmod.visuals.fsr;

import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.VK_INCOMPLETE;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;

/**
 * FSR design 4.4: what Renderer does with a VkResult of FSR's replacement acquire or present while FSR's swapchain is live, where
 * VulkanMod's own codes would end in a RuntimeException. FSR's acquire answers NOT_READY when every replacement image is acquired
 * and not yet presented, which only a new swapchain clears; its present hands back what its presenter thread last got, where
 * NOT_READY, TIMEOUT and INCOMPLETE are a present it skipped. An error turns FSR off for the session instead of ending the game.
 */
public final class FsrResults {
    /** OK: go on; RECREATE: make the swapchain again (as for OUT_OF_DATE); FAIL: FsrSupport.markFailed, then make it again. */
    public enum Outcome {OK, RECREATE, FAIL}

    private FsrResults() {
    }

    public static Outcome acquire(int vkResult) {
        if (vkResult == VK_SUCCESS) return Outcome.OK;
        if (vkResult == VK_SUBOPTIMAL_KHR || vkResult == VK_ERROR_OUT_OF_DATE_KHR) return Outcome.RECREATE;
        //NOT_READY, TIMEOUT, INCOMPLETE and any other success code: no image this frame
        return vkResult > 0 ? Outcome.RECREATE : Outcome.FAIL;
    }

    public static Outcome present(int vkResult) {
        if (vkResult == VK_SUCCESS || vkResult == VK_NOT_READY || vkResult == VK_TIMEOUT || vkResult == VK_INCOMPLETE) return Outcome.OK;
        if (vkResult == VK_SUBOPTIMAL_KHR || vkResult == VK_ERROR_OUT_OF_DATE_KHR) return Outcome.RECREATE;
        return vkResult > 0 ? Outcome.OK : Outcome.FAIL;
    }
}
