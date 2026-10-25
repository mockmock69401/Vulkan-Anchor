package net.vulkanmod.visuals.render;

/**
 * Sun-step crossfade (2026-10-16): a shadow map moves its edges a texel at a time (0.16 blocks near, 1.5 far), so under a
 * turning sun a shadow stood still for a while and then jumped. Each cascade instead holds two maps: one drawn for the sun at
 * the start of the current step (time of day quantised by STEP) and one for the next step. The lighting mixes the two by the
 * sun's progress through the step, so a texel that changes between them fades over the step instead of popping.
 * At most one map per cascade is drawn a frame: the missing one of the two, else (refresh) the older one at its own step.
 */
final class ShadowSteps {
    /** The sun's turn per step, in degrees: about 3.3 game ticks (a sixth of a second at normal speed). */
    static final double STEP_DEGREES = 0.05;
    /** The same in time of day (a full turn is 1). */
    static final double STEP = STEP_DEGREES / 360.0;

    private ShadowSteps() {
    }

    static long step(float timeOfDay) {
        return (long) Math.floor(timeOfDay / STEP);
    }

    /** How far the sun is through its step, 0 at the step's start. */
    static float progress(float timeOfDay) {
        double s = timeOfDay / STEP;
        return (float) (s - Math.floor(s));
    }

    /** The time of day a step's map is drawn for. */
    static float timeOf(long step) {
        return (float) (step * STEP);
    }

    /** Draw into slot (0 or 1) the map of this step. */
    record Draw(int slot, long step) {
    }

    /** Read the primary slot, and the secondary one (-1: none) with this weight. */
    record Read(int primary, int secondary, float secondaryWeight) {
    }

    private static int find(long step, long[] steps, boolean[] valid) {
        for (int i = 0; i < 2; i++) {
            if (valid[i] && steps[i] == step) return i;
        }
        return -1;
    }

    /**
     * This frame's draw for a cascade in step k: the map of k when missing (into the slot not holding k + 1), else the map of
     * k + 1, else on a refresh frame the older map again at its own step (blocks placed or broken since); null for none.
     */
    static Draw plan(long k, long[] steps, boolean[] valid, long[] drawnSerial, boolean refreshDue) {
        int a = find(k, steps, valid), b = find(k + 1, steps, valid);
        if (a < 0) return new Draw(b == 0 ? 1 : 0, k);
        if (b < 0) return new Draw(1 - a, k + 1);
        if (!refreshDue) return null;
        int older = drawnSerial[0] <= drawnSerial[1] ? 0 : 1;
        return new Draw(older, steps[older]);
    }

    /** After this frame's draw: k's and k + 1's maps mixed by the progress f, else whichever valid map there is; null for none. */
    static Read read(long k, float f, long[] steps, boolean[] valid) {
        int a = find(k, steps, valid), b = find(k + 1, steps, valid);
        if (a >= 0 && b >= 0) return new Read(a, b, f);
        if (a >= 0) return new Read(a, -1, 0);
        if (b >= 0) return new Read(b, -1, 0);
        for (int i = 0; i < 2; i++) {
            if (valid[i]) return new Read(i, -1, 0);
        }
        return null;
    }
}
