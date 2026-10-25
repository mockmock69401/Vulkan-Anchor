package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FramePacingTest {
    private static final long MS = 1_000_000L;

    /** Feeds n presents so that n-1 intervals of the given length are recorded. */
    private static FramePacing paced(int factor, long interval, int intervals) {
        FramePacing p = new FramePacing(factor);
        long t = 1_000_000_000L;
        p.realPresented(t);
        for (int i = 0; i < intervals; i++) {
            t += interval;
            p.realPresented(t);
        }
        return p;
    }

    @Test void theMedianIgnoresOneSpike() {
        FramePacing p = new FramePacing(2);
        long t = 0;
        p.realPresented(t);
        for (int i = 0; i < 15; i++) { t += 20 * MS; p.realPresented(t); }
        t += 200 * MS;
        p.realPresented(t);
        assertEquals(20_000_000L, p.intervalEstimate());
    }

    @Test void theEstimateIsClamped() {
        assertEquals(4_000_000L, paced(2, 2 * MS, 4).intervalEstimate());
        assertEquals(50_000_000L, paced(2, 80 * MS, 4).intervalEstimate());
    }

    @Test void factorTwoIsTheMidpoint() {
        FramePacing p = paced(2, 20 * MS, 4);
        assertArrayEquals(new long[] {1_010_000_000L}, p.schedule(1_000_000_000L));
    }

    @Test void factorFourSplitsTheInterval() {
        FramePacing p = paced(4, 20 * MS, 4);
        assertArrayEquals(new long[] {1_005_000_000L, 1_010_000_000L, 1_015_000_000L}, p.schedule(1_000_000_000L));
    }

    @Test void nothingBeforeTwoPresents() {
        FramePacing p = new FramePacing(2);
        p.realPresented(1_000_000_000L);
        assertEquals(0, p.schedule(1_000_000_000L).length);
    }

    @Test void pausedWinsOverEverything() {
        FramePacing p = paced(2, 20 * MS, 4);
        assertEquals(FramePacing.Decision.SKIP_PAUSED,
            p.decide(1_000_000_000L + 100 * MS, 1_000_000_000L, true, true));
    }

    @Test void aNewerRealFrameCancels() {
        FramePacing p = paced(2, 20 * MS, 4);
        assertEquals(FramePacing.Decision.SKIP_NEWER_REAL,
            p.decide(1_000_000_000L + 10 * MS, 1_000_000_000L, true, false));
    }

    /** Whether frames are generated is the player's switch alone: no refresh rate is read (2026-10-24, refresh detection differs per setup). */
    @Test void highFrameRatesStillGenerate() {
        assertEquals(FramePacing.Decision.PRESENT,
            paced(2, 4 * MS, 4).decide(1_002_000_000L, 1_000_000_000L, false, false));
        assertEquals(FramePacing.Decision.PRESENT,
            paced(2, 20 * MS, 4).decide(1_010_000_000L, 1_000_000_000L, false, false));
    }

    @Test void nothingIsGeneratedBeforeAnIntervalIsKnown() {
        FramePacing p = new FramePacing(2);
        p.realPresented(1_000_000_000L);
        assertEquals(FramePacing.Decision.SKIP_FAST, p.decide(1_005_000_000L, 1_000_000_000L, false, false));
    }

    @Test void aLongStallGeneratesNothingLate() {
        FramePacing p = paced(3, 2_000 * MS, 16);
        assertEquals(50_000_000L, p.intervalEstimate());
        assertEquals(2, p.schedule(1_000_000_000L).length);
        assertEquals(FramePacing.Decision.SKIP_TOO_FAR,
            p.decide(1_000_000_000L + 40 * MS, 1_000_000_000L, false, false));
    }

    @Test void contentTimeFollowsTheDisplay() {
        assertEquals(110L, FramePacing.contentTime(100, 130, 140));
    }

    /** FIFO 60 Hz, real 40 fps: the acquire blocked to the next vblank, past T-hat; the camera must not extrapolate past the cap. */
    @Test void aLateAcquireIsCappedAtTheExtrapolationLimit() {
        long pN = 1_000_000_000L;
        assertEquals(pN + 25 * MS, FramePacing.capShownAt(pN + 29 * MS, pN, 25 * MS));
        assertEquals(pN + 33 * MS, FramePacing.capShownAt(pN + 40 * MS, pN, 50 * MS), "never past MAX_EXTRAPOLATION_NS");
        assertEquals(pN + 12 * MS, FramePacing.capShownAt(pN + 12 * MS, pN, 25 * MS), "inside the cap it is unchanged");
    }
}
