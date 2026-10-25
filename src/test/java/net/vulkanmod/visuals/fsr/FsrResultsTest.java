package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.fsr.FsrResults.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** FSR design 4.4: what Renderer does with the VkResult of FSR's replacement acquire and present. */
class FsrResultsTest {
    @Test void acquireCodes() {
        assertEquals(Outcome.OK, FsrResults.acquire(VK_SUCCESS));
        //no image to render into: the swapchain is made again (FSR's NOT_READY means every replacement image is acquired)
        assertEquals(Outcome.RECREATE, FsrResults.acquire(VK_SUBOPTIMAL_KHR));
        assertEquals(Outcome.RECREATE, FsrResults.acquire(VK_ERROR_OUT_OF_DATE_KHR));
        assertEquals(Outcome.RECREATE, FsrResults.acquire(VK_NOT_READY));
        assertEquals(Outcome.RECREATE, FsrResults.acquire(VK_TIMEOUT));
        assertEquals(Outcome.RECREATE, FsrResults.acquire(VK_INCOMPLETE));
        //FSR's swapchain lost its images, or any other error: FSR is turned off, the game goes on without it
        assertEquals(Outcome.FAIL, FsrResults.acquire(VK_ERROR_SURFACE_LOST_KHR));
        assertEquals(Outcome.FAIL, FsrResults.acquire(VK_ERROR_DEVICE_LOST));
        assertEquals(Outcome.FAIL, FsrResults.acquire(VK_ERROR_OUT_OF_HOST_MEMORY));
    }

    @Test void presentCodes() {
        //FSR's present hands back its presenter thread's last result: NOT_READY, TIMEOUT and INCOMPLETE are a skipped present
        assertEquals(Outcome.OK, FsrResults.present(VK_SUCCESS));
        assertEquals(Outcome.OK, FsrResults.present(VK_NOT_READY));
        assertEquals(Outcome.OK, FsrResults.present(VK_TIMEOUT));
        assertEquals(Outcome.OK, FsrResults.present(VK_INCOMPLETE));
        assertEquals(Outcome.RECREATE, FsrResults.present(VK_SUBOPTIMAL_KHR));
        assertEquals(Outcome.RECREATE, FsrResults.present(VK_ERROR_OUT_OF_DATE_KHR));
        assertEquals(Outcome.FAIL, FsrResults.present(VK_ERROR_SURFACE_LOST_KHR));
        assertEquals(Outcome.FAIL, FsrResults.present(VK_ERROR_DEVICE_LOST));
    }
}
