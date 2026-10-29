package net.vulkanmod.visuals.render;

/**
 * 2026-10-29: rainripple.glsl's consts and its slope, mirrored for the tests (RainRippleTest compares the consts): the rings raindrops
 * draw on the open water while it rains (reflection.comp).
 */
public final class RainRippleMath {
    public static final float RAIN_RIPPLE_CELL = 0.8f, RAIN_RIPPLE_CYCLE = 1.1f;
    public static final float RAIN_RIPPLE_RADIUS = 0.5f, RAIN_RIPPLE_WIDTH = 0.06f, RAIN_RIPPLE_SLOPE = 0.6f;
    public static final float RAIN_RIPPLE_SHINE = 0.35f;
    public static final float RAIN_RIPPLE_DENSITY = 0.85f;
    public static final float RAIN_RIPPLE_FADE_NONE = 0.5f, RAIN_RIPPLE_FADE_FULL = 2.0f;
    /** The seconds rainParams.x wraps at (ReflectionUniforms): a whole number of cycles' worth of float precision. */
    public static final double TIME_WRAP = 3600;

    private RainRippleMath() {
    }

    static float cells() {
        return (float) Math.floor(RippleMath.RIPPLE_PERIOD / RAIN_RIPPLE_CELL + 0.5f);
    }

    static float hash(int h, int which) {
        return ((h >>> (which * 10)) & 1023) / 1023f;
    }

    /** rainRippleSlope: {d height / d x, d height / d z} at pos for time (seconds), rain 0..1 and footprint (blocks per pixel). */
    public static float[] slope(float px, float pz, float time, float rain, float footprint) {
        float r = Math.max(0, Math.min(1, rain)) * RAIN_RIPPLE_DENSITY;
        float pixels = footprint > 0.000001f ? RAIN_RIPPLE_WIDTH * 2 / footprint : 1000000f;
        float fade = smoothstep(RAIN_RIPPLE_FADE_NONE, RAIN_RIPPLE_FADE_FULL, pixels);
        float[] s = new float[2];
        if (!(r > 0) || fade <= 0) return s;
        float n = cells();
        for (int layer = 0; layer < 2; layer++) {
            float qx = px / RAIN_RIPPLE_CELL + layer * 0.5f, qz = pz / RAIN_RIPPLE_CELL + layer * 0.5f;
            float bx = (float) Math.floor(qx), bz = (float) Math.floor(qz);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float cx = bx + i, cz = bz + j;
                    float wx = cx - n * (float) Math.floor(cx / n), wz = cz - n * (float) Math.floor(cz / n);
                    int h = WaterMath.rippleHash((int) wx, (int) wz, 7 + layer);
                    if (hash(h, 0) > r) continue;
                    float centreX = cx + 0.2f + 0.6f * hash(h, 1), centreZ = cz + 0.2f + 0.6f * hash(h, 2);
                    double a = time / RAIN_RIPPLE_CYCLE + layer * 0.5 + hash(h, 1) * 7.0 + hash(h, 2) * 3.0;
                    float age = (float) (a - Math.floor(a));
                    float dx = (qx - centreX) * RAIN_RIPPLE_CELL, dz = (qz - centreZ) * RAIN_RIPPLE_CELL;
                    float dist = (float) Math.sqrt(dx * dx + dz * dz);
                    if (!(dist > 0.0001f) || dist > RAIN_RIPPLE_RADIUS + 3 * RAIN_RIPPLE_WIDTH) continue;
                    float x = (dist - age * RAIN_RIPPLE_RADIUS) / RAIN_RIPPLE_WIDTH;
                    float life = (1 - age) * (1 - age);
                    float k = (float) (-2 * x * Math.exp(-x * x)) * life / dist;
                    s[0] += dx * k;
                    s[1] += dz * k;
                }
            }
        }
        s[0] *= RAIN_RIPPLE_SLOPE * fade;
        s[1] *= RAIN_RIPPLE_SLOPE * fade;
        return s;
    }

    /** rainRippleShine: the crest's share of the mirrored light. */
    public static float shine(float[] slope) {
        return Math.min((float) Math.hypot(slope[0], slope[1]) / RAIN_RIPPLE_SLOPE, 1) * RAIN_RIPPLE_SHINE;
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
