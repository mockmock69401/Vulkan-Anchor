package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.render.AtmosphereMath.Atmosphere;

/**
 * W6: water maths. {@link #ambient} is forwarded to the composite (slot 11 .y); the rest mirrors water.glsl and reflection.comp for the
 * tests (every shader const is a field of the same name here or, for the RIPPLE_ ones, in RippleMath: WaterTest compares them both
 * ways). Units: W2's scene units, blocks, world unit directions (y up); see water.glsl's header.
 */
public final class WaterMath {
    // ---- Java only ----
    /** ambient(day) at night and in full rain: the water body's in-scatter is half its daylight share of the surface's light. */
    public static final float WATER_NIGHT_AMBIENT = 0.5f;
    /**
     * 2026-10-28 (the user: next to Complementary Reimagined the lakes were opaque): the terrain's WaterAlpha (Uniforms; terrain.fsh
     * scales water's texture opacity by it) is this at the water depth setting's 100% with the visuals on (vanilla's 0.71 -> 0.43).
     */
    public static final float WATER_SURFACE_ALPHA = 0.6f;

    /** The terrain's WaterAlpha: 1 with the visuals off, else from 1 at water depth 0 to WATER_SURFACE_ALPHA at 100% and beyond. */
    public static float surfaceAlpha(boolean on, float waterDepth) {
        if (!on || !(waterDepth > 0)) return 1;
        return 1 - (1 - WATER_SURFACE_ALPHA) * Math.min(waterDepth, 1);
    }

    // ---- water.glsl ----
    public static final float WATER_EXTINCTION_RED = 0.10f, WATER_EXTINCTION_GREEN = 0.035f, WATER_EXTINCTION_BLUE = 0.025f;
    public static final float WATER_BODY_RED = 0.03f, WATER_BODY_GREEN = 0.10f, WATER_BODY_BLUE = 0.22f;
    public static final float WATER_LONGEST = 1024.0f;
    public static final float WATER_F0 = 0.02f, FRESNEL_POWER = 5.0f;
    public static final float WATER_REFLECTION = 1.0f, GLASS_REFLECTION = 0.5f;
    public static final float REFLECT_MARK_MIN = 0.001f;
    public static final float SUN_HALF_SIZE = 0.084f, MOON_HALF_SIZE = 0.06f, DISC_EDGE = 0.002f;
    public static final float DISC_RAIN_HIDE = 1.25f;
    public static final float MOON_DISC_LUMINANCE = 0.6f, MOON_RED = 0.92f, MOON_GREEN = 0.98f, MOON_BLUE = 1.12f;
    public static final float GLINT_POWER = 800.0f, GLINT_PEAK = 0.5f;
    public static final float REFLECT_MIN_UP = 0.02f;
    public static final float HIT_CLEARANCE = 0.1f, HIT_CLEARANCE_SHARE = 0.002f;
    public static final float COVER_LOW = 0.3f, COVER_HIGH = 0.9f;

    // ---- reflection.comp ----
    public static final int REFLECT_STEPS = 24, REFLECT_REFINE = 5;
    public static final float REFLECT_STEP_POWER = 1.6f;
    public static final float REFLECT_RAY_LENGTH = 100000.0f, REFLECT_NEAR = 0.06f;
    public static final float REFLECT_THICK_MIN = 0.5f, REFLECT_THICK_SHARE = 0.03f, REFLECT_STEP_CAP = 0.25f;
    public static final float REFLECT_EDGE_FADE = 0.05f, REFLECT_MIN_MARCH = 0.025f;
    public static final float REFRACT_BLOCKS = 0.25f, REFRACT_MAX_PIXELS = 24.0f, REFRACT_SAME_DEPTH = 0.08f;
    public static final int REFLECT_CLOUD_MIN_SAMPLES = 4, REFLECT_CLOUD_MAX_SAMPLES = 12;
    public static final float REFLECT_CLOUD_STEP = 8.0f;
    public static final float REFLECT_CLOUD_SHADE = 0.7f;
    public static final float DEBUG_WATER_RED = 0.0f, DEBUG_WATER_GREEN = 0.35f, DEBUG_WATER_BLUE = 1.0f;
    public static final float DEBUG_GLASS_RED = 1.0f, DEBUG_GLASS_GREEN = 0.45f, DEBUG_GLASS_BLUE = 0.0f;

