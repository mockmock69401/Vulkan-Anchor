package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.fsr.FsrPresentQuiesce.Result;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** FSR design 4.4 (final review C1): no queue or device wait starts while FSR's presenter thread may still be presenting. */
class FsrPresentQuiesceTest {
    private static final long MS = 1_000_000L;

    /** A fake clock that moves 0.5 ms per pause, and a present count that follows a schedule of (time, count) steps. */
    private static final class Fake {
        long now;
        final long[][] steps;

        Fake(long[]... steps) {
            this.steps = steps;
        }

        long presents() {
            long c = 0;
            for (long[] s : steps) if (now >= s[0]) c = s[1];
            return c;
        }

        Result await(long stableNs, long timeoutNs) {
            LongSupplier clock = () -> now;
            return FsrPresentQuiesce.await(this::presents, clock, () -> now += MS / 2, stableNs, timeoutNs);
        }
    }

    @Test void settlesOnceTheCountStopsMovingForTheStableTime() {
        //presents at 0, 3 and 7 ms (a generated and a real frame), then nothing: settled 10 ms after the last one
        Fake f = new Fake(new long[]{0, 100}, new long[]{3 * MS, 101}, new long[]{7 * MS, 102});
        assertEquals(Result.SETTLED, f.await(10 * MS, 200 * MS));
        assertEquals(17 * MS, f.now, MS / 2);
    }

    @Test void aQuietPresenterSettlesAfterOneStableTime() {
        Fake f = new Fake(new long[]{0, 5});
        assertEquals(Result.SETTLED, f.await(4 * MS, 200 * MS));
        assertEquals(4 * MS, f.now);
    }

    @Test void givesUpAtTheTimeout() {
        //a count that moves every 2 ms never stays for 4 ms
        long[][] steps = new long[200][];
        for (int i = 0; i < steps.length; i++) steps[i] = new long[]{2L * i * MS, i};
        Fake f = new Fake(steps);
        assertEquals(Result.TIMED_OUT, f.await(4 * MS, 50 * MS));
        assertEquals(50 * MS, f.now, MS / 2);
    }

    @Test void stableTimeFollowsTheFrameInterval() {
        //one and a half real frames: FSR paces the generated and the real present of a frame over about one frame interval
        assertEquals(9 * MS, FsrPresentQuiesce.stableNs(6 * MS));
        assertEquals(25 * MS, FsrPresentQuiesce.stableNs(16_666_667L), MS / 1000);
        //never shorter than 4 ms, never longer than 50 ms; 20 ms when no frame interval is known yet
        assertEquals(4 * MS, FsrPresentQuiesce.stableNs(MS));
        assertEquals(50 * MS, FsrPresentQuiesce.stableNs(500 * MS));
        assertEquals(20 * MS, FsrPresentQuiesce.stableNs(-1));
    }
}
