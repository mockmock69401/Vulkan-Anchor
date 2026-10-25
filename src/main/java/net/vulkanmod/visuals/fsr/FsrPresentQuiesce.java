package net.vulkanmod.visuals.fsr;

import java.util.function.LongSupplier;

/**
 * FSR design 4.4 (final review C1, H1): FSR's presenter thread signals its present semaphore before it calls vkQueuePresentKHR, so
 * FSR's wait-for-presents can go on to vkQueueWaitIdle and vkDeviceWaitIdle while that thread is still inside vkQueuePresentKHR on
 * the same queue (validation: FSR's present queue used by two threads). Before any such wait, and before a configure that makes FSR
 * wait for its presents, this waits until FSR's present count has not moved for a stable time: the presenter thread has presented
 * everything it was given and waits for the next frame. Pure: FsrSwapchain feeds it the count, the clock and the pause.
 */
public final class FsrPresentQuiesce {
    public enum Result {SETTLED, TIMED_OUT}

    static final long MIN_STABLE_NS = 4_000_000L, MAX_STABLE_NS = 50_000_000L, UNKNOWN_STABLE_NS = 20_000_000L;

    private FsrPresentQuiesce() {
    }

    /**
     * Polls presents until it returned the same value for stableNs (SETTLED) or timeoutNs have passed (TIMED_OUT), calling pause
     * between polls.
     */
    public static Result await(LongSupplier presents, LongSupplier clock, Runnable pause, long stableNs, long timeoutNs) {
        long start = clock.getAsLong();
        long last = presents.getAsLong();
        long lastChange = start;
        while (true) {
            long now = clock.getAsLong();
            if (now - lastChange >= stableNs) return Result.SETTLED;
            if (now - start >= timeoutNs) return Result.TIMED_OUT;
            pause.run();
            long count = presents.getAsLong();
            if (count != last) {
                last = count;
                lastChange = clock.getAsLong();
            }
        }
    }

    /**
     * How long the count must stay put: one and a half real frame intervals (FSR spreads a frame's generated and real presents over
     * about one interval), at least 4 ms and at most 50 ms; 20 ms when the interval is not known (frameIntervalNs &lt;= 0).
     */
    public static long stableNs(long frameIntervalNs) {
        if (frameIntervalNs <= 0) return UNKNOWN_STABLE_NS;
        return Math.max(MIN_STABLE_NS, Math.min(MAX_STABLE_NS, frameIntervalNs * 3 / 2));
    }
}