    private WaterMath() {
    }

    /**
     * The water body's ambient factor for the composite (slot 11 .y), day 0 night .. 1 day (LightingUniforms passes the day factor with
     * rain): the share of the surface's light the composite gives waterColour. 1 by day, WATER_NIGHT_AMBIENT at night and in full rain.
     */
    public static float ambient(float day) {
        float d = day > 0 ? Math.min(day, 1) : 0;
        return WATER_NIGHT_AMBIENT + (1 - WATER_NIGHT_AMBIENT) * d;
    }

    // ---- water colour ----

    public static float[] waterTransmittance(float len) {
        float l = len > 0 ? Math.min(len, WATER_LONGEST) : 0;
        return new float[]{(float) Math.exp(-WATER_EXTINCTION_RED * l), (float) Math.exp(-WATER_EXTINCTION_GREEN * l), (float) Math.exp(-WATER_EXTINCTION_BLUE * l)};
    }

    public static float[] waterColour(float[] behind, float len, float[] light) {
        float[] t = waterTransmittance(len);
        float[] body = {WATER_BODY_RED, WATER_BODY_GREEN, WATER_BODY_BLUE};
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = behind[i] * t[i] + body[i] * Math.max(light[i], 0) * (1 - t[i]);
        return out;
    }

    // ---- reflectivity ----

    public static float reflectionMark(float kind) {
        return kind > 0 ? WATER_REFLECTION : (kind < 0 ? -GLASS_REFLECTION : 0);
    }

    public static float reflectivity(float mark, float cosTheta) {
        float w = mark > 0 ? Math.min(mark, 1) : (mark < 0 ? Math.min(-mark, 1) : 0);
        float c = cosTheta > 0 ? Math.min(cosTheta, 1) : 0;
        return w * (WATER_F0 + (1 - WATER_F0) * (float) Math.pow(1 - c, FRESNEL_POWER));
    }

    // ---- discs ----

    public static float discCover(float[] dir, float[] centre, float h) {
        float c = dot(dir, centre);
        if (!(c > 0)) return 0;
        float[] axis = {-centre[0] * centre[2], -centre[1] * centre[2], 1 - centre[2] * centre[2]};
        float al = length(axis);
        axis = al > 0.0001f ? scale(axis, 1 / al) : new float[]{1, 0, 0};
        float[] side = cross(axis, centre);
        float u = dot(dir, axis) / c, v = dot(dir, side) / c;
        float m = Math.max(Math.abs(u), Math.abs(v));
        return 1 - smoothstep(h - DISC_EDGE, h + DISC_EDGE, m);
    }

    public static float[] sunRadiance(Atmosphere a) {
        if (!AtmosphereMath.atmosphereSky(a)) return new float[3];
        float[] c = {Math.max(a.sunLight()[0], 0), Math.max(a.sunLight()[1], 0), Math.max(a.sunLight()[2], 0)};
        float l = PostMath.luminance(c[0], c[1], c[2]);
        float[] tint = l > 0.000001f ? scale(c, 1 / l) : new float[]{1, 1, 1};
        float overcast = a.sunLight()[3] > 0 ? Math.min(a.sunLight()[3] * DISC_RAIN_HIDE, 1) : 0;
        return scale(tint, PostMath.SUN_DISC_LUMINANCE * (1 - overcast) * smoothstep(-SUN_HALF_SIZE, SUN_HALF_SIZE, a.sun()[1]));
    }

    public static float[] moonRadiance(Atmosphere a) {
        if (!AtmosphereMath.atmosphereSky(a)) return new float[3];
        float[] tint = {MOON_RED, MOON_GREEN, MOON_BLUE};
        float night = a.moonLight()[3] > 0 ? Math.min(a.moonLight()[3], 1) : 0;
        float overcast = a.sunLight()[3] > 0 ? Math.min(a.sunLight()[3] * DISC_RAIN_HIDE, 1) : 0;
        return scale(tint, MOON_DISC_LUMINANCE / PostMath.luminance(tint[0], tint[1], tint[2]) * night * (1 - overcast)
                * smoothstep(-MOON_HALF_SIZE, MOON_HALF_SIZE, -a.sun()[1]));
    }

