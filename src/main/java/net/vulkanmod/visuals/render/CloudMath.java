package net.vulkanmod.visuals.render;

/**
 * Visuals P7h far clouds (design 2026-10-12 P7h 3.1-3.3): clouds.glsl's cloud shape and march, mirrored for the tests (change
 * both together), and the CPU side: the cloud map from vanilla's clouds.png and vanilla's scroll. Positions are relative to the
 * camera (eye), y up, in blocks.
 */
public final class CloudMath {
    /** Vanilla's layer: MAP x MAP clouds.png texels of CLOUD_CELL blocks (PERIOD blocks), the bottom CLOUD_LIFT above the dimension's cloud height, CLOUD_THICKNESS thick, drifting CLOUD_DRIFT blocks per tick along +x, z shifted CLOUD_Z_SHIFT cells. */
    public static final int MAP = 256;
    public static final double PERIOD = MAP * 12.0;
    public static final float CLOUD_CELL = 12, CLOUD_LIFT = 0.33f, CLOUD_THICKNESS = 4, CLOUD_DRIFT = 0.03f, CLOUD_Z_SHIFT = 0.33f;
    /** Design 3.1: the cells blend over CLOUD_ROUND cells either side of their boundary; the top and bottom thin over CLOUD_EDGE blocks; the 0.5 threshold is softened by CLOUD_SOFT. */
    public static final float CLOUD_ROUND = 0.25f, CLOUD_EDGE = 1, CLOUD_SOFT = 0.1f;
    /** Design 3.2: samples every CLOUD_STEP blocks, at least CLOUD_MIN_SAMPLES and at most CLOUD_MAX_SAMPLES; CLOUD_DENSITY per block. */
    public static final float CLOUD_STEP = 6, CLOUD_DENSITY = 0.6f;
    public static final int CLOUD_MIN_SAMPLES = 12, CLOUD_MAX_SAMPLES = 48;
    /** Design 3.2: the light is read CLOUD_LIGHT_NEAR and CLOUD_LIGHT_FAR blocks toward the sun (moon); the shading never goes below CLOUD_SHADE_MIN. */
    public static final float CLOUD_LIGHT_NEAR = 1.5f, CLOUD_LIGHT_FAR = 4, CLOUD_SHADE_K = 1.2f, CLOUD_SHADE_MIN = 0.65f;
    /** Final review: below CLOUD_SHADE_LEVEL of light height the shading fades out (the sun and moon swap at the horizon, so the lit side would jump). */
    public static final float CLOUD_SHADE_LEVEL = 0.1f;
    /** Gate G7h: the cloud colour is dimmed to vanilla's side-face brightness before the highlights are expanded (white x 1.0 expanded 5x and bloomed). */
    public static final float CLOUD_ALBEDO = 0.8f;
    /** Design 3.2: the clouds fade out from CLOUD_FADE_START of the LOD distance; steep rays count as at least CLOUD_MIN_HORIZONTAL level for the LOD cut. */
    public static final float CLOUD_FADE_START = 0.75f, CLOUD_MIN_HORIZONTAL = 0.01f;
    /** The LOD distance in blocks per LOD section render distance unit (LodInputs.lodDistanceSections) (32 sections of 16 blocks). */
    public static final float LOD_BLOCKS_PER_SECTION = 512;

    /** The cloud map: 1 on a cloud cell, else 0; x, z in 0..MAP-1. */
    public interface Map {
        float at(int x, int z);
    }

    /** One march: the light-weighted coverage (times the cloud colour gives the premultiplied colour), the coverage, the coverage-weighted distance. */
    public record March(float light, float coverage, float distance) {
    }

    private CloudMath() {
    }

    private static float wrap(double v) {
        float f = (float) (v - PERIOD * Math.floor(v / PERIOD));
        return f >= PERIOD ? 0 : f;
    }

    /** Design 2: the camera's x, z on the cloud map in blocks: vanilla's drift and z shift, wrapped at PERIOD. */
    public static float[] offset(double cameraX, double cameraZ, int ticks, float partialTick) {
        return new float[]{wrap(cameraX + (ticks + (double) partialTick) * CLOUD_DRIFT), wrap(cameraZ + CLOUD_Z_SHIFT * CLOUD_CELL)};
    }

    /**
     * 2026-11-03: a texel is a cloud where vanilla draws it: rendertype_clouds.fsh discards alpha below 0.1 at vertex alpha 0.8,
     * so texel alpha 32 and up (Stay True's clouds.png fills its gaps with alpha 1, which covered the whole sky).
     */
    public static final int MASK_ALPHA = 32;

