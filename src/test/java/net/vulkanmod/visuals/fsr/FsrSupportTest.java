package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;

import static net.vulkanmod.visuals.fsr.FsrSupport.Reason.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** FSR design 3.2: the support verdict and the order of its checks. */
class FsrSupportTest {
    private static final int VK13 = 1 << 22 | 3 << 12;
    private static final int VK12_9 = 1 << 22 | 2 << 12 | 9;

    @Test void windowsX64Vulkan13WithDllAndQueuesIsOk() {
        assertEquals(OK, FsrSupport.verdict("Windows 11", "amd64", VK13, true, true, true, false));
    }

    @Test void eachMissingPieceGivesItsReasonInOrder() {
        assertEquals(NOT_WINDOWS, FsrSupport.verdict("Linux", "amd64", VK13, true, true, true, false));
        assertEquals(NOT_X64, FsrSupport.verdict("Windows 11", "aarch64", VK13, true, true, true, false));
        assertEquals(VULKAN_BELOW_1_3, FsrSupport.verdict("Windows 11", "amd64", VK12_9, true, true, true, false));
        assertEquals(DLL, FsrSupport.verdict("Windows 11", "amd64", VK13, false, true, true, false));
        assertEquals(QUEUES, FsrSupport.verdict("Windows 11", "amd64", VK13, true, false, true, false));
        assertEquals(FEATURES, FsrSupport.verdict("Windows 11", "amd64", VK13, true, true, false, false));
        assertEquals(FAILED, FsrSupport.verdict("Windows 11", "amd64", VK13, true, true, true, true));
    }

    @Test void earlierReasonsWinOverLaterOnes() {
        assertEquals(NOT_WINDOWS, FsrSupport.verdict("Mac OS X", "aarch64", VK12_9, false, false, false, true));
        assertEquals(NOT_X64, FsrSupport.verdict("Windows 11", "x86", VK12_9, false, false, false, true));
        assertEquals(VULKAN_BELOW_1_3, FsrSupport.verdict("Windows 11", "x86_64", VK12_9, false, false, false, true));
        assertEquals(DLL, FsrSupport.verdict("Windows 11", "x86_64", VK13, false, false, false, true));
        assertEquals(QUEUES, FsrSupport.verdict("Windows 11", "x86_64", VK13, true, false, false, true));
        assertEquals(FEATURES, FsrSupport.verdict("Windows 11", "x86_64", VK13, true, true, false, true));
    }
}
