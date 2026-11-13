package net.vulkanmod.visuals.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 2026-11-13 (shader roadmap session 20, the user after AstraLex: "수중에서의 빛 굴절"): Caustics, the sunlight the rippled water surface
 * focuses onto what lies under it (caustics.glsl, in the composite). Mirrors caustics.glsl's constants and functions (CausticTest compares
 * them) and writes the composite's causticParams (SLOT): x the strength (setting / 100; 0 = off: the composite as before), y the clock
 * (the ripples' flow seconds, wrapped at TIME_PERIOD), zw the camera's x, z wrapped at PERIOD.
 */
public final class CausticMath {
    public static final int SLOT = 71;
    /** The setting's range (percent). */
    public static final int STRENGTH_MAX = 200;
    public static final int WAVES = 6;
    public static final float PERIOD = 240f, TIME_PERIOD = 120f;
    public static final float[][] K = {{99, 14}, {77, 83}, {-17, 86}, {-91, 79}, {-85, -42}, {13, -107}};
    public static final float[] CYCLES = {19, 23, 16, 27, 21, 25};
    public static final float[] SLOPE = {0.17f, 0.15f, 0.14f, 0.13f, 0.14f, 0.12f};
    public static final float ETA = 0.25f, SOFT = 0.08f, MAX = 5.0f, DEPTH_IN = 0.75f, DEPTH_MAX = 12.0f;
    public static final float FADE_NONE = 3.0f, FADE_FULL = 10.0f, SHORTEST = 1.99f, WATER_IOR = 1.333f, BEHIND_MAX = 1000.0f;
    public static final int MEAN_COUNT = 7;
    public static final float MEAN_STEP = 2.0f;
    public static final float[] MEAN = {1.0f, 1.113f, 1.648f, 2.082f, 2.029f, 1.822f, 1.585f};

    /** The ripples' flow seconds (VisualsRenderer sets it once a frame). */
    private static volatile double clock;

    private CausticMath() {
    }

    public static void setClock(double seconds) {
        clock = seconds;
    }

    /** causticParams for a setting (percent), the flow seconds and the camera's x, z: all 0 when off. */
    public static float[] params(int setting, double seconds, double cameraX, double cameraZ) {
        float s = Math.max(0, Math.min(STRENGTH_MAX, setting)) / 100f;
        if (!(s > 0)) return new float[4];
        double t = Double.isFinite(seconds) ? seconds - TIME_PERIOD * Math.floor(seconds / TIME_PERIOD) : 0;
        return new float[]{s, (float) t, (float) RippleMath.wrap(cameraX), (float) RippleMath.wrap(cameraZ)};
    }

    /** This frame's causticParams for the setting. */
    public static float[] params(int setting) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return params(setting, clock, camera.x, camera.z);
    }

    /** caustics.glsl causticGain: the light's gain 1/|det J| at surface point (sx, sz) for a floor depth blocks down at time t. */
    public static float gain(float sx, float sz, float depth, float t) {
        float h00 = 0, h01 = 0, h11 = 0;
        for (int i = 0; i < WAVES; i++) {
            float kx = K[i][0] * (6.2831853f / PERIOD), kz = K[i][1] * (6.2831853f / PERIOD);
            float phase = kx * sx + kz * sz - CYCLES[i] * (6.2831853f / TIME_PERIOD) * t;
            float c = -SLOPE[i] / (float) Math.hypot(kx, kz) * (float) Math.sin(phase);
            h00 += c * kx * kx;
            h01 += c * kx * kz;
            h11 += c * kz * kz;
        }
        float d = Math.min(depth, DEPTH_MAX);
        float f = d * ETA;
        float det = (1 + f * h00) * (1 + f * h11) - f * h01 * f * h01;
        return Math.min(1 / (Math.abs(det) + SOFT), MAX) * (1 + SOFT) / mean(d);
    }

    /** causticMean. */
    public static float mean(float depth) {
        float x = Math.max(0, Math.min(depth / MEAN_STEP, MEAN_COUNT - 1));
        int i = Math.min((int) x, MEAN_COUNT - 2);
        return MEAN[i] + (MEAN[i + 1] - MEAN[i]) * (x - i);
    }
}
