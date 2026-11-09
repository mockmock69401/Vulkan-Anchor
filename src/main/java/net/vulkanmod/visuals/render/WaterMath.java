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
    /** 2026-10-29: water.glsl's OVERDRAW_MIN (overdrawnAfterTranslucent). */
    public static final float OVERDRAW_MIN = 0.0058824f;

    public static boolean overdrawnAfterTranslucent(float[] afterTranslucent, float[] colour) {
        for (int i = 0; i < 3; i++) if (Math.abs(afterTranslucent[i] - colour[i]) > OVERDRAW_MIN) return true;
        return false;
    }
    public static final float SUN_HALF_SIZE = 0.084f, MOON_HALF_SIZE = 0.06f, DISC_EDGE = 0.002f;
    public static final float DISC_RAIN_HIDE = 1.25f;
    public static final float MOON_DISC_LUMINANCE = 0.6f, MOON_RED = 0.92f, MOON_GREEN = 0.98f, MOON_BLUE = 1.12f;
    public static final float GLINT_POWER = 800.0f, GLINT_PEAK = 0.5f;
    /** 2026-11-09 (session 5): the round discs (water.glsl's shape section; CelestialUniforms writes the slot). */
    public static final float CELESTIAL_ROUND = 1.0f, SUN_ROUND_RADIUS = 0.084f, MOON_ROUND_RADIUS = 0.046f, SUN_LIMB_DARKENING = 0.6f;
    public static final float MOON_TERMINATOR = 0.06f, MOON_EARTHSHINE = 0.02f, MOON_MARIA = 0.35f, MOON_MARIA_CELLS = 2.5f;
    public static final int MOON_MARIA_SEED = 9;
    public static final float VANILLA_MOON_MASK = 0.056f;
    public static final float REFLECT_MIN_UP = 0.02f;
    public static final float HIT_CLEARANCE = 0.1f, HIT_CLEARANCE_SHARE = 0.002f;
    public static final float COVER_LOW = 0.3f, COVER_HIGH = 0.9f;

    // ---- reflection.comp ----
    public static final int REFLECT_STEPS = 24, REFLECT_REFINE = 5;
    public static final float REFLECT_STEP_POWER = 1.6f;
    public static final float REFLECT_RAY_LENGTH = 100000.0f, REFLECT_NEAR = 0.06f;
    public static final float REFLECT_THICK_MIN = 0.5f, REFLECT_THICK_SHARE = 0.03f, REFLECT_STEP_CAP = 0.25f;
    public static final float REFLECT_EDGE_FADE = 0.05f, REFLECT_MIN_MARCH = 0.025f;
    public static final float REFRACT_BLOCKS = 0.6f, REFRACT_MAX_PIXELS = 24.0f, REFRACT_SAME_DEPTH = 0.08f;
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

    /** water.glsl discPlane: dir in the gnomonic plane facing centre (along the rotation axis, along the path), or null behind. */
    public static float[] discPlane(float[] dir, float[] centre) {
        float c = dot(dir, centre);
        if (!(c > 0)) return null;
        float[] axis = {-centre[0] * centre[2], -centre[1] * centre[2], 1 - centre[2] * centre[2]};
        float al = length(axis);
        axis = al > 0.0001f ? scale(axis, 1 / al) : new float[]{1, 0, 0};
        float[] side = cross(axis, centre);
        return new float[]{dot(dir, axis) / c, dot(dir, side) / c};
    }

    public static float discCover(float[] dir, float[] centre, float h) {
        float[] uv = discPlane(dir, centre);
        if (uv == null) return 0;
        float m = Math.max(Math.abs(uv[0]), Math.abs(uv[1]));
        return 1 - smoothstep(h - DISC_EDGE, h + DISC_EDGE, m);
    }

    // ---- 2026-11-09 (session 5): the discs' shape (celestial: CelestialUniforms' slot, x the shape, y the moon phase) ----

    public static boolean celestialRound(float[] celestial) {
        return celestial != null && celestial[0] > CELESTIAL_ROUND * 0.5f;
    }

    static float roundCover(float[] uv, float r) {
        return 1 - smoothstep(r - DISC_EDGE, r + DISC_EDGE, (float) Math.hypot(uv[0], uv[1]));
    }

    public static float sunShape(float[] dir, float[] centre, float[] celestial) {
        if (!celestialRound(celestial)) return discCover(dir, centre, SUN_HALF_SIZE);
        float[] uv = discPlane(dir, centre);
        if (uv == null) return 0;
        float r = SUN_ROUND_RADIUS;
        float e = Math.min((float) Math.hypot(uv[0], uv[1]) / r, 1);
        float mu = (float) Math.sqrt(Math.max(1 - e * e, 0));
        return roundCover(uv, r) * (1 - SUN_LIMB_DARKENING * (1 - mu)) / (1 - SUN_LIMB_DARKENING / 3);
    }

    public static float moonLit(float[] celestial) {
        if (!celestialRound(celestial)) return 1;
        return 0.5f + 0.5f * (float) Math.cos(Math.max(0, Math.min(celestial[1], 8)) * 0.7853982f);
    }

    static float moonCorner(float cx, float cy) {
        int x = (int) cx, y = (int) cy;
        int h = (x * 73856093) ^ (y * 19349663) ^ (MOON_MARIA_SEED * 83492791);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777215f;
    }

    public static float moonNoise(float x, float y) {
        float ix = (float) Math.floor(x), iy = (float) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        float a = moonCorner(ix, iy), b = moonCorner(ix + 1, iy), c = moonCorner(ix, iy + 1), d = moonCorner(ix + 1, iy + 1);
        float ab = a + (b - a) * ux, cd = c + (d - c) * ux;
        return ab + (cd - ab) * uy;
    }

    public static float moonShape(float[] dir, float[] centre, float[] celestial) {
        if (!celestialRound(celestial)) return discCover(dir, centre, MOON_HALF_SIZE);
        float[] uv = discPlane(dir, centre);
        if (uv == null) return 0;
        float r = MOON_ROUND_RADIUS;
        float cover = roundCover(uv, r);
        if (!(cover > 0)) return 0;
        float ex = uv[0] / r, ey = uv[1] / r;
        float l = (float) Math.hypot(ex, ey);
        if (l > 1) {
            ex /= l;
            ey /= l;
        }
        float z = (float) Math.sqrt(Math.max(1 - ex * ex - ey * ey, 0));
        float phase = Math.max(0, Math.min(celestial[1], 8));
        float phi = phase * 0.7853982f;
        float lit = smoothstep(-MOON_TERMINATOR, MOON_TERMINATOR, ex * (float) Math.sin(phi) + z * (float) Math.cos(phi));
        float maria = 1 - MOON_MARIA * smoothstep(0.45f, 0.75f, moonNoise(ex * MOON_MARIA_CELLS + 3.7f, ey * MOON_MARIA_CELLS + 1.9f));
        return cover * (MOON_EARTHSHINE + (1 - MOON_EARTHSHINE) * lit) * maria;
    }

    public static float vanillaMoonKept(Atmosphere a, float[] dir, float[] celestial) {
        if (!celestialRound(celestial)) return 1;
        return 1 - discCover(dir, moonCentre(a), VANILLA_MOON_MASK);
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

    public static float[] sunDisc(Atmosphere a, float[] dir, float[] celestial) {
        return scale(sunRadiance(a), sunShape(dir, a.sun(), celestial));
    }

    public static float[] moonDisc(Atmosphere a, float[] dir, float[] celestial) {
        if (!celestialRound(celestial)) return new float[3];
        return scale(moonRadiance(a), moonShape(dir, moonCentre(a), celestial));
    }

    public static float[] glintOf(float[] radiance, float[] r, float[] centre, float sharp, float lit, float ripple) {
        float[] mirrored = scale(radiance, sharp);
        float k = ripple > 0 ? Math.min(ripple, 1) : 0;
        if (k <= 0) return mirrored;
        float[] lobe = scale(radiance, (float) Math.pow(Math.max(dot(r, centre), 0), GLINT_POWER) * GLINT_PEAK * lit);
        return new float[]{mirrored[0] + (lobe[0] - mirrored[0]) * k, mirrored[1] + (lobe[1] - mirrored[1]) * k, mirrored[2] + (lobe[2] - mirrored[2]) * k};
    }

    private static float[] moonCentre(Atmosphere a) {
        return new float[]{-a.sun()[0], -a.sun()[1], -a.sun()[2]};
    }

    /** The sun's disc/glint times the sun visibility; the moon's unshadowed (the shadow maps are the sun's). celestial: the shape slot. */
    public static float[] discGlint(Atmosphere a, float[] r, float ripple, float sunVisibility, float[] celestial) {
        float v = sunVisibility > 0 ? Math.min(sunVisibility, 1) : 0;
        float[] moon = moonCentre(a);
        return add(scale(glintOf(sunRadiance(a), r, a.sun(), sunShape(r, a.sun(), celestial), 1, ripple), v),
                glintOf(moonRadiance(a), r, moon, moonShape(r, moon, celestial), moonLit(celestial), ripple));
    }

    /**
     * The reflected sky: sky colour, the glow and the discs' glints. 2026-11-09: sunOpen 0..1 (reflection.comp: 0 where the march saw
     * the ray pass behind ground beyond the water, else 1 - the hit's weight) takes the glow and both glints; the sky colour stays.
     */
    public static float[] reflectedSky(Atmosphere a, float[] r, float ripple, float sunVisibility, float sunOpen, float[] celestial) {
        float open = sunOpen > 0 ? Math.min(sunOpen, 1) : 0;
        return add(AtmosphereMath.skyColour(a, r), scale(add(AtmosphereMath.sunGlow(a, r), discGlint(a, r, ripple, sunVisibility, celestial)), open));
    }

    public static float[] reflectFallback(float[] sky, float[] own, float skyAccess) {
        float s = skyAccess > 0 ? Math.min(skyAccess, 1) : 0;
        return new float[]{own[0] + (sky[0] - own[0]) * s, own[1] + (sky[1] - own[1]) * s, own[2] + (sky[2] - own[2]) * s};
    }

    /**
     * reflection.comp's colour for a missed ray, without the clouds: the reflected sky hazed along the view to the water (dist blocks,
     * haze amount), then the fallback by the water's sky access; for the tests.
     */
    public static float[] missedRay(Atmosphere a, float[] view, float dist, float amount, float[] r, float ripple, float sunVisibility, float sunOpen,
                                    float[] own, float skyAccess) {
        return missedRay(a, view, dist, amount, r, ripple, sunVisibility, sunOpen, own, skyAccess, SQUARE);
    }

    /** The square discs' shape slot (vanilla's squares, no aurora): what the composite and the reflection had before session 5. */
    public static final float[] SQUARE = new float[4];

    /** missedRay with the discs' shape slot and no aurora. */
    public static float[] missedRay(Atmosphere a, float[] view, float dist, float amount, float[] r, float ripple, float sunVisibility, float sunOpen,
                                    float[] own, float skyAccess, float[] celestial) {
        return reflectFallback(AtmosphereMath.applyHaze(a, reflectedSky(a, r, ripple, sunVisibility, sunOpen, celestial), view, dist, amount), own, skyAccess);
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

    /** The value -1..1 at lattice corner (ix, iz), wrapped into nx, nz cells. */
    public static float rippleCorner(float ix, float iz, float nx, float nz, int seed) {
        float wx = ix - nx * (float) Math.floor(ix / nx), wz = iz - nz * (float) Math.floor(iz / nz);
        return (rippleHash((int) wx, (int) wz, seed) & 0xFFFFFF) / 16777215f * 2 - 1;
    }

    /** The value noise's gradient at (x, z) in lattice units, repeating every nx, nz cells. */
    public static float[] rippleGradient(float x, float z, float nx, float nz, int seed) {
        float ix = (float) Math.floor(x), iz = (float) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        float ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10), uz = fz * fz * fz * (fz * (fz * 6 - 15) + 10);
        float dux = 30 * fx * fx * (fx * (fx - 2) + 1), duz = 30 * fz * fz * (fz * (fz - 2) + 1);
        float a = rippleCorner(ix, iz, nx, nz, seed), b = rippleCorner(ix + 1, iz, nx, nz, seed);
        float c = rippleCorner(ix, iz + 1, nx, nz, seed), d = rippleCorner(ix + 1, iz + 1, nx, nz, seed);
        float k = a - b - c + d;
        return new float[]{dux * (b - a + k * uz), duz * (c - a + k * ux)};
    }

    /** The whole number of cells (at least 1) of span blocks in one period. */
    public static float rippleCells(float span) {
        return Math.max((float) Math.floor(RippleMath.RIPPLE_PERIOD / span + 0.5f), 1);
    }

    /** The density setting clamped to RIPPLE_DENSITY_MIN..MAX; 1 for NaN. */
    public static float rippleDensity(float density) {
        return density >= RippleMath.RIPPLE_DENSITY_MIN ? Math.min(density, RippleMath.RIPPLE_DENSITY_MAX)
                : (density < RippleMath.RIPPLE_DENSITY_MIN ? RippleMath.RIPPLE_DENSITY_MIN : 1);
    }

    public static float rippleFade(float cell, float footprint) {
        float pixels = footprint > 0.000001f ? cell / footprint : 1000000f;
        return smoothstep(RippleMath.RIPPLE_FADE_NONE, RippleMath.RIPPLE_FADE_FULL, pixels);
    }

    /** water.glsl's rippleOctave: the slope {d h / d x, d h / d z} of octave k at (px, pz) (world less the flow); density rippleDensity's. */
    public static float[] rippleOctave(int k, float px, float pz, float density, float footprint) {
        float u = RippleMath.DIRS[k][0], v = RippleMath.DIRS[k][1];
        float m = (float) Math.sqrt(u * u + v * v);
        float cell = RippleMath.CELLS[k];
        float nx = rippleCells(cell * m / density), ny = rippleCells(cell * RippleMath.STRETCHES[k] * m / density);
        float qx = (u * px + v * pz) * nx / RippleMath.RIPPLE_PERIOD, qz = (u * pz - v * px) * ny / RippleMath.RIPPLE_PERIOD;
        float[] g = rippleGradient(qx, qz, nx, ny, k + 1);
        float w = RippleMath.STEEPS[k] * rippleFade(RippleMath.RIPPLE_PERIOD / (nx * m), footprint);
        float gy = g[1] * (ny / nx);
        return new float[]{(g[0] * u - gy * v) * (w / m), (g[0] * v + gy * u) * (w / m)};
    }

    /** The cell (blocks) across octave k's crests at the density (rippleDensity's), as the shader rounds it. */
    public static float rippleCellAcross(int k, float density) {
        float u = RippleMath.DIRS[k][0], v = RippleMath.DIRS[k][1];
        float m = (float) Math.sqrt(u * u + v * v);
        return RippleMath.RIPPLE_PERIOD / (rippleCells(RippleMath.CELLS[k] * m / density) * m);
    }

    /** water.glsl's rippleNormal: rel the surface's x, z relative to the camera; offsets RippleMath.offsets (slots 11, 12). */
    public static float[] rippleNormal(float relX, float relZ, float[] offsets, float strength, float density, float footprint) {
        float s = strength > 0 ? strength : 0;
        if (s <= 0) return new float[]{0, 1, 0};
        float d = rippleDensity(density);
        float sx = 0, sz = 0;
        for (int k = 0; k < RippleMath.OCTAVES; k++) {
            float[] o = rippleOctave(k, relX + offsets[2 * k], relZ + offsets[2 * k + 1], d, footprint);
            sx += o[0];
            sz += o[1];
        }
        sx *= s;
        sz *= s;
        float[] n = {-sx, 1, -sz};
        return scale(n, 1 / length(n));
    }

    public static float rippleAmount(float strength, float density, float footprint) {
        float s = strength > 0 ? Math.min(strength, 1) : 0;
        return s * rippleFade(RippleMath.RIPPLE_CELL_0 * RippleMath.RIPPLE_STRETCH_0 / rippleDensity(density), footprint);
    }

    // ---- hits ----

    public static float reflectHitClearance(float dist) {
        return HIT_CLEARANCE + HIT_CLEARANCE_SHARE * Math.max(dist, 0);
    }

    public static boolean reflectAcceptsHit(float hitMark, float heightAbove, float dist) {
        boolean translucent = Math.abs(hitMark) >= REFLECT_MARK_MIN && hitMark >= -WATER_REFLECTION;
        return !translucent && heightAbove > reflectHitClearance(dist);
    }

    public static float reflectCoverWeight(float covered) {
        return smoothstep(COVER_LOW, COVER_HIGH, covered);
    }

    /**
     * 2026-11-09: whether a march step says the sky along the reflected ray is blocked: the ray (view z rayZ) passed behind the surface
     * shown there (sceneZ), which lies beyond the reflecting point (originZ), stands above its plane by more than the hit clearance and
     * is no reflective translucent.
     */
    public static boolean reflectBlocksSky(float hitMark, float heightAbove, float dist, float rayZ, float sceneZ, float originZ) {
        boolean translucent = Math.abs(hitMark) >= REFLECT_MARK_MIN && hitMark >= -WATER_REFLECTION;
        return !translucent && rayZ < sceneZ && sceneZ < originZ && heightAbove > reflectHitClearance(dist);
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