    /** clouds.png's ABGR pixels (NativeImage.getPixelRGBA) as RGBA8 bytes: 255 in every channel where the alpha is MASK_ALPHA or more, else 0. */
    public static byte[] mask(int[] abgr) {
        byte[] out = new byte[abgr.length * 4];
        for (int i = 0; i < abgr.length; i++) {
            byte v = (abgr[i] >>> 24) >= MASK_ALPHA ? (byte) 255 : 0;
            out[i * 4] = v;
            out[i * 4 + 1] = v;
            out[i * 4 + 2] = v;
            out[i * 4 + 3] = v;
        }
        return out;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    //GLSL's mix: a(1 - t) + b t
    private static float mix(float a, float b, float t) {
        return a * (1 - t) + b * t;
    }

    private static float mod(float v, float m) {
        return v - m * (float) Math.floor(v / m);
    }

    private static float texel(Map map, float cx, float cz) {
        return map.at((int) mod(cx, MAP), (int) mod(cz, MAP));
    }

    /** Design 3.1: the horizontal cover 0..1 at camera-relative (x, z): the four nearest cells blended by smoothstep around their boundaries. */
    public static float cover(Map map, float offsetX, float offsetZ, float x, float z) {
        float ux = (offsetX + x) / CLOUD_CELL - 0.5f, uz = (offsetZ + z) / CLOUD_CELL - 0.5f;
        float ix = (float) Math.floor(ux), iz = (float) Math.floor(uz);
        float wx = smoothstep(0.5f - CLOUD_ROUND, 0.5f + CLOUD_ROUND, ux - ix), wz = smoothstep(0.5f - CLOUD_ROUND, 0.5f + CLOUD_ROUND, uz - iz);
        float a = texel(map, ix, iz), b = texel(map, ix + 1, iz), c = texel(map, ix, iz + 1), d = texel(map, ix + 1, iz + 1);
        return mix(mix(a, b, wx), mix(c, d, wx), wz);
    }

    /** Design 3.1: the density at camera-relative (x, y, z); base is the layer's bottom relative to the camera. */
    public static float density(Map map, float offsetX, float offsetZ, float base, float thickness, float x, float y, float z) {
        float h = y - base;
        if (h < 0 || h > thickness) return 0;
        float v = smoothstep(0, CLOUD_EDGE, Math.min(h, thickness - h));
        return smoothstep(0.5f - CLOUD_SOFT, 0.5f + CLOUD_SOFT, cover(map, offsetX, offsetZ, x, z) * v);
    }

    /** Design 3.2: the ray's span inside the layer, cut to [0, maxDist] and to the LOD distance horizontally; null when there is none. */
    public static float[] interval(float base, float thickness, float lodDistance, float[] origin, float[] dir, float maxDist) {
        float bottom = base, top = base + thickness;
        float t0 = 0, t1 = maxDist;
        if (Math.abs(dir[1]) < 0.000001f) {
            if (origin[1] < bottom || origin[1] > top) return null;
        } else {
            float ta = (bottom - origin[1]) / dir[1], tb = (top - origin[1]) / dir[1];
            t0 = Math.max(Math.min(ta, tb), 0);
            t1 = Math.min(Math.max(ta, tb), maxDist);
        }
        t1 = Math.min(t1, lodDistance / Math.max((float) Math.sqrt(dir[0] * dir[0] + dir[2] * dir[2]), CLOUD_MIN_HORIZONTAL));
        return t1 > t0 ? new float[]{t0, t1} : null;
    }

    /** Design 3.2: front to back through the layer, CLOUD_STEP apart (12-48 samples), each shaded toward lightDir and faded by its horizontal distance. */
    public static March march(Map map, float offsetX, float offsetZ, float base, float thickness, float lodDistance, float[] origin, float[] dir,
                              float maxDist, float[] lightDir) {
        float[] span = interval(base, thickness, lodDistance, origin, dir, maxDist);
        if (span == null) return new March(0, 0, 0);
        float len = span[1] - span[0];
        int n = (int) Math.max(CLOUD_MIN_SAMPLES, Math.min(CLOUD_MAX_SAMPLES, Math.ceil(len / CLOUD_STEP)));
        float dt = len / n;
        float shadeMin = mix(1, CLOUD_SHADE_MIN, smoothstep(0, CLOUD_SHADE_LEVEL, Math.abs(lightDir[1])));
        float light = 0, trans = 1, dsum = 0, wsum = 0;
        for (int i = 0; i < CLOUD_MAX_SAMPLES; i++) {
            if (i >= n || trans < 0.01f) break;
            float t = span[0] + (i + 0.5f) * dt;
            float px = origin[0] + dir[0] * t, py = origin[1] + dir[1] * t, pz = origin[2] + dir[2] * t;
            float d = density(map, offsetX, offsetZ, base, thickness, px, py, pz);
            if (d <= 0) continue;
            float fade = 1 - smoothstep(CLOUD_FADE_START, 1, (float) Math.sqrt(px * px + pz * pz) / lodDistance);
            float a = (1 - (float) Math.exp(-d * CLOUD_DENSITY * dt)) * fade;
            float near = density(map, offsetX, offsetZ, base, thickness, px + lightDir[0] * CLOUD_LIGHT_NEAR, py + lightDir[1] * CLOUD_LIGHT_NEAR,
                    pz + lightDir[2] * CLOUD_LIGHT_NEAR);
            float far = density(map, offsetX, offsetZ, base, thickness, px + lightDir[0] * CLOUD_LIGHT_FAR, py + lightDir[1] * CLOUD_LIGHT_FAR,
                    pz + lightDir[2] * CLOUD_LIGHT_FAR);
            float shade = (float) Math.exp(-(near + far) * CLOUD_SHADE_K);
            float w = a * trans;
            light += w * mix(shadeMin, 1, shade);
            dsum += w * t;
            wsum += w;
            trans *= 1 - a;
        }
        return new March(light, 1 - trans, wsum > 0 ? dsum / wsum : 0);
    }
}
