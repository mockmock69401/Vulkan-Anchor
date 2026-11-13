package net.vulkanmod.visuals.render;

/**
 * 2026-11-13 (shader roadmap session 19): the Java mirror of heat.glsl (the Nether's heat haze). Every const and function there is here
 * under the same name (DimensionEffectsTest compares them); the noise is GalaxyMath's.
 */
public final class HeatMath {
    public static final float HEAT_NEAR = 4.0f;
    public static final float HEAT_NEAR_FULL = 16.0f;
    public static final float HEAT_REACH = 64.0f;
    public static final float HEAT_SKY_DISTANCE = 256.0f;
    public static final float HEAT_LAVA_LEVEL = 31.0f;
    public static final float HEAT_LAVA_HEIGHT = 12.0f;
    public static final float HEAT_AIR = 0.35f;
    public static final float HEAT_SEA = 0.9f;
    public static final float HEAT_LAVA = 1.0f;
    public static final float HEAT_MAX = 1.5f;
    public static final float HEAT_LAVA_REACH = 0.12f;
    public static final int HEAT_LAVA_TAPS = 4;
    public static final float HEAT_LAVA_DEPTH_LOW = 0.6f;
    public static final float HEAT_LAVA_DEPTH_HIGH = 0.9f;
    public static final float HEAT_NEARER_KEEP = 0.85f;
    public static final float HEAT_CELLS = 24.0f;
    public static final float HEAT_RISE = 1.4f;
    public static final float HEAT_DRIFT = 0.3f;
    public static final float HEAT_PIXELS = 5.0f;
    public static final float HEAT_REFERENCE_HEIGHT = 1440.0f;
    public static final float HEAT_TIME_WRAP = 25600.0f;

    private HeatMath() {
    }

    /** heat.glsl heatLavaWeight. */
    public static float heatLavaWeight(float below, float dist, float lavaDist) {
        float b = clamp(1 - below, 0, 1);
        return b * b * AtmosphereMath.smoothstep(HEAT_LAVA_DEPTH_LOW, HEAT_LAVA_DEPTH_HIGH, dist / Math.max(lavaDist, 0.001f));
    }

    /** heat.glsl heatAmount. */
    public static float heatAmount(float strength, float dist, float camY, float dirY, float lava) {
        if (!(strength > 0)) return 0;
        float far = 1 - (float) Math.exp(-Math.max(dist - HEAT_NEAR, 0) / HEAT_REACH);
        float h0 = Math.max(camY - HEAT_LAVA_LEVEL, 0) / HEAT_LAVA_HEIGHT;
        float h1 = Math.max(camY + dirY * dist - HEAT_LAVA_LEVEL, 0) / HEAT_LAVA_HEIGHT;
        float sea = Math.abs(h1 - h0) < 0.001f ? (float) Math.exp(-h0) : (float) ((Math.exp(-h0) - Math.exp(-h1)) / (h1 - h0));
        float near = AtmosphereMath.smoothstep(HEAT_NEAR, HEAT_NEAR_FULL, dist);
        return strength * Math.min(HEAT_AIR * far + HEAT_SEA * sea * far + HEAT_LAVA * lava * near, HEAT_MAX);
    }

    /** heat.glsl heatOffsetPixels: {x, y} pixels. */
    public static float[] heatOffsetPixels(float[] dir, float t, float heat, float height) {
        float[] x = {dir[0] * HEAT_CELLS + t * HEAT_DRIFT, dir[1] * HEAT_CELLS - t * HEAT_RISE, dir[2] * HEAT_CELLS + 0.7f * t * HEAT_DRIFT};
        float a = GalaxyMath.galaxyNoise(x[0], x[1], x[2]), b = GalaxyMath.galaxyNoise(x[0] + 57, x[1] + 57, x[2] + 57);
        float k = heat * HEAT_PIXELS * height / HEAT_REFERENCE_HEIGHT;
        return new float[]{(a - 0.5f) * 2 * k, (b - 0.5f) * 2 * k};
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
