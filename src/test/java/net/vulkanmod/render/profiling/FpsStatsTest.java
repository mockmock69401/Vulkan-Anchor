package net.vulkanmod.render.profiling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FpsStatsTest {
    private static final long MS = 1_000_000L;

    @Test
    void steadyFramesGiveOneRate() {
        FpsStats stats = new FpsStats();
        for (int i = 0; i < 120; i++) stats.add(16_666_667L);
        FpsStats.Result r = stats.compute();
        assertEquals(120, r.frames());
        assertEquals(60.0, r.average(), 0.01);
        assertEquals(60.0, r.min(), 0.01);
        assertEquals(60.0, r.max(), 0.01);
        assertEquals(60.0, r.low1(), 0.01);
        assertEquals(60.0, r.low01(), 0.01);
    }

    @Test
    void lowsAverageTheSlowestFrames() {
        FpsStats stats = new FpsStats(100_000 * MS);
        for (int i = 0; i < 989; i++) stats.add(10 * MS);
        for (int i = 0; i < 10; i++) stats.add(20 * MS);
        stats.add(100 * MS);
        FpsStats.Result r = stats.compute();
        assertEquals(1000, r.frames());
        assertEquals(1000 / 10.19, r.average(), 0.01);
        assertEquals(100.0, r.max(), 0.01);
        assertEquals(10.0, r.min(), 0.01);
        //slowest 10: 100 ms and nine of 20 ms
        assertEquals(1000.0 / 28.0, r.low1(), 0.01);
        //slowest 1
        assertEquals(10.0, r.low01(), 0.01);
    }

    @Test
    void windowKeepsTheLatestFrames() {
        FpsStats stats = new FpsStats(1000 * MS);
        for (int i = 0; i < 100; i++) stats.add(5 * MS);
        for (int i = 0; i < 100; i++) stats.add(10 * MS);
        FpsStats.Result r = stats.compute();
        assertEquals(100, r.frames());
        assertEquals(100.0, r.average(), 0.01);
        assertEquals(100.0, r.max(), 0.01);
    }

    @Test
    void longGapStartsOver() {
        FpsStats stats = new FpsStats();
        for (int i = 0; i < 50; i++) stats.add(10 * MS);
        stats.add(3_000 * MS);
        assertNull(stats.compute());
        stats.add(20 * MS);
        assertEquals(50.0, stats.compute().average(), 0.01);
    }

    @Test
    void formatsEachMode() {
        FpsStats.Result r = new FpsStats.Result(100, 59.6, 41.2, 72.5, 38.4, 30.1);
        assertNull(FpsOverlay.format(FpsOverlay.OFF, r));
        assertEquals("60 FPS", FpsOverlay.format(FpsOverlay.SIMPLE, r));
        assertEquals("60 FPS  min 41  max 73", FpsOverlay.format(FpsOverlay.BASIC, r));
        assertEquals("60 FPS  1% low 38  0.1% low 30", FpsOverlay.format(FpsOverlay.DETAILED, r));
        assertNull(FpsOverlay.format(FpsOverlay.SIMPLE, null));
    }
}
