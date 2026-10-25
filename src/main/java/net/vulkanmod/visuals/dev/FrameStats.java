package net.vulkanmod.visuals.dev;

import java.util.Arrays;

/**
 * Stutter (2026-10-21): the spike figures of a run of frame times, in milliseconds. Pure, so it is tested without the game.
 * Percentiles are nearest-rank on the sorted times; a "low" is the frame rate of the mean of the slowest share of the frames.
 */
public record FrameStats(int frames, double meanMs, double medianMs, double p99Ms, double p999Ms, double maxMs, double low1Fps,
                         double low01Fps, int over2xMedian, int over25Ms, int over50Ms) {

    public static FrameStats of(double[] ms, int n) {
        if (n <= 0) return new FrameStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        double[] s = Arrays.copyOf(ms, n);
        Arrays.sort(s);
        double sum = 0;
        for (double v : s) sum += v;
        double median = percentile(s, 0.5);
        int over2x = 0, over25 = 0, over50 = 0;
        for (double v : s) {
            if (v > 2 * median) over2x++;
            if (v > 25) over25++;
            if (v > 50) over50++;
        }
        return new FrameStats(n, sum / n, median, percentile(s, 0.99), percentile(s, 0.999), s[n - 1], low(s, 0.01), low(s, 0.001),
                over2x, over25, over50);
    }

    /** Nearest rank: the smallest value with at least p of the values at or below it. */
    static double percentile(double[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
    }

    /** 1000 over the mean of the slowest share (at least one frame). */
    static double low(double[] sorted, double share) {
        int k = Math.max(1, (int) Math.round(share * sorted.length));
        double sum = 0;
        for (int i = sorted.length - k; i < sorted.length; i++) sum += sorted[i];
        return 1000 / (sum / k);
    }

    public String line() {
        return String.format(java.util.Locale.ROOT,
                "%d frames, mean %.2f ms (%.0f fps), median %.2f, p99 %.2f, p99.9 %.2f, max %.1f ms, 1%% low %.0f fps, 0.1%% low %.0f fps, "
                        + ">2x median %d, >25 ms %d, >50 ms %d",
                this.frames, this.meanMs, this.meanMs > 0 ? 1000 / this.meanMs : 0, this.medianMs, this.p99Ms, this.p999Ms, this.maxMs,
                this.low1Fps, this.low01Fps, this.over2xMedian, this.over25Ms, this.over50Ms);
    }
}
