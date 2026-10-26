package net.vulkanmod.render.profiling;

import java.util.Arrays;

/**
 * Frame times of the last {@link #WINDOW_NS} for the FPS overlay (2026-10-26). The average is frames over elapsed time; min and max
 * come from the longest and shortest frame; the 1% and 0.1% lows are the frame rate of the average of the slowest 1% and 0.1% of
 * frames (at least one frame each). A frame longer than {@link #GAP_NS} (minimised window, loading) starts the window over.
 */
public final class FpsStats {
    static final long WINDOW_NS = 5_000_000_000L;
    static final long GAP_NS = 2_000_000_000L;
    private static final int CAPACITY = 1 << 15;

    public record Result(int frames, double average, double min, double max, double low1, double low01) {
    }

    private final long windowNs;
    private final long[] ring = new long[CAPACITY];
    private int head, size;
    private long total;

    public FpsStats() {
        this(WINDOW_NS);
    }

    FpsStats(long windowNs) {
        this.windowNs = windowNs;
    }

    public void clear() {
        this.head = 0;
        this.size = 0;
        this.total = 0;
    }

    /** Adds one frame's duration in nanoseconds. */
    public void add(long frameNs) {
        if (frameNs <= 0) return;
        if (frameNs > GAP_NS) {
            clear();
            return;
        }
        if (this.size == CAPACITY) dropOldest();
        this.ring[(this.head + this.size) % CAPACITY] = frameNs;
        this.size++;
        this.total += frameNs;
        while (this.size > 1 && this.total - this.ring[this.head] >= this.windowNs) dropOldest();
    }

    private void dropOldest() {
        this.total -= this.ring[this.head];
        this.head = (this.head + 1) % CAPACITY;
        this.size--;
    }

    /** The window's figures, or null before the first frame. */
    public Result compute() {
        if (this.size == 0) return null;
        long[] frames = new long[this.size];
        for (int i = 0; i < this.size; i++) frames[i] = this.ring[(this.head + i) % CAPACITY];
        Arrays.sort(frames);
        int n = frames.length;
        return new Result(n, n * 1e9 / this.total, 1e9 / frames[n - 1], 1e9 / frames[0],
                slowestRate(frames, 0.01), slowestRate(frames, 0.001));
    }

    /** The frame rate of the mean of the slowest share of the sorted frames. */
    private static double slowestRate(long[] sorted, double share) {
        int k = Math.max(1, (int) Math.ceil(sorted.length * share));
        long sum = 0;
        for (int i = sorted.length - k; i < sorted.length; i++) sum += sorted[i];
        return 1e9 * k / sum;
    }
}
