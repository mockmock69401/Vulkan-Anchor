package net.vulkanmod.visuals.framegen;

import java.util.Arrays;

/**
 * Pure pacing math for frame generation: when generated frames are presented between two real
 * ones, and when one must be dropped instead (FG design 3.2). It reads no clock; every time comes
 * in as a parameter, so the pacer thread owns all timing and this class stays testable.
 */
public final class FramePacing {
    public static final int HISTORY = 16;
    public static final long MIN_INTERVAL_NS = 4_000_000L;
    public static final long MAX_INTERVAL_NS = 50_000_000L;
    public static final long MAX_EXTRAPOLATION_NS = 33_000_000L;
    public static final long SPIN_NS = 1_500_000L;

    /** SKIP_FAST: no real-frame interval is known yet (fewer than two real presents); the name is kept for the present log. */
    public enum Decision { PRESENT, SKIP_PAUSED, SKIP_NEWER_REAL, SKIP_FAST, SKIP_TOO_FAR }

    private final long[] intervals = new long[HISTORY];
    private int count;
    private int next;
    private long lastPresent;
    private boolean hasLast;
    private int factor;

    public FramePacing(int factor) {
        setFactor(factor);
    }

    public void setFactor(int factor) {
        this.factor = Math.max(1, factor);
    }

    /** Records the interval from the previous real present. */
    public void realPresented(long presentNanos) {
        if (hasLast) {
            intervals[next] = presentNanos - lastPresent;
            next = (next + 1) % HISTORY;
            if (count < HISTORY) count++;
        }
        lastPresent = presentNanos;
        hasLast = true;
    }

    /** T-hat: the median of the recent intervals, clamped; 0 before two presents. */
    public long intervalEstimate() {
        if (count == 0) return 0;
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        long median = sorted[count / 2];
        return Math.max(MIN_INTERVAL_NS, Math.min(MAX_INTERVAL_NS, median));
    }

    /** The factor-1 targets {@code realPresent + j*T/k}; empty when T-hat is unknown or factor is 1. */
    public long[] schedule(long realPresentNanos) {
        long t = intervalEstimate();
        if (t == 0 || factor <= 1) return new long[0];
        long[] targets = new long[factor - 1];
        for (int j = 1; j < factor; j++) {
            targets[j - 1] = realPresentNanos + j * t / factor;
        }
        return targets;
    }

    /**
     * Whether a generated frame aimed at targetNanos should still be presented. No refresh rate is read: whether frames are generated
     * at all is the player's switch (2026-10-24, the detected refresh differs per monitor setup, so no automatic on/off by it).
     */
    public Decision decide(long targetNanos, long realPresentNanos, boolean newerRealHanded, boolean paused) {
        if (paused) return Decision.SKIP_PAUSED;
        if (newerRealHanded) return Decision.SKIP_NEWER_REAL;
        long t = intervalEstimate();
        // t == 0 (fewer than two presents): nothing to pace against, treat as too fast
        if (t == 0) return Decision.SKIP_FAST;
        if (targetNanos - realPresentNanos > Math.min(t, MAX_EXTRAPOLATION_NS)) return Decision.SKIP_TOO_FAR;
        return Decision.PRESENT;
    }

    /**
     * The display time a generated frame's camera is predicted for, held to the extrapolation cap decide() checks on the target
     * (min(T-hat, MAX_EXTRAPOLATION_NS) past the real present). A late acquire (FIFO blocking to the next vblank) can put shownAt past
     * it, and the image is already acquired, so it is clamped instead of skipped: a pose ahead of the next real frame would jump back.
     */
    static long capShownAt(long shownAtNanos, long realPresentNanos, long intervalEstimate) {
        return Math.min(shownAtNanos, realPresentNanos + Math.min(intervalEstimate, MAX_EXTRAPOLATION_NS));
    }

    /** Game time a generated frame shows: the real sample advanced by how far the display moved. */
    static long contentTime(long realSampleNanos, long realPresentNanos, long targetNanos) {
        return realSampleNanos + (targetNanos - realPresentNanos);
    }
}
