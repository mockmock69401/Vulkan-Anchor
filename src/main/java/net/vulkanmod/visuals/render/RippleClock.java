package net.vulkanmod.visuals.render;

/**
 * P7e-3 design 3.3: the ripples' flow phase per octave (x, z), RippleMath.OCTAVES of them, advanced by each frame's game-time step so a change of
 * speed (rain) never moves them at once. Steps that are not flow (the first frame, backwards, a pause, a jump) are skipped.
 */
public final class RippleClock {
    private double last = Double.NaN;
    private final double[] phase = new double[RippleMath.OCTAVES * 2];

    public void advance(double seconds, float speedFactor) {
        double dt = seconds - this.last;
        this.last = seconds;
        if (!(dt > 0) || dt > RippleMath.MAX_STEP) return;
        for (int layer = 0; layer < RippleMath.OCTAVES; layer++) {
            this.phase[layer * 2] = RippleMath.wrap(this.phase[layer * 2] + RippleMath.FLOW[layer][0] * speedFactor * dt);
            this.phase[layer * 2 + 1] = RippleMath.wrap(this.phase[layer * 2 + 1] + RippleMath.FLOW[layer][1] * speedFactor * dt);
        }
    }

    public double[] phase() {
        return this.phase.clone();
    }
}
