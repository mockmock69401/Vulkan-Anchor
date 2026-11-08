package net.vulkanmod.visuals.render;

/**
 * W6: the ripples' clock and offsets (boundary 2). The clean RippleClock advances each octave's flow phase by FLOW x speedFactor x the
 * frame's game-time step; {@link #offsets} turns the phase and the camera into what reflection.comp receives in its slots 11 and 12:
 * each octave's world offset, wrapped into [0, PERIOD), so that offset + (surface - camera) is the world position less the flow,
 * exact in float near the camera whatever the world coordinates. The RIPPLE_ constants mirror water.glsl's (WaterTest compares them).
 * 2026-11-09 (session 2a): four octaves of short directional waves (water.glsl's header for the ripples); each flows along its own
 * direction (RIPPLE_DIR_*), the first along the old main flow, which the plants' wind follows (FoliageWind).
 */
public final class RippleMath {
    public static final int OCTAVES = 4;
    /**
     * Per octave the drift on x and z in blocks per second (the pattern moves this way): along the octave's direction (RIPPLE_DIR_X/Z),
     * the longer waves faster (deep water waves' speed grows with the wavelength; slowed here to read as ripples, not swell).
     */
    public static final float[][] FLOW = {{0.30f, 0.12f}, {0.256f, -0.043f}, {0.122f, 0.183f}, {0.15f, -0.10f}};
    /** The longest game-time step (seconds) RippleClock takes as flow: a longer one is a jump (a time command, a pause, a dimension change). */
    public static final double MAX_STEP = 1.0;
    /** The wrap period in blocks: the ripple noise repeats every PERIOD blocks (water.glsl's RIPPLE_PERIOD; every cell divides it). */
    public static final double PERIOD = 240.0;
    /** How much faster the ripples flow in full rain (1: twice as fast). Java only. */
    public static final float RAIN_SPEED = 1.0f;

    // water.glsl's ripple consts
    public static final float RIPPLE_PERIOD = (float) PERIOD;
    public static final float RIPPLE_DIR_X_0 = 5.0f, RIPPLE_DIR_Z_0 = 2.0f, RIPPLE_CELL_0 = 2.0f, RIPPLE_STRETCH_0 = 2.2f, RIPPLE_STEEP_0 = 0.035f;
    public static final float RIPPLE_DIR_X_1 = 6.0f, RIPPLE_DIR_Z_1 = -1.0f, RIPPLE_CELL_1 = 0.95f, RIPPLE_STRETCH_1 = 2.0f, RIPPLE_STEEP_1 = 0.035f;
    public static final float RIPPLE_DIR_X_2 = 2.0f, RIPPLE_DIR_Z_2 = 3.0f, RIPPLE_CELL_2 = 0.45f, RIPPLE_STRETCH_2 = 1.8f, RIPPLE_STEEP_2 = 0.03f;
    public static final float RIPPLE_DIR_X_3 = 3.0f, RIPPLE_DIR_Z_3 = -2.0f, RIPPLE_CELL_3 = 0.22f, RIPPLE_STRETCH_3 = 1.5f, RIPPLE_STEEP_3 = 0.025f;
    public static final float RIPPLE_DENSITY_MIN = 0.25f, RIPPLE_DENSITY_MAX = 2.0f;
    public static final float RIPPLE_FADE_NONE = 2.0f, RIPPLE_FADE_FULL = 8.0f;
    public static final float RIPPLE_MIN_COS = 0.02f;
    public static final int RIPPLE_PRIME_X = 73856093, RIPPLE_PRIME_Z = 19349663, RIPPLE_PRIME_SEED = 83492791;

    /** The octaves' directions (x, z), cells, stretches and steepnesses in order. */
    static final float[][] DIRS = {{RIPPLE_DIR_X_0, RIPPLE_DIR_Z_0}, {RIPPLE_DIR_X_1, RIPPLE_DIR_Z_1}, {RIPPLE_DIR_X_2, RIPPLE_DIR_Z_2}, {RIPPLE_DIR_X_3, RIPPLE_DIR_Z_3}};
    static final float[] CELLS = {RIPPLE_CELL_0, RIPPLE_CELL_1, RIPPLE_CELL_2, RIPPLE_CELL_3};
    static final float[] STRETCHES = {RIPPLE_STRETCH_0, RIPPLE_STRETCH_1, RIPPLE_STRETCH_2, RIPPLE_STRETCH_3};
    static final float[] STEEPS = {RIPPLE_STEEP_0, RIPPLE_STEEP_1, RIPPLE_STEEP_2, RIPPLE_STEEP_3};

    private RippleMath() {
    }

    /** v wrapped into [0, PERIOD); NaN and infinities give 0. */
    public static double wrap(double v) {
        if (!Double.isFinite(v)) return 0;
        double w = v - PERIOD * Math.floor(v / PERIOD);
        return w >= 0 && w < PERIOD ? w : 0;
    }

    /** The game time in seconds (20 ticks a second), the partial tick included: the ripples stop when the game pauses. */
    public static double seconds(long gameTime, float partialTick) {
        return (gameTime + (double) partialTick) / 20.0;
    }

    /** The flow's speed factor: the ripple speed setting (1 = 100%), RAIN_SPEED faster in full rain; never negative or NaN. */
    public static float speedFactor(float rain, float rippleSpeedSetting) {
        float r = rain > 0 ? Math.min(rain, 1) : 0;
        float s = rippleSpeedSetting > 0 ? rippleSpeedSetting : 0;
        if (!Float.isFinite(s)) s = 0;
        return s * (1 + RAIN_SPEED * r);
    }

    /**
     * What reflection.comp receives in its ripple slots 11 and 12: [2k], [2k + 1] octave k's world offset on x and z, wrap(camera - phase),
     * for k &lt; OCTAVES. phase is RippleClock's (null: none yet). 2026-11-09: the fourth octave took the camera's x and z fraction's
     * place ([6], [7]; no shader read them).
     */
    public static float[] offsets(double cameraX, double cameraZ, double[] phase) {
        float[] o = new float[2 * OCTAVES];
        for (int k = 0; k < OCTAVES; k++) {
            double px = phase != null && phase.length > 2 * k ? phase[2 * k] : 0;
            double pz = phase != null && phase.length > 2 * k + 1 ? phase[2 * k + 1] : 0;
            o[2 * k] = (float) wrap(cameraX - px);
            o[2 * k + 1] = (float) wrap(cameraZ - pz);
        }
        return o;
    }
}
