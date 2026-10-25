package net.vulkanmod.visuals.vk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P7f: GPU time per labelled interval, averaged over frames. A frame is a list of timestamps, each with the label of the
 * interval it starts; the last one only closes the interval before it.
 */
public final class GpuTimes {
    private final Map<String, double[]> sums = new LinkedHashMap<>();//label -> total ns
    private final Map<String, double[]> cpu = new LinkedHashMap<>();//label -> {total ns, calls}
    private double total;
    private int frames;

    /** ticks[0..count) in order; nsPerTick is the device's timestampPeriod; validMask keeps the counter's valid bits (wraps). */
    public void add(String[] labels, long[] ticks, int count, double nsPerTick, long validMask) {
        if (count < 2) return;
        for (int i = 0; i + 1 < count; i++) {
            double ns = ((ticks[i + 1] - ticks[i]) & validMask) * nsPerTick;
            this.sums.computeIfAbsent(labels[i], k -> new double[1])[0] += ns;
            this.total += ns;
        }
        this.sums.computeIfAbsent(labels[count - 1], k -> new double[1]);
        this.frames++;
    }

    /** A render-thread step's CPU time, averaged over its calls. */
    public void addCpu(String label, long ns) {
        double[] s = this.cpu.computeIfAbsent(label, k -> new double[2]);
        s[0] += ns;
        s[1]++;
    }

    public int frames() {
        return this.frames;
    }

    public double averageMs(String label) {
        double[] s = this.sums.get(label);
        return s == null || this.frames == 0 ? 0 : s[0] / this.frames / 1e6;
    }

    public double averageTotalMs() {
        return this.frames == 0 ? 0 : this.total / this.frames / 1e6;
    }

    /** "N frames: label ms, ...; total ms; CPU label ms, ...", labels without time (closing marks) left out. */
    public String line() {
        StringBuilder b = new StringBuilder(this.gpuLine());
        boolean first = true;
        for (Map.Entry<String, double[]> e : this.cpu.entrySet()) {
            b.append(first ? "; CPU " : ", ").append(e.getKey()).append(' ').append(ms(e.getValue()[0] / e.getValue()[1]));
            first = false;
        }
        return b.toString();
    }

    private String gpuLine() {
        if (this.frames == 0) return "no frames";
        StringBuilder b = new StringBuilder().append(this.frames).append(" frames: ");
        boolean first = true;
        for (Map.Entry<String, double[]> e : this.sums.entrySet()) {
            if (e.getValue()[0] == 0) continue;
            if (!first) b.append(", ");
            b.append(e.getKey()).append(' ').append(ms(e.getValue()[0] / this.frames));
            first = false;
        }
        return b.append("; total ").append(ms(this.total / this.frames)).toString();
    }

    private static String ms(double ns) {
        return String.format(Locale.ROOT, "%.2f", ns / 1e6);
    }

    public void clear() {
        this.sums.clear();
        this.cpu.clear();
        this.total = 0;
        this.frames = 0;
    }
}
