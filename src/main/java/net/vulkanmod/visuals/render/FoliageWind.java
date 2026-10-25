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
    /** The longest step (seconds) taken as flow, as RippleClock's. */
    public static final double MAX_STEP = RippleMath.MAX_STEP;

    private static final MappedBuffer PHASE = new MappedBuffer(4 * 4);
    private static final MappedBuffer PARAMS = new MappedBuffer(4 * 4);
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
        double dt = seconds - last;
        last = seconds;
        if (dt > 0 && dt <= MAX_STEP) {
            for (int i = 0; i < 4; i++) phase[i] = wrapAngle(phase[i] + OMEGA[i] * speedFactor * dt);
        }
        float[] a = amplitudes(setting, rain, true);
        for (int i = 0; i < 4; i++) PHASE.putFloat(i * 4, (float) phase[i]);
        PARAMS.putFloat(8, a[0]);
        PARAMS.putFloat(12, a[1]);
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
