package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.fsr.FsrPresentWatch.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FSR design 4.4: FSR's swapchain hides OUT_OF_DATE from VulkanMod, so a stale real swapchain is found by its size and its presents. */
class FsrPresentWatchTest {
    private static final int W = 2560, H = 1410;

    @Test void checksOnceEveryWindowOfFrames() {
        FsrPresentWatch w = new FsrPresentWatch();
        for (int i = 1; i < FsrPresentWatch.WINDOW; i++) assertFalse(w.due(), "frame " + i);
        assertTrue(w.due());
        assertEquals(FsrPresentWatch.WINDOW, w.frames());
        w.reset();
        assertEquals(0, w.frames());
    }

    @Test void surfaceOfAnotherSizeRecreates() {
        FsrPresentWatch w = new FsrPresentWatch();
        assertEquals(Verdict.OK, w.check(10, W, H, W, H));
        assertEquals(Verdict.RECREATE, w.check(30, 1920, 1080, W, H));
        //a surface that does not report its size (0xFFFFFFFF) is left to the present count
        assertEquals(Verdict.OK, w.check(50, -1, -1, W, H));
    }

    @Test void stalledPresentsRecreateThenGiveUp() {
        FsrPresentWatch w = new FsrPresentWatch();
        assertEquals(Verdict.OK, w.check(100, W, H, W, H));
        assertEquals(Verdict.RECREATE, w.check(100, W, H, W, H));
        //a new swapchain counts from 0: the first check after a recreation only records
        w.reset();
        assertEquals(Verdict.OK, w.check(0, W, H, W, H));
        assertEquals(Verdict.RECREATE, w.check(0, W, H, W, H));
        w.reset();
        assertEquals(Verdict.OK, w.check(0, W, H, W, H));
        assertEquals(Verdict.GIVE_UP, w.check(0, W, H, W, H));
    }

    @Test void advancingPresentsClearTheStalls() {
        FsrPresentWatch w = new FsrPresentWatch();
        assertEquals(Verdict.OK, w.check(5, W, H, W, H));
        assertEquals(Verdict.RECREATE, w.check(5, W, H, W, H));
        w.reset();
        assertEquals(Verdict.OK, w.check(0, W, H, W, H));
        assertEquals(Verdict.OK, w.check(16, W, H, W, H));
        //two more stalls are needed again before giving up
        assertEquals(Verdict.RECREATE, w.check(16, W, H, W, H));
        w.reset();
        assertEquals(Verdict.OK, w.check(0, W, H, W, H));
        assertEquals(Verdict.RECREATE, w.check(0, W, H, W, H));
    }
}
