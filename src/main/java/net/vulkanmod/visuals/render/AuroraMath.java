package net.vulkanmod.visuals.render;

/**
 * 2026-11-09 (session 5): aurora.glsl's mirror for the tests. Every shader const is a field of the same name here and every function the
 * shader's (AuroraTest compares them). Units and the model: see aurora.glsl's header. The parameters (CelestialUniforms' aurora slot):
 * x the strength, y the time in seconds (wrapped at AURORA_TIME_WRAP), z 1 in the End, w the jitter phase.
 */
public final class AuroraMath {
    public static final float AURORA_TOP = 3.0f;
    public static final int AURORA_STEPS = 12, AURORA_REFLECTION_STEPS = 6;
    public static final float AURORA_REACH = 10.0f, AURORA_EXTINCTION = 12.0f;
    public static final float AURORA_HORIZON_LOW = 0.0f, AURORA_HORIZON_HIGH = 0.22f;
    public static final float AURORA_SCALE = 0.9f, AURORA_STRETCH = 2.5f;
    public static final float AURORA_WARP_SCALE = 0.35f, AURORA_WARP = 2.6f;
    public static final float AURORA_SHARPNESS = 18.0f;
    public static final float AURORA_WARP_DRIFT = 0.01f, AURORA_CREST_DRIFT = 0.02f, AURORA_TIME_WRAP = 25600.0f;
    public static final float AURORA_PERIOD = 256.0f;
    public static final float AURORA_RAY_CELLS = 24.0f, AURORA_RAY_DEPTH = 0.8f, AURORA_RAY_DRIFT = 0.05f;
    /** Session 14 follow-up: the half-size pass fades rays finer than a pixel to their mean (aurora.glsl). */
    public static final float AURORA_RAY_MEAN = 0.5f, AURORA_RAY_ALIAS_LOW = 0.2f, AURORA_RAY_ALIAS_HIGH = 0.4f;
    public static final float AURORA_PATCH_MIN = 0.15f;
    public static final float AURORA_BORDER_LIFT = 0.5f, AURORA_BORDER_EDGE = 0.04f;
    public static final float AURORA_GREEN_SCALE = 0.6f, AURORA_RED_LOW = 1.4f, AURORA_RED_HIGH = 2.2f, AURORA_RED_SCALE = 0.8f, AURORA_RED_SHARE = 0.2f;
    public static final float AURORA_FRINGE_SCALE = 0.07f, AURORA_FRINGE_SHARE = 0.35f;
    public static final float AURORA_GREEN_R = 0.12f, AURORA_GREEN_G = 1.0f, AURORA_GREEN_B = 0.62f;
    public static final float AURORA_RED_R = 0.8f, AURORA_RED_G = 0.12f, AURORA_RED_B = 0.55f;
    public static final float AURORA_FRINGE_R = 0.7f, AURORA_FRINGE_G = 0.2f, AURORA_FRINGE_B = 1.0f;
    public static final float AURORA_END_LOW_R = 0.95f, AURORA_END_LOW_G = 0.35f, AURORA_END_LOW_B = 1.0f;
    public static final float AURORA_END_MID_R = 0.6f, AURORA_END_MID_G = 0.25f, AURORA_END_MID_B = 1.0f;
    public static final float AURORA_END_TOP_R = 0.3f, AURORA_END_TOP_G = 0.35f, AURORA_END_TOP_B = 1.0f;
    public static final float AURORA_BRIGHTNESS = 1.6f;
    public static final int AURORA_PRIME_X = 73856093, AURORA_PRIME_Z = 19349663, AURORA_PRIME_SEED = 83492791;

    private AuroraMath() {
    }

