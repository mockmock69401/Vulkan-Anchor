package net.vulkanmod.visuals.dev;

/** Pilot glide (thin-shadow flicker gate): a straight line from a to b over seconds, sampled at elapsed seconds. */
public final class Glide {
    private Glide() {
    }

    public static double[] at(double[] a, double[] b, double seconds, double elapsed) {
        double t = seconds <= 0 ? 1 : Math.max(0, Math.min(1, elapsed / seconds));
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
}