    public static float[] sunDisc(Atmosphere a, float[] dir) {
        return scale(sunRadiance(a), discCover(dir, a.sun(), SUN_HALF_SIZE));
    }

    public static float[] glintOf(float[] radiance, float[] r, float[] centre, float h, float ripple) {
        float[] sharp = scale(radiance, discCover(r, centre, h));
        float k = ripple > 0 ? Math.min(ripple, 1) : 0;
        if (k <= 0) return sharp;
        float[] lobe = scale(radiance, (float) Math.pow(Math.max(dot(r, centre), 0), GLINT_POWER) * GLINT_PEAK);
        return new float[]{sharp[0] + (lobe[0] - sharp[0]) * k, sharp[1] + (lobe[1] - sharp[1]) * k, sharp[2] + (lobe[2] - sharp[2]) * k};
    }

    private static float[] moonCentre(Atmosphere a) {
        return new float[]{-a.sun()[0], -a.sun()[1], -a.sun()[2]};
    }

    /** The sun's disc/glint times the sun visibility; the moon's unshadowed (the shadow maps are the sun's). */
    public static float[] discGlint(Atmosphere a, float[] r, float ripple, float sunVisibility) {
        float v = sunVisibility > 0 ? Math.min(sunVisibility, 1) : 0;
        return add(scale(glintOf(sunRadiance(a), r, a.sun(), SUN_HALF_SIZE, ripple), v), glintOf(moonRadiance(a), r, moonCentre(a), MOON_HALF_SIZE, ripple));
    }

    public static float[] reflectedSky(Atmosphere a, float[] r, float ripple, float sunVisibility) {
        return add(add(AtmosphereMath.skyColour(a, r), AtmosphereMath.sunGlow(a, r)), discGlint(a, r, ripple, sunVisibility));
    }

    public static float[] reflectFallback(float[] sky, float[] own, float skyAccess) {
        float s = skyAccess > 0 ? Math.min(skyAccess, 1) : 0;
        return new float[]{own[0] + (sky[0] - own[0]) * s, own[1] + (sky[1] - own[1]) * s, own[2] + (sky[2] - own[2]) * s};
    }

    /**
     * reflection.comp's colour for a missed ray, without the clouds: the reflected sky hazed along the view to the water (dist blocks,
     * haze amount), then the fallback by the water's sky access; for the tests.
     */
    public static float[] missedRay(Atmosphere a, float[] view, float dist, float amount, float[] r, float ripple, float sunVisibility, float[] own,
                                    float skyAccess) {
        return reflectFallback(AtmosphereMath.applyHaze(a, reflectedSky(a, r, ripple, sunVisibility), view, dist, amount), own, skyAccess);
    }

    // ---- ripples ----

    public static int rippleHash(int x, int z, int seed) {
        int h = (x * RippleMath.RIPPLE_PRIME_X) ^ (z * RippleMath.RIPPLE_PRIME_Z) ^ (seed * RippleMath.RIPPLE_PRIME_SEED);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    public static float rippleCorner(float ix, float iz, float n, int seed) {
        float wx = ix - n * (float) Math.floor(ix / n), wz = iz - n * (float) Math.floor(iz / n);
        return (rippleHash((int) wx, (int) wz, seed) & 0xFFFFFF) / 16777215f * 2 - 1;
    }

    /** The value noise's gradient at (x, z) in lattice units, repeating every n cells. */
    public static float[] rippleGradient(float x, float z, float n, int seed) {
        float ix = (float) Math.floor(x), iz = (float) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        float ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10), uz = fz * fz * fz * (fz * (fz * 6 - 15) + 10);
        float dux = 30 * fx * fx * (fx * (fx - 2) + 1), duz = 30 * fz * fz * (fz * (fz - 2) + 1);
        float a = rippleCorner(ix, iz, n, seed), b = rippleCorner(ix + 1, iz, n, seed);
        float c = rippleCorner(ix, iz + 1, n, seed), d = rippleCorner(ix + 1, iz + 1, n, seed);
        float k = a - b - c + d;
        return new float[]{dux * (b - a + k * uz), duz * (c - a + k * ux)};
    }

