package net.vulkanmod.visuals.render;

/**
 * 2026-10-29: blockreflect.glsl's consts and functions, mirrored for the tests (BlockReflectionTest compares the consts): the sun's
 * and the moon's highlights on polished and metal blocks (the composite) and their reflections (reflection.comp). Vectors are float[3].
 */
public final class BlockReflectionMath {
    public static final float POLISHED_MARK = -2.0f, METAL_MARK = -4.0f;
    public static final float BLOCK_MARK_LIMIT = -1.5f;
    public static final float POLISHED_F0 = 0.04f, METAL_F0 = 0.45f;
    public static final float POLISHED_WEIGHT = 0.45f, METAL_WEIGHT = 0.55f;
    public static final float POLISHED_ROUGHNESS = 0.15f, METAL_ROUGHNESS = 0.3f;
    public static final float BLOCK_RAY_SPREAD = 0.6f;
    public static final float HIGHLIGHT_SCALE = 0.3f, HIGHLIGHT_TERMINATOR = 0.1f, NDOTV_MIN = 0.35f, HIGHLIGHT_MAX = 3.0f;
    public static final float MOON_HIGHLIGHT = 0.08f;
    public static final float METAL_TINT_MIN = 0.1f;
    public static final float DEBUG_SMOOTH_RED = 0.2f, DEBUG_SMOOTH_GREEN = 1.0f, DEBUG_SMOOTH_BLUE = 0.3f;
    /** The block reflections setting: off, the highlights only, highlights and reflections (Complementary's Off, Low, High). */
    public static final int OFF = 0, HIGHLIGHTS = 1, REFLECTIONS = 2;

    private BlockReflectionMath() {
    }

    public static int blockCodeClass(int code) {
        return code == LightingMath.METAL_ALPHA_CODE ? 2 : (code == LightingMath.POLISHED_ALPHA_CODE ? 1 : 0);
    }

    public static int blockReflectClass(float mark) {
        if (!(mark < BLOCK_MARK_LIMIT)) return 0;
        return mark < 0.5f * (POLISHED_MARK + METAL_MARK) ? 2 : 1;
    }

    public static float blockReflectionMark(int cls, float mode) {
        if (!(mode > 1.5f)) return 0;
        return cls == 2 ? METAL_MARK : (cls == 1 ? POLISHED_MARK : 0);
    }

    public static float[] metalTint(float[] colour) {
        float m = Math.max(colour[0], Math.max(colour[1], colour[2]));
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) t[i] = Math.max(METAL_TINT_MIN, Math.min(1, m > 0.000001f ? colour[i] / m : 1));
        return t;
    }

    public static float[] blockReflectivity(int cls, float cosTheta, float[] tint) {
        float c = Math.max(0, Math.min(1, cosTheta));
        float k = (float) Math.pow(1 - c, 5);
        float[] f = new float[3];
        for (int i = 0; i < 3; i++) {
            if (cls == 2) {
                float f0 = METAL_F0 * tint[i];
                f[i] = METAL_WEIGHT * (f0 + (1 - f0) * k);
            } else if (cls == 1) {
                f[i] = POLISHED_WEIGHT * (POLISHED_F0 + (1 - POLISHED_F0) * k);
            }
        }
        return f;
    }

    public static float blockRoughness(int cls) {
        return cls == 2 ? METAL_ROUGHNESS : POLISHED_ROUGHNESS;
    }

    public static float ggxDistribution(float nDotH, float alpha) {
        float a2 = alpha * alpha;
        float d = nDotH * nDotH * (a2 - 1) + 1;
        return (float) (a2 / (Math.PI * d * d));
    }

    public static float[] blockHighlight(int cls, float[] n, float[] v, float[] l, float[] light, float[] tint) {
        float nl = dot(n, l), nv = dot(n, v);
        float[] out = new float[3];
        if (cls == 0 || !(nl > 0) || !(nv > 0)) return out;
        float[] h = normalize(new float[]{l[0] + v[0], l[1] + v[1], l[2] + v[2]});
        float d = ggxDistribution(Math.max(dot(n, h), 0), blockRoughness(cls));
        float[] f = blockReflectivity(cls, dot(v, h), tint);
        float s = d * HIGHLIGHT_SCALE * smoothstep(0, HIGHLIGHT_TERMINATOR, nl) / Math.max(nv, NDOTV_MIN);
        for (int i = 0; i < 3; i++) out[i] = Math.min(Math.max(light[i], 0) * f[i] * s, HIGHLIGHT_MAX);
        return out;
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float[] normalize(float[] a) {
        float l = (float) Math.sqrt(dot(a, a));
        return new float[]{a[0] / l, a[1] / l, a[2] / l};
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
