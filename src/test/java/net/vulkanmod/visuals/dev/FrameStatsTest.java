package net.vulkanmod.visuals.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recorder's spike figures: nearest-rank percentiles, lows as the mean of the slowest share, the spike counts. */
class FrameStatsTest {
    @Test
    void steadyFramesHaveNoSpikes() {
        double[] ms = new double[1000];
        java.util.Arrays.fill(ms, 4);
        FrameStats s = FrameStats.of(ms, ms.length);
        assertEquals(1000, s.frames());
        assertEquals(4, s.medianMs(), 1e-9);
        assertEquals(4, s.maxMs(), 1e-9);
        assertEquals(250, s.low1Fps(), 1e-9);
        assertEquals(0, s.over2xMedian());
    }

    @Test
    void spikesAreCountedAndPulledIntoTheLows() {
        double[] ms = new double[1000];
        java.util.Arrays.fill(ms, 4);
        ms[10] = 60;//one 60 ms hitch
        for (int i = 0; i < 9; i++) ms[100 + i] = 10;//nine 10 ms frames
        FrameStats s = FrameStats.of(ms, ms.length);
        assertEquals(10, s.over2xMedian());
        assertEquals(1, s.over25Ms());
        assertEquals(1, s.over50Ms());
        assertEquals(60, s.maxMs(), 1e-9);
        assertEquals(10, s.p999Ms(), 1e-9);//rank 999 of 1000: the one hitch is above it
        assertEquals(4, s.p99Ms(), 1e-9);//rank 990: the ten slow frames are all above it
        assertEquals(1000 / ((60 + 9 * 10) / 10.0), s.low1Fps(), 1e-9);
        assertEquals(1000 / 60.0, s.low01Fps(), 1e-9);
        assertTrue(s.line().contains(">2x median 10"));
    }

    @Test
    void onlyTheFirstNValuesCountAndNoneGivesZeros() {
        double[] ms = {5, 5, 100};
        assertEquals(5, FrameStats.of(ms, 2).maxMs(), 1e-9);
        assertEquals(0, FrameStats.of(ms, 0).frames());
    }
}