    public static float rippleCells(float cell) {
        return (float) Math.floor(RippleMath.RIPPLE_PERIOD / cell + 0.5f);
    }

    public static float rippleFade(float cell, float footprint) {
        float pixels = footprint > 0.000001f ? cell / footprint : 1000000f;
        return smoothstep(RippleMath.RIPPLE_FADE_NONE, RippleMath.RIPPLE_FADE_FULL, pixels);
    }

    /** water.glsl's rippleNormal: rel the surface's x, z relative to the camera; offsets RippleMath.offsets (slots 11, 12). */
    public static float[] rippleNormal(float relX, float relZ, float[] offsets, float strength, float footprint) {
        float s = strength > 0 ? strength : 0;
        if (s <= 0) return new float[]{0, 1, 0};
        float sx = 0, sz = 0;
        for (int k = 0; k < RippleMath.OCTAVES; k++) {
            float cell = RippleMath.CELLS[k];
            float[] g = rippleGradient((relX + offsets[2 * k]) / cell, (relZ + offsets[2 * k + 1]) / cell, rippleCells(cell), k + 1);
            float w = RippleMath.HEIGHTS[k] / cell * rippleFade(cell, footprint);
            sx += g[0] * w;
            sz += g[1] * w;
        }
        sx *= s;
        sz *= s;
        float[] n = {-sx, 1, -sz};
        return scale(n, 1 / length(n));
    }

    public static float rippleAmount(float strength, float footprint) {
        float s = strength > 0 ? Math.min(strength, 1) : 0;
        return s * rippleFade(RippleMath.RIPPLE_CELL_0, footprint);
    }

    // ---- hits ----

    public static float reflectHitClearance(float dist) {
        return HIT_CLEARANCE + HIT_CLEARANCE_SHARE * Math.max(dist, 0);
    }

    public static boolean reflectAcceptsHit(float hitMark, float heightAbove, float dist) {
        return !(Math.abs(hitMark) >= REFLECT_MARK_MIN) && heightAbove > reflectHitClearance(dist);
    }

    public static float reflectCoverWeight(float covered) {
        return smoothstep(COVER_LOW, COVER_HIGH, covered);
    }

    // ---- the march's thickness (reflection.comp) ----

    public static float marchThickness(float sceneZ, float stepZ) {
        return Math.max(REFLECT_THICK_MIN, REFLECT_THICK_SHARE * Math.abs(sceneZ)) + Math.min(Math.abs(stepZ), REFLECT_STEP_CAP * Math.abs(sceneZ));
    }

    /** bracketZ: the ray's depth change across the bracket left after the refinement. */
    public static float refinedThickness(float sceneZ, float bracketZ) {
        return Math.max(REFLECT_THICK_MIN, REFLECT_THICK_SHARE * Math.abs(sceneZ)) + Math.min(Math.abs(bracketZ), REFLECT_STEP_CAP * Math.abs(sceneZ));
    }

    public static int reflectCloudSamples(float len) {
        float n = (float) Math.ceil(len / REFLECT_CLOUD_STEP);
        return (int) (Float.isNaN(n) ? REFLECT_CLOUD_MIN_SAMPLES : Math.max(REFLECT_CLOUD_MIN_SAMPLES, Math.min(REFLECT_CLOUD_MAX_SAMPLES, n)));
    }

    public static boolean marchBehind(float rayZ, float sceneZ, float thick) {
        return rayZ < sceneZ && rayZ > sceneZ - thick;
    }

    // ---- helpers ----

    static float smoothstep(float e0, float e1, float x) {
        float t = (x - e0) / (e1 - e0);
        t = Float.isNaN(t) ? 0 : Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float length(float[] a) {
        return (float) Math.sqrt(dot(a, a));
    }

    static float[] scale(float[] a, float s) {
        return new float[]{a[0] * s, a[1] * s, a[2] * s};
    }

    static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