    static int auroraHash(float cx, float cy, int seed) {
        float wx = cx - AURORA_PERIOD * (float) Math.floor(cx / AURORA_PERIOD), wy = cy - AURORA_PERIOD * (float) Math.floor(cy / AURORA_PERIOD);
        int x = (int) wx, y = (int) wy;
        int h = (x * AURORA_PRIME_X) ^ (y * AURORA_PRIME_Z) ^ (seed * AURORA_PRIME_SEED);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    /** Two values 0..1 at a corner: the hash's low and high 16 bits. */
    static float[] auroraCorner(float cx, float cy, int seed) {
        int h = auroraHash(cx, cy, seed);
        return new float[]{(h & 0xFFFF) / 65535f, (h >>> 16) / 65535f};
    }

    /** Two channels of 2D value noise 0..1. */
    public static float[] auroraNoise(float x, float y, int seed) {
        float ix = (float) Math.floor(x), iy = (float) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        float[] a = auroraCorner(ix, iy, seed), b = auroraCorner(ix + 1, iy, seed), c = auroraCorner(ix, iy + 1, seed), d = auroraCorner(ix + 1, iy + 1, seed);
        float[] out = new float[2];
        for (int k = 0; k < 2; k++) {
            float ab = a[k] + (b[k] - a[k]) * ux, cd = c[k] + (d[k] - c[k]) * ux;
            out[k] = ab + (cd - ab) * uy;
        }
        return out;
    }

    public static float auroraNoise1(float x, int seed) {
        float i = (float) Math.floor(x), f = x - i;
        float a = auroraCorner(i, 0, seed)[0], b = auroraCorner(i + 1, 0, seed)[0];
        return a + (b - a) * (f * f * (3 - 2 * f));
    }

    /** x the curtain 0..1, y the patch brightness noise, z the border's lift noise, w the coordinate along the arc. */
    public static float[] auroraFootprint(float qx, float qz, float t) {
        float px = qx / AURORA_STRETCH * AURORA_SCALE, pz = qz * AURORA_SCALE;
        float[] w = auroraNoise(px * AURORA_WARP_SCALE + t * AURORA_WARP_DRIFT, pz * AURORA_WARP_SCALE - t * AURORA_WARP_DRIFT, 1);
        float warpedX = px + (w[0] - 0.5f) * AURORA_WARP, warpedZ = pz + (w[1] - 0.5f) * AURORA_WARP;
        float n = auroraNoise(warpedX + t * AURORA_CREST_DRIFT, warpedZ, 3)[0];
        float crest = (float) Math.pow(1 - Math.abs(2 * n - 1), AURORA_SHARPNESS);
        return new float[]{crest, w[0], w[1], warpedX};
    }

    public static float[] auroraColour(float h, float border, boolean end) {
        return auroraColour(h, border, end, new float[]{1, 1, 1}, new float[]{1, 1, 1}, new float[]{1, 1, 1});
    }

    /** 2026-11-10 (session 8): each colour times its tint (CelestialUniforms' AURORA_TINTS: lower fringe, main body, top). */
    public static float[] auroraColour(float h, float border, boolean end, float[] tintLow, float[] tintMid, float[] tintTop) {
        float above = h - border;
        float bottom = smoothstep(-AURORA_BORDER_EDGE, AURORA_BORDER_EDGE, above);
        float a = Math.max(above, 0);
        float green = (float) Math.exp(-a / AURORA_GREEN_SCALE);
        float red = AURORA_RED_SHARE * smoothstep(AURORA_RED_LOW, AURORA_RED_HIGH, h) * (float) Math.exp(-Math.max(h - AURORA_RED_HIGH, 0) / AURORA_RED_SCALE);
        float fringe = AURORA_FRINGE_SHARE * (float) Math.exp(-a / AURORA_FRINGE_SCALE);
        float[] low = end ? new float[]{AURORA_END_LOW_R, AURORA_END_LOW_G, AURORA_END_LOW_B} : new float[]{AURORA_FRINGE_R, AURORA_FRINGE_G, AURORA_FRINGE_B};
        float[] mid = end ? new float[]{AURORA_END_MID_R, AURORA_END_MID_G, AURORA_END_MID_B} : new float[]{AURORA_GREEN_R, AURORA_GREEN_G, AURORA_GREEN_B};
        float[] top = end ? new float[]{AURORA_END_TOP_R, AURORA_END_TOP_G, AURORA_END_TOP_B} : new float[]{AURORA_RED_R, AURORA_RED_G, AURORA_RED_B};
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = (mid[i] * tintMid[i] * green + top[i] * tintTop[i] * red + low[i] * tintLow[i] * fringe) * bottom;
        return out;
    }

    /** aurora.glsl's aurora: the light along dir (unit, world), params the aurora slot, steps samples, jitter 0..1. */
    public static float[] aurora(float[] dir, float[] params, int steps, float jitter) {
        return aurora(dir, params, new float[]{1, 1, 1}, new float[]{1, 1, 1}, new float[]{1, 1, 1}, steps, jitter);
    }

    /** 2026-11-10 (session 8): with the colour tints (lower, main, upper; see auroraColour). */
    public static float[] aurora(float[] dir, float[] params, float[] tintLow, float[] tintMid, float[] tintTop, int steps, float jitter) {
        if (!(params[0] > 0) || !(dir[1] > AURORA_HORIZON_LOW) || steps < 1) return new float[3];
        float fade = smoothstep(AURORA_HORIZON_LOW, AURORA_HORIZON_HIGH, dir[1]);
        float t = params[1];
        boolean end = params[2] > 0.5f;
        float horizontal = (float) Math.sqrt(dir[0] * dir[0] + dir[2] * dir[2]);
        float enter = 1 / dir[1];
        float leave = AURORA_TOP / dir[1];
        if (horizontal > 0.0001f) leave = Math.min(leave, enter + AURORA_REACH / horizontal);
        float dt = (leave - enter) / steps;
        float j = Math.max(0, Math.min(jitter, 1));
        float[] sum = new float[3];
        for (int i = 0; i < steps; i++) {
            float s = enter + (i + j) * dt;
            float x = dir[0] * s, y = dir[1] * s, z = dir[2] * s;
            float[] f = auroraFootprint(x, z, t);
            if (f[0] < 0.001f) continue;
            float rays = 1 - AURORA_RAY_DEPTH * auroraNoise1(f[3] * AURORA_RAY_CELLS + t * AURORA_RAY_DRIFT, 4);
            float bright = AURORA_PATCH_MIN + (1 - AURORA_PATCH_MIN) * smoothstep(0.25f, 0.75f, f[1]);
            float border = 1 + AURORA_BORDER_LIFT * f[2];
            float[] c = auroraColour(y, border, end, tintLow, tintMid, tintTop);
            float w = f[0] * rays * bright * (float) Math.exp(-s * horizontal / AURORA_EXTINCTION);
            for (int k = 0; k < 3; k++) sum[k] += c[k] * w;
        }
        float g = dt * AURORA_BRIGHTNESS * params[0] * fade;
        return new float[]{sum[0] * g, sum[1] * g, sum[2] * g};
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min((x - e0) / (e1 - e0), 1));
        return t * t * (3 - 2 * t);
    }
}
