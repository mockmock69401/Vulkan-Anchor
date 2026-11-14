package net.vulkanmod.visuals.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.vulkan.util.MappedBuffer;

/**
 * 2026-10-21: plants and leaves sway in the wind. terrain.vsh moves the vertices FoliageMarker marked (a plant's by its height in
 * the plant, leaves whole) by four travelling waves over the world (a main swell along the wind, a cross wave, a slow gust envelope
 * and a quick flutter). The wind keeps time with the water: its phases advance by the ripples' speed factor (RippleMath.speedFactor:
 * the ripple speed setting, faster in rain) and it blows along the ripples' main flow (RippleMath.FLOW[0]); rain also bends the
 * plants further. Each wave fits PERIOD a whole number of times along x and z, so the camera is passed wrapped into [0, PERIOD) and
 * the pattern is exact in float near the camera wherever it is.
 * <p>
 * The terrain's vertex uniforms: WindPhase (the four waves' phases, radians) and WindParams (x, z the wrapped camera; z the plants'
 * sway at their top, w the leaves', blocks; both 0 with the setting or the post-processing off).
 */
public final class FoliageWind {
    /** The pattern's period in blocks (the ripples' period: one wrap for both). */
    public static final double PERIOD = RippleMath.PERIOD;
    /** The waves' angular speeds at speed factor 1 (radians per second): main, cross, gust, flutter. */
    public static final float[] OMEGA = {1.7f, 2.3f, 0.45f, 5.3f};
    /** The plants' sway at their top and the leaves' (blocks) at setting 100 in clear weather. */
    public static final float PLANT_AMPLITUDE = 0.12f, LEAF_AMPLITUDE = 0.035f;
    /**
     * 2026-10-22 (CTE2: far canopies rippled): terrain.vsh fades the sway out between these distances (blocks; start, end): farther a
     * leaf's move is under a pixel (about 0.03 blocks a pixel at 32 blocks, FOV 70, 1440 rows) and only shimmers its texture, and the
     * waves' travel read as ripples rolling over the forest. Plants sway further and fade later.
     */
    public static final float[] LEAF_FADE = {16, 40}, PLANT_FADE = {32, 64};
    /** Full rain bends further by this share. */
    public static final float RAIN_GAIN = 0.6f;
    /**
     * Session 20: the hanging blocks' swing (blocks sideways per block below their anchor) at setting 100 in clear weather. Rain swings
     * them further by RAIN_GAIN too.
     */
    public static final float HANG_AMPLITUDE = 0.11f;
    /**
     * Session 20 follow-up: the hanging blocks' clock wraps at this many seconds (WindHang.y, 0..1), and each column swings a whole number
     * of times in it (terrain.vsh hangOffset, hangColumn): HANG_CYCLES + 0 .. HANG_CYCLES_SPAN - 1 times along its swing and
     * HANG_CROSS_CYCLES + 0 .. HANG_CROSS_SPAN - 1 across it (2.0 .. 3.0 s and 2.6 .. 4.1 s a swing), scaled by HANG_SIZE + 0 ..
     * HANG_SIZE_SPAN. The columns are told apart by their block wrapped at HANG_WRAP (the wind's period).
     */
    public static final double HANG_TIME_PERIOD = 120;
    public static final int HANG_CYCLES = 40, HANG_CYCLES_SPAN = 21, HANG_CROSS_CYCLES = 29, HANG_CROSS_SPAN = 17, HANG_WRAP = 240;
    public static final float HANG_SIZE = 0.7f, HANG_SIZE_SPAN = 0.6f;
    /** The longest step (seconds) taken as flow, as RippleClock's. */
    public static final double MAX_STEP = RippleMath.MAX_STEP;

    private static final MappedBuffer PHASE = new MappedBuffer(4 * 4);
    private static final MappedBuffer PARAMS = new MappedBuffer(4 * 4);
    private static final MappedBuffer HANG = new MappedBuffer(4 * 4);
    private static double hangTime;
    private static final double[] phase = new double[4];
    private static double last = Double.NaN;

    private FoliageWind() {
    }

    /** The sway amplitudes {plants, leaves} for a setting (0..2, 1 = 100) and the rain level; 0 when off. */
    public static float[] amplitudes(float setting, float rain, boolean on) {
        float s = on && setting > 0 && Float.isFinite(setting) ? setting : 0;
        float r = rain > 0 ? Math.min(rain, 1) : 0;
        float g = s * (1 + RAIN_GAIN * r);
        return new float[]{PLANT_AMPLITUDE * g, LEAF_AMPLITUDE * g};
    }

    /** terrain.vsh's windFade: the share of the sway at a distance, 1 up to fade[0], 0 from fade[1], smoothstep between. */
    public static float fade(float distance, float[] fade) {
        float t = Math.max(0, Math.min(1, (distance - fade[0]) / (fade[1] - fade[0])));
        return 1 - t * t * (3 - 2 * t);
    }

    /** v wrapped into [0, 2 pi). */
    static double wrapAngle(double v) {
        double t = 2 * Math.PI;
        double w = v - t * Math.floor(v / t);
        return Double.isFinite(w) && w >= 0 && w < t ? w : 0;
    }

