package net.vulkanmod.visuals.render;

/**
 * 2026-10-23 (CTE2: the plants' sway now and then stopped short): the seconds the wind and the water ripples flow by. They took the
 * client's game time plus the partial tick, which a busy client or a lagging server makes run in bursts (frames at over 2.5x or under
 * 0.4x real time, and steps back when the server's time arrives). This clock counts real time between frames instead, stopped while
 * the game is paused; a step over MAX_STEP (a loading screen, a hitch) is not flow.
 */
public final class FlowClock {
    public static final double MAX_STEP = RippleMath.MAX_STEP;

    private long lastNanos;
    private boolean started;
    private double seconds;

    /** Once a frame: the clock's seconds after the real time since the last call (none while paused). */
    public double advance(long nanos, boolean paused) {
        if (this.started && !paused) {
            double dt = (nanos - this.lastNanos) / 1e9;
            if (dt > 0 && dt <= MAX_STEP) this.seconds += dt;
        }
        this.lastNanos = nanos;
        this.started = true;
        return this.seconds;
    }

    public double seconds() {
        return this.seconds;
    }
}
