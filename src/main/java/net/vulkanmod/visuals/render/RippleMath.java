package net.vulkanmod.visuals.render;

/**
 * W6: the ripples' clock and offsets (boundary 2). The clean RippleClock advances each octave's flow phase by FLOW x speedFactor x the
 * frame's game-time step; {@link #offsets} turns the phase and the camera into what reflection.comp receives in its slots 11 and 12:
 * each octave's world offset, wrapped into [0, PERIOD), so that offset + (surface - camera) is the world position less the flow,
 * exact in float near the camera whatever the world coordinates. The RIPPLE_ constants mirror water.glsl's (WaterTest compares them).
 */
public final class RippleMath {
    public static final int OCTAVES = 3;
    /** Per octave the drift on x and z in blocks per second (the pattern moves this way): three directions, the small octaves faster. */
    public static final float[][] FLOW = {{0.30f, 0.12f}, {-0.22f, 0.36f}, {0.18f, -0.52f}};
    /** The longest game-time step (seconds) RippleClock takes as flow: a longer one is a jump (a time command, a pause, a dimension change). */
    public static final double MAX_STEP = 1.0;
    /** The wrap period in blocks: the ripple noise repeats every PERIOD blocks (water.glsl's RIPPLE_PERIOD; every cell divides it). */
    public static final double PERIOD = 240.0;
    /** How much faster the ripples flow in full rain (1: twice as fast). Java only. */
    public static final float RAIN_SPEED = 1.0f;

    // water.glsl's ripple consts
    public static final float RIPPLE_PERIOD = (float) PERIOD;
    public static final float RIPPLE_CELL_0 = 6.0f, RIPPLE_CELL_1 = 2.5f, RIPPLE_CELL_2 = 1.2f;
    public static final float RIPPLE_HEIGHT_0 = 0.10f, RIPPLE_HEIGHT_1 = 0.05f, RIPPLE_HEIGHT_2 = 0.022f;
    public static final float RIPPLE_FADE_NONE = 2.0f, RIPPLE_FADE_FULL = 8.0f;
    public static final float RIPPLE_MIN_COS = 0.02f;
    public static final int RIPPLE_PRIME_X = 73856093, RIPPLE_PRIME_Z = 19349663, RIPPLE_PRIME_SEED = 83492791;

    /** The octaves' cells and heights in order. */
    static final float[] CELLS = {RIPPLE_CELL_0, RIPPLE_CELL_1, RIPPLE_CELL_2};
    static final float[] HEIGHTS = {RIPPLE_HEIGHT_0, RIPPLE_HEIGHT_1, RIPPLE_HEIGHT_2};

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
     * What reflection.comp receives in its ripple slots: [2k], [2k + 1] octave k's world offset on x and z, wrap(camera - phase), for
     * k &lt; OCTAVES; [6], [7] the camera's x and z fraction. phase is RippleClock's (null: none yet).
     */
    public static float[] offsets(double cameraX, double cameraZ, double[] phase) {
        float[] o = new float[8];
        for (int k = 0; k < OCTAVES; k++) {
            double px = phase != null && phase.length > 2 * k ? phase[2 * k] : 0;
            double pz = phase != null && phase.length > 2 * k + 1 ? phase[2 * k + 1] : 0;
            o[2 * k] = (float) wrap(cameraX - px);
            o[2 * k + 1] = (float) wrap(cameraZ - pz);
        }
        o[6] = Double.isFinite(cameraX) ? (float) (cameraX - Math.floor(cameraX)) : 0;
        o[7] = Double.isFinite(cameraZ) ? (float) (cameraZ - Math.floor(cameraZ)) : 0;
        return o;
    }
}
