package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GpuTimesTest {
    private static final double NS = 1.0;//one tick = 1 ns

    @Test
    void eachIntervalGoesToTheLabelThatStartsIt() {
        GpuTimes t = new GpuTimes();
        t.add(new String[]{"a", "b", "end"}, new long[]{0, 1_000_000, 3_000_000}, 3, NS, -1L);
        assertEquals(1, t.frames());
        assertEquals(1.0, t.averageMs("a"), 1e-9);
        assertEquals(2.0, t.averageMs("b"), 1e-9);
        assertEquals(0.0, t.averageMs("end"), 1e-9, "the last mark only closes the one before");
        assertEquals(3.0, t.averageTotalMs(), 1e-9);
    }

    @Test
    void framesAverageAndRepeatedLabelsAdd() {
        GpuTimes t = new GpuTimes();
        t.add(new String[]{"a", "b", "a", "end"}, new long[]{0, 1_000_000, 2_000_000, 4_000_000}, 4, NS, -1L);
        t.add(new String[]{"a", "end"}, new long[]{10, 2_000_010}, 2, NS, -1L);
        assertEquals(2, t.frames());
        assertEquals((3.0 + 2.0) / 2, t.averageMs("a"), 1e-9);
        assertEquals(0.5, t.averageMs("b"), 1e-9, "a label missing from a frame counts 0 there");
        assertEquals(3.0, t.averageTotalMs(), 1e-9);
    }

    @Test
    void ticksWrapWithinTheValidBits() {
        GpuTimes t = new GpuTimes();
        long mask = (1L << 32) - 1;
        t.add(new String[]{"a", "end"}, new long[]{mask - 99, 400}, 2, NS, mask);
        assertEquals(500e-6, t.averageMs("a"), 1e-12);
    }

    @Test
    void thePeriodScalesTicks() {
        GpuTimes t = new GpuTimes();
        t.add(new String[]{"a", "end"}, new long[]{0, 1000}, 2, 41.67, -1L);
        assertEquals(1000 * 41.67 / 1e6, t.averageMs("a"), 1e-9);
    }

    @Test
    void shortFramesAreSkipped() {
        GpuTimes t = new GpuTimes();
        t.add(new String[]{"a"}, new long[]{5}, 1, NS, -1L);
        t.add(new String[0], new long[0], 0, NS, -1L);
        assertEquals(0, t.frames());
        assertEquals("no frames", t.line());
    }

    @Test
    void theLineKeepsTheFirstSeenOrderAndClears() {
        GpuTimes t = new GpuTimes();
        t.add(new String[]{"lod", "post", "end"}, new long[]{0, 1_250_000, 1_500_000}, 3, NS, -1L);
        assertEquals("1 frames: lod 1.25, post 0.25; total 1.50", t.line());
        t.clear();
        assertEquals(0, t.frames());
        assertEquals("no frames", t.line());
    }

    @Test
    void cpuTimesAverageByCallAndFollowTheGpuLine() {
        GpuTimes t = new GpuTimes();
        t.addCpu("frame", 2_000_000);
        t.addCpu("frame", 4_000_000);
        t.addCpu("post", 500_000);
        assertEquals("no frames; CPU frame 3.00, post 0.50", t.line());
        t.add(new String[]{"lod", "end"}, new long[]{0, 1_000_000}, 2, NS, -1L);
        assertEquals("1 frames: lod 1.00; total 1.00; CPU frame 3.00, post 0.50", t.line());
        t.clear();
        assertEquals("no frames", t.line());
    }
}
