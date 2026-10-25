package net.vulkanmod.visuals.render;

/** The camera's sky light (0..1), eased (design 2026-10-11 P7d 3.2): 0 in caves, where the sun shading fades out. */
public final class SkyExposure {
    public static final float TIME_CONSTANT = 1;
    /** Seconds: a longer gap (a pause, a loading screen) snaps to the target. */
    public static final float MAX_STEP = 10;
    /**
     * Gate G7j round 6: air blocks straight above the camera from which it counts as under the open sky, and from which fully.
     * Under an island 130 blocks up the sky light is 0 (it spreads only 15 blocks sideways), which read as a cave and turned the
     * shadow maps, the sun shading and the sky-level-0 lift off; a cave or a house has its ceiling closer.
     */
    public static final int OPEN_AIR_MIN = 24, OPEN_AIR_FULL = 48;
    private float value;
    private long lastNanos;
    private boolean started;

    public float value() {
        return this.value;
    }

    /** target is the camera block's sky light / 15. */
    public void update(float target, long nanos) {
        this.value = this.started ? next(this.value, target, (nanos - this.lastNanos) / 1e9f) : clamp01(target);
        this.started = true;
        this.lastNanos = nanos;
    }

    /** The target for update: the camera block's sky light / 15, or more where enough open air is above it. */
    public static float target(float cameraSky, int airAbove) {
        float open = (float) (airAbove - OPEN_AIR_MIN) / (OPEN_AIR_FULL - OPEN_AIR_MIN);
        return clamp01(Math.max(cameraSky, open));
    }

    public static float next(float previous, float target, float seconds) {
        float t = clamp01(target);
        if (seconds >= MAX_STEP) return t;
        if (seconds <= 0) return previous;
        return previous + (t - previous) * (float) (1 - Math.exp(-seconds / TIME_CONSTANT));
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
