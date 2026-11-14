package net.vulkanmod.visuals.camera;

/**
 * 2026-11-14 (session 22, the user: walking up or down stairs makes the view height jump): the rendered camera's height eases toward
 * the player's real height after a step up or down, instead of following it within one tick. Pure (no game types), so it is tested
 * without the game; StepSmoothing feeds it the local player's ticks and reads the offset each frame.
 * <p>
 * The camera is drawn at the real (tick-interpolated) eye height plus an offset. A tick that counts as a step moves the camera's target
 * by the step at once and the offset takes the step back, so the camera stays where it was and then approaches the target
 * exponentially: within a tick at partialTick pt the offset is (a - s) k^pt + s (1 - pt), where a is the offset at the tick's start, s the
 * step taken this tick and k the decay per tick. At pt 1 that is (a - s) k, the next tick's a, so the camera's path is continuous and does
 * not depend on the frame rate. Any move that is not a step (a jump, a fall, flying, swimming, riding, a teleport) moves the camera
 * exactly; what offset is left just keeps fading. Only the camera lags: the player, its hitbox, the crosshair's target and every
 * raycast stay at the real position.
 */
public final class StepSmoothMath {
    /** The settings slider (percent): the time constant is STRENGTH_SECONDS per percent, 50 (the default) = 80 ms. */
    public static final int STRENGTH_MIN = 10, STRENGTH_MAX = 100, STRENGTH_DEFAULT = 50;
    static final double STRENGTH_SECONDS = 0.0016;
    static final double TICK_SECONDS = 0.05;
    /** The tallest step smoothed (blocks): a full block with a step-height mod, never more. */
    public static final double MAX_STEP = 1.0;
    /** A step height below vanilla's 0.6 still smooths stairs and slabs. */
    public static final double MIN_STEP = 0.6;
    /** The offset never grows past this (blocks); a run of steps faster than the camera catches up is held to it. */
    public static final double MAX_OFFSET = 1.0;
    /** Ticks missed between two frames beyond this (a freeze, a long load) drop the offset. */
    public static final int MAX_TICK_GAP = 20;
    private static final double EPS = 1e-4;

    /** The decay of the offset per tick for the slider's strength (percent). */
    public static double tickFactor(int strength) {
        int s = Math.max(STRENGTH_MIN, Math.min(STRENGTH_MAX, strength));
        return Math.exp(-TICK_SECONDS / (s * STRENGTH_SECONDS));
    }

    /** The tallest move smoothed as a step for the entity's step height (vanilla player 0.6), between MIN_STEP and MAX_STEP. */
    public static double stepLimit(double stepHeight) {
        return Math.max(MIN_STEP, Math.min(MAX_STEP, stepHeight)) + EPS;
    }

    /** The offset at partialTick pt of a tick that starts at offset a and takes the step s (0: no step this tick). */
    public static double offsetAt(double a, double s, double k, double pt) {
        double p = Math.max(0.0, Math.min(1.0, pt));
        return (a - s) * Math.pow(k, p) + s * (1.0 - p);
    }

    /** One tick of the player as seen after it ticked. */
    public record Tick(double dy, boolean onGround, boolean eligible, double stepLimit) {
    }

    /** The offset at the current tick's start. */
    private double a;
    /** The step the current tick takes back (0: none). */
    private double s;
    /** How far the player has dropped since it walked off the ground without jumping; -1 while a drop is not a step (a jump, a fall). */
    private double drop;

    public StepSmoothMath() {
        reset();
    }

    /** The camera at the real position, nothing to fade (teleport, respawn, another dimension, the setting off). */
    public void reset() {
        a = 0.0;
        s = 0.0;
        drop = -1.0;
    }

    public double offset(double k, double pt) {
        return offsetAt(a, s, k, pt);
    }

    /**
     * Holds the offset at partialTick pt to at most max (blocks; never below 0) and lets it fade from there: a camera still above a
     * player who stepped down must not rise into the ceiling of a low stair tunnel. Returns the offset at pt afterwards.
     */
    public double limitAbove(double max, double k, double pt) {
        double now = offset(k, pt);
        double m = Math.max(0.0, max);
        if (now <= m) return now;
        double p = Math.max(0.0, Math.min(1.0, pt));
        a = m / Math.pow(k, p);
        s = 0.0;
        return m;
    }

    /** The current tick's step (for tests and the frame generation's eye path). */
    public double step() {
        return s;
    }

    /**
     * ticks ticks passed (the last one is t): the offset fades through them and t's step, if it is one, is taken back. A gap of more than
     * MAX_TICK_GAP ticks or a move taller than MAX_STEP (a teleport the client saw as a move) drops the offset.
     */
    public void tick(int ticks, Tick t, double k) {
        if (ticks <= 0) return;
        if (ticks > MAX_TICK_GAP || Math.abs(t.dy()) > MAX_STEP + EPS) {
            reset();
            if (t.onGround()) drop = 0.0;
            return;
        }
        a = offsetAt(a, s, k, 1.0);
        for (int i = 1; i < ticks; i++) a *= k;
        s = isStep(t) ? t.dy() : 0.0;
        //the offset this tick ends at, held to MAX_OFFSET
        double end = a - s;
        if (end > MAX_OFFSET) s = a - MAX_OFFSET;
        else if (end < -MAX_OFFSET) s = a + MAX_OFFSET;
        if (Math.abs(a) < EPS * 0.01 && s == 0.0) a = 0.0;
    }

    private boolean isStep(Tick t) {
        if (!t.eligible()) {
            drop = t.onGround() ? 0.0 : -1.0;
            return false;
        }
        boolean step = false;
        if (t.dy() > 0.0) {
            //a step up lands on the ground in the same tick; a jump leaves it (and its drop is a fall, not a step down)
            step = t.onGround() && t.dy() <= t.stepLimit();
            if (!t.onGround()) drop = -1.0;
        } else if (t.dy() < 0.0 && drop >= 0.0) {
            //walking off a stair: the drop counts as a step until it is taller than one
            drop -= t.dy();
            step = drop <= t.stepLimit();
            if (!step) drop = -1.0;
        }
        if (t.onGround()) drop = 0.0;
        return step;
    }
}
