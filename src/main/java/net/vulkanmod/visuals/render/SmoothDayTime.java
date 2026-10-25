package net.vulkanmod.visuals.render;

/**
 * The shadows' time of day. Sun-step crossfade (2026-10-16): vanilla's time of day moves once a tick (Level.getTimeOfDay ignores the
 * partial tick), so the steps' progress would jump a third of a step twenty times a second; while the day runs the shadows take the
 * day time between ticks.
 * <p>
 * Shadows running backward (CTE2, 2026-10-22): the client moves its day time on by a tick each client tick, and the server's time
 * packet (every 20 server ticks) sets it to the server's. A server below 20 ticks a second therefore pulls the client's time back
 * every packet (about 26 ticks every 2.3 s at 9 ticks a second), and the shadows ran back by more than a second of sun each time.
 * The clock here moves at the day's own rate, averaged over RATE_SECONDS (the corrections included, so it is the server's), takes up
 * its offset from the client's time over CATCH_SECONDS and never moves back; a jump of more than SNAP_TICKS (/time set, sleeping, a
 * long stall) is followed at once. With the day stopped (doDaylightCycle off) or a fixed-time dimension the shadows keep vanilla's value.
 */
final class SmoothDayTime {
    static final double SNAP_TICKS = 100;
    static final double RATE_SECONDS = 8;
    static final double CATCH_SECONDS = 10;
    static final double MAX_RATE = 60;
    /** The longest frame the clock counts (a pause or a hitch does not move it further). */
    static final double MAX_FRAME_SECONDS = 0.25;
    static final double TICKS_PER_SECOND = 20;

    private double clock = Double.NaN, rate = TICKS_PER_SECOND, lastTarget;
    private long lastNanos;

    /** DimensionType.timeOfDay for a day time with a fraction of a tick. */
    static float timeOfDay(double dayTime) {
        double d0 = dayTime / 24000.0 - 0.25;
        d0 -= Math.floor(d0);
        double d1 = 0.5 - Math.cos(d0 * Math.PI) / 2.0;
        return (float) (d0 * 2.0 + d1) / 3.0f;
    }

    /**
     * Once a frame: the time of day for the shadows. vanilla is Level.getTimeOfDay, dayTime the client's day time, running whether the
     * day runs (the client's doDaylightCycle and no fixed time), nanos the frame's System.nanoTime.
     */
    float timeOfDay(float vanilla, long dayTime, float partialTick, boolean running, long nanos) {
        double target = dayTime + (double) partialTick;
        if (!running) {
            this.clock = Double.NaN;
            return vanilla;
        }
        if (Double.isNaN(this.clock) || Math.abs(target - this.clock) > SNAP_TICKS) {
            this.clock = target;
            this.rate = TICKS_PER_SECOND;
        } else {
            double dt = Math.min(Math.max((nanos - this.lastNanos) / 1e9, 0), MAX_FRAME_SECONDS);
            if (dt > 0) {
                //The day's rate: the client's time moved per second, its pulls back included (averaged over RATE_SECONDS)
                this.rate += ((target - this.lastTarget) / dt - this.rate) * Math.min(1, dt / RATE_SECONDS);
                this.rate = Math.min(Math.max(this.rate, 0), MAX_RATE);
                double ahead = this.clock + this.rate * dt;
                this.clock = Math.max(this.clock, ahead + (target - ahead) * Math.min(1, dt / CATCH_SECONDS));
            }
        }
        this.lastTarget = target;
        this.lastNanos = nanos;
        return timeOfDay(this.clock);
    }

    /** The clock in day-time ticks (NaN while the shadows keep vanilla's value); for tests. */
    double clock() {
        return this.clock;
    }
}