    /**
     * Once a frame from the visuals' frame (after the cutout terrain: the next frame's terrain reads it): the game time (seconds),
     * the ripples' speed factor, the setting and rain. The camera is read when the terrain's uniforms are written (params).
     */
    public static synchronized void update(double seconds, float speedFactor, float setting, float rain) {
        update(seconds, speedFactor, setting, rain, 0);
    }

    /** As update, with the hanging blocks' setting (0..2, 1 = 100). */
    public static synchronized void update(double seconds, float speedFactor, float setting, float rain, float hangSetting) {
        double dt = seconds - last;
        last = seconds;
        if (dt > 0 && dt <= MAX_STEP) {
            for (int i = 0; i < 4; i++) phase[i] = wrapAngle(phase[i] + OMEGA[i] * speedFactor * dt);
            hangTime = wrapTime(hangTime + dt);
        }
        HANG.putFloat(0, hangAmplitude(hangSetting, rain));
        HANG.putFloat(4, (float) (hangTime / HANG_TIME_PERIOD));
        float[] a = amplitudes(setting, rain, true);
        for (int i = 0; i < 4; i++) PHASE.putFloat(i * 4, (float) phase[i]);
        PARAMS.putFloat(8, a[0]);
        PARAMS.putFloat(12, a[1]);
    }

    /** Session 20 follow-up: t wrapped into [0, HANG_TIME_PERIOD). */
    static double wrapTime(double t) {
        double w = t - HANG_TIME_PERIOD * Math.floor(t / HANG_TIME_PERIOD);
        return Double.isFinite(w) && w >= 0 && w < HANG_TIME_PERIOD ? w : 0;
    }

    /** terrain.vsh's hangRandom: the PCG hash's next number of a column (0..1); state[0] advances. */
    static float hangRandom(int[] state) {
        state[0] = state[0] * 747796405 + (int) 2891336453L;
        int s = state[0];
        int w = ((s >>> ((s >>> 28) + 4)) ^ s) * 277803737;
        w = (w >>> 22) ^ w;
        return (w >>> 8) / 16777216f;
    }

    /**
     * Session 20 follow-up, as terrain.vsh's hangOffset: the column at block x, z (wrapped at HANG_WRAP): {swings along, swings across
     * (per HANG_TIME_PERIOD), the swing's phase and the cross swing's (0..1), the swing's direction (radians), its size}.
     */
    public static float[] hangColumn(int x, int z) {
        int cx = Math.floorMod(x, HANG_WRAP), cz = Math.floorMod(z, HANG_WRAP);
        int[] state = {cx * 73856093 ^ cz * 19349663};
        float cycles = HANG_CYCLES + (float) Math.floor(hangRandom(state) * HANG_CYCLES_SPAN);
        float cross = HANG_CROSS_CYCLES + (float) Math.floor(hangRandom(state) * HANG_CROSS_SPAN);
        float phase = hangRandom(state);
        float crossPhase = hangRandom(state);
        float turn = 6.2831853f * hangRandom(state);
        float size = HANG_SIZE + HANG_SIZE_SPAN * hangRandom(state);
        return new float[]{cycles, cross, phase, crossPhase, turn, size};
    }

    /** A column's own swing {along, across} (-1..1) at clock c (WindHang.y, 0..1), as terrain.vsh. */
    public static float[] hangSwing(float[] column, float c) {
        double a = column[0] * (double) c, b = column[1] * (double) c;
        return new float[]{(float) Math.sin(2 * Math.PI * (a - Math.floor(a) + column[2])),
                (float) Math.sin(2 * Math.PI * (b - Math.floor(b) + column[3]))};
    }

    /** The hanging blocks' swing for a setting (0..2, 1 = 100) and the rain level; 0 when off. */
    public static float hangAmplitude(float setting, float rain) {
        float s = setting > 0 && Float.isFinite(setting) ? setting : 0;
        float r = rain > 0 ? Math.min(rain, 1) : 0;
        return HANG_AMPLITUDE * s * (1 + RAIN_GAIN * r);
    }

    /** The terrain's WindHang: the hanging blocks' swing (0 while the post-processing is off) and their clock (0..1). */
    public static MappedBuffer hang() {
        if (!VisualsConfig.get().enabled) HANG.putFloat(0, 0);
        return HANG;
    }

    public static MappedBuffer phase() {
        return PHASE;
    }

    /** The main wave's phase (radians; the frame recorder's windPhaseE6). */
    public static synchronized double phase0() {
        return phase[0];
    }

    /**
     * The terrain's WindParams: this frame's camera, wrapped; the amplitudes read 0 while the post-processing is off (the frame does
     * not update them then).
     */
    public static MappedBuffer params() {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        PARAMS.putFloat(0, (float) RippleMath.wrap(camera.x));
        PARAMS.putFloat(4, (float) RippleMath.wrap(camera.z));
        if (!VisualsConfig.get().enabled) {
            PARAMS.putFloat(8, 0);
            PARAMS.putFloat(12, 0);
        }
        return PARAMS;
    }
}
