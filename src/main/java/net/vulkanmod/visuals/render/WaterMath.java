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
    public static final float CELESTIAL_ROUND = 1.0f, SUN_ROUND_RADIUS = 0.06f, MOON_ROUND_RADIUS = 0.056f, SUN_LIMB_DARKENING = 0.6f;
    /** 2026-11-11 (session 10 follow-up): the round sun keeps session 5's light at any size (water.glsl sunShape). */
    public static final float SUN_FLUX_RADIUS = 0.084f, SUN_BRIGHTNESS_MAX = 16.0f;
    public static final float MOON_TERMINATOR = 0.06f, MOON_EARTHSHINE = 0.02f;
    public static final float VANILLA_MOON_MASK = 0.056f;
    /** 2026-11-10 (session 10): the round moon's face (water.glsl's face section: maria, craters, rays). */
    public static final float MOON_MARIA = 0.36f, MOON_MOTTLE = 0.16f;
    public static final int MOON_MARIA_SEED = 9;
    public static final float MOON_CRATER_DEPTH = 0.32f, MOON_CRATER_RIM = 0.10f, MOON_CRATER_RIM_WIDTH = 0.45f;
    public static final float MOON_CRATER_CELL = 0.16f, MOON_CRATER_SHARE = 0.45f, MOON_CRATER_MIN = 0.022f, MOON_CRATER_MAX = 0.075f;
    public static final int MOON_CRATER_SEED = 41;
    public static final float MOON_RIM_ALBEDO = 0.03f;
    public static final int MOON_RAY_BINS = 20, MOON_RAY_SEED = 31;
    public static final float MOON_RELIEF_MAX = 1.8f, MOON_NIGHT_REACH = 0.25f, MOON_LIMB = 0.985f, MOON_PLAIN_CELLS = 2.5f;
    public static final int MOON_MARE_COUNT = 17, MOON_CRATER_COUNT = 10;
    /** The maria: xyz the centre on the unit sphere (face space, see water.glsl), w the radius. */
    public static final float[][] MOON_MARE = {
            {0.2312f, -0.5446f, 0.8062f, 0.3f}, {-0.2655f, -0.4695f, 0.8421f, 0.18f}, {-0.5094f, -0.1478f, 0.8478f, 0.21f},
            {-0.8197f, -0.2924f, 0.4925f, 0.13f}, {-0.7618f, 0.1045f, 0.6393f, 0.15f}, {-0.5401f, 0.2588f, 0.8008f, 0.09f},
            {0.2573f, 0.3584f, 0.8974f, 0.16f}, {0.3848f, 0.1736f, 0.9065f, 0.1f}, {0.5749f, 0.4067f, 0.71f, 0.1f},
            {0.7595f, -0.3746f, 0.5318f, 0.26f}, {0.788f, 0.0f, 0.6157f, 0.22f}, {0.4924f, -0.6428f, 0.5868f, 0.15f},
            {0.51f, -0.1392f, 0.8488f, 0.12f}, {-0.068f, -0.225f, 0.972f, 0.08f}, {0.1447f, -0.829f, 0.5401f, 0.08f},
            {-0.1102f, -0.848f, 0.5183f, 0.08f}, {-0.3744f, -0.0349f, 0.9266f, 0.1f}};
    /** The named craters (Tycho, Copernicus, Kepler, Aristarchus, Plato, Clavius, Ptolemaeus, Theophilus, Petavius, Grimaldi): centre, radius. */
    public static final float[][] MOON_CRATER = {
            {0.1395f, 0.682f, 0.7179f, 0.045f}, {0.3372f, -0.1668f, 0.9265f, 0.05f}, {0.6097f, -0.1392f, 0.7803f, 0.03f},
            {0.674f, -0.4019f, 0.6198f, 0.025f}, {0.1014f, -0.7837f, 0.6128f, 0.045f}, {0.1282f, 0.848f, 0.5142f, 0.09f},
            {0.0345f, 0.1564f, 0.9871f, 0.065f}, {-0.4359f, 0.1977f, 0.878f, 0.05f}, {-0.7849f, 0.4226f, 0.4532f, 0.065f},
            {0.9237f, 0.0872f, 0.3732f, 0.06f}};
    /** Their albedo: x the rays, y the dark floor (negative). */
    public static final float[][] MOON_CRATER_LOOK = {
            {0.22f, 0.0f}, {0.12f, 0.0f}, {0.1f, 0.0f}, {0.12f, 0.0f}, {0.0f, -0.35f},
            {0.0f, 0.0f}, {0.0f, 0.0f}, {0.0f, 0.0f}, {0.0f, 0.0f}, {0.0f, -0.3f}};
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

    /** The round sun's and moon's size (celestial z, w; 1 = 100%, an unwritten 0 is 1). */
    public static float sunScale(float[] celestial) {
        return celestial != null && celestial.length > 2 && celestial[2] > 0 ? celestial[2] : 1;
    }

    public static float moonScale(float[] celestial) {
        return celestial != null && celestial.length > 3 && celestial[3] > 0 ? celestial[3] : 1;
    }

    /** The sun disc's half extent: the square's half size, or the round sun's radius at its size. */
    public static float sunExtent(float[] celestial) {
        return celestialRound(celestial) ? SUN_ROUND_RADIUS * sunScale(celestial) : SUN_HALF_SIZE;
    }

    static float roundCover(float[] uv, float r) {
        return 1 - smoothstep(r - DISC_EDGE, r + DISC_EDGE, (float) Math.hypot(uv[0], uv[1]));
    }

    public static float sunShape(float[] dir, float[] centre, float[] celestial) {
        if (!celestialRound(celestial)) return discCover(dir, centre, SUN_HALF_SIZE);
        float[] uv = discPlane(dir, centre);
        if (uv == null) return 0;
        float r = SUN_ROUND_RADIUS * sunScale(celestial);
        float e = Math.min((float) Math.hypot(uv[0], uv[1]) / r, 1);
        float mu = (float) Math.sqrt(Math.max(1 - e * e, 0));
        float flux = Math.min(SUN_FLUX_RADIUS * SUN_FLUX_RADIUS / (r * r), SUN_BRIGHTNESS_MAX);
        return roundCover(uv, r) * (1 - SUN_LIMB_DARKENING * (1 - mu)) / (1 - SUN_LIMB_DARKENING / 3) * flux;
    }

    public static float moonLit(float[] celestial) {
        if (!celestialRound(celestial)) return 1;
        return 0.5f + 0.5f * (float) Math.cos(Math.max(0, Math.min(celestial[1], 8)) * 0.7853982f);
    }

    static float moonHash(int x, int y, int seed) {
        int h = (x * 73856093) ^ (y * 19349663) ^ (seed * 83492791);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777215f;
    }

    public static float moonNoise(float x, float y, int seed) {
        float fx0 = (float) Math.floor(x), fy0 = (float) Math.floor(y);
        int ix = (int) fx0, iy = (int) fy0;
        float fx = x - fx0, fy = y - fy0;
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        float a = moonHash(ix, iy, seed), b = moonHash(ix + 1, iy, seed), c = moonHash(ix, iy + 1, seed), d = moonHash(ix + 1, iy + 1, seed);
        float ab = a + (b - a) * ux, cd = c + (d - c) * ux;
        return ab + (cd - ab) * uy;
    }

    /** One crater (water.glsl moonCrater): adds to slope[0..2] and rim[0]; returns s (distance over radius, 9 behind it). */
    static float moonCrater(float[] p, float[] c, float radius, float strength, float[] slope, float[] rim) {
        float along = dot(p, c);
        float[] d = {p[0] - c[0] * along, p[1] - c[1] * along, p[2] - c[2] * along};
        float dl = (float) Math.sqrt(dot(d, d));
        float s = along > 0 ? dl / radius : 9;
        if (s < 3 && dl > 0.000001f) {
            float o = (s - 1) / MOON_CRATER_RIM_WIDTH;
            float g = s < 1 ? 2 * MOON_CRATER_DEPTH * s : -2 * MOON_CRATER_RIM * o / MOON_CRATER_RIM_WIDTH * (float) Math.exp(-o * o);
            float k = strength * smoothstep(0.05f, 0.3f, c[2]);
            for (int i = 0; i < 3; i++) slope[i] += d[i] * (g * k / dl);
            rim[0] += k * MOON_RIM_ALBEDO * (float) Math.exp(-(s - 1) * (s - 1) * 16);
        }
        return s;
    }

    /** A named crater's rays and halo (water.glsl moonRays). */
    static float moonRays(float[] p, float[] c, float radius, float s) {
        float[] b1 = cross(new float[]{0, 1, 0}, c);
        float bl = (float) Math.sqrt(dot(b1, b1));
        b1 = scale(b1, 1 / bl);
        float[] b2 = cross(c, b1);
        float along = dot(p, c);
        float[] d = {p[0] - c[0] * along, p[1] - c[1] * along, p[2] - c[2] * along};
        float t = ((float) Math.atan2(dot(d, b2), dot(d, b1)) / 6.2831853f + 0.5f) * MOON_RAY_BINS;
        float i = (float) Math.floor(t);
        float f = t - i;
        int i0 = (int) i % MOON_RAY_BINS;
        float h0 = moonHash(i0, 0, MOON_RAY_SEED), h1 = moonHash((i0 + 1) % MOON_RAY_BINS, 0, MOON_RAY_SEED);
        float streak = smoothstep(0.5f, 1, h0 + (h1 - h0) * (f * f * (3 - 2 * f)));
        float halo = s < 3 ? 0.5f * (float) Math.exp(-Math.max(s - 1, 0) * 1.2f) : 0;
        return streak * (float) Math.exp(-Math.sqrt(dot(d, d)) / (10 * radius)) * smoothstep(1, 1.6f, s) + halo;
    }

    /** The sky's moon (the composite: the cratered face). */
    public static float moonShape(float[] dir, float[] centre, float[] celestial) {
        return moonShape(dir, centre, celestial, false);
    }

    /** plain: reflection.comp's MOON_PLAIN_FACE (session 5's plain face: phase, earthshine, value-noise maria). */
    public static float moonShape(float[] dir, float[] centre, float[] celestial, boolean plain) {
        if (!celestialRound(celestial)) return discCover(dir, centre, MOON_HALF_SIZE);
        float[] uv = discPlane(dir, centre);
        if (uv == null) return 0;
        float r = MOON_ROUND_RADIUS * moonScale(celestial);
        float cover = roundCover(uv, r);
        if (!(cover > 0)) return 0;
        float ex = uv[0] / r, ey = uv[1] / r;
        float l = (float) Math.hypot(ex, ey);
        if (l > MOON_LIMB) {
            ex *= MOON_LIMB / l;
            ey *= MOON_LIMB / l;
        }
        if (plain) {
            float z = (float) Math.sqrt(Math.max(1 - ex * ex - ey * ey, 0));
            float phi = Math.max(0, Math.min(celestial[1], 8)) * 0.7853982f;
            float lit = smoothstep(-MOON_TERMINATOR, MOON_TERMINATOR, ex * (float) Math.sin(phi) + z * (float) Math.cos(phi));
            float maria = 1 - MOON_MARIA * smoothstep(0.45f, 0.75f, moonNoise(ex * MOON_PLAIN_CELLS + 3.7f, ey * MOON_PLAIN_CELLS + 1.9f, MOON_MARIA_SEED));
            return Math.min(cover * maria * (MOON_EARTHSHINE + (1 - MOON_EARTHSHINE) * lit), 1);
        }
        return Math.min(cover * moonFace(ex, ey, celestial[1]), 1);
    }

    /** The round moon's brightness at e (the unit disc; water.glsl moonShape before the cover) and moon phase 0..7 (about 0..1.2). */
    public static float moonFace(float ex, float ey, float moonPhase) {
        float z = (float) Math.sqrt(Math.max(1 - ex * ex - ey * ey, 0));
        float[] p = {ex, ey, z};
        float phase = Math.max(0, Math.min(moonPhase, 8));
        float phi = phase * 0.7853982f;
        float[] sunward = {(float) Math.sin(phi), 0, (float) Math.cos(phi)};
        float wobble = (moonNoise(ex * 5 + 1.3f, ey * 5 + 7.1f, MOON_MARIA_SEED + 1) - 0.5f) * 0.16f
                + (moonNoise(ex * 13 + 4.1f, ey * 13 + 2.2f, MOON_MARIA_SEED + 2) - 0.5f) * 0.06f;
        float mare = 0;
        for (int k = 0; k < MOON_MARE_COUNT; k++) {
            float[] m = MOON_MARE[k];
            float dx = p[0] - m[0], dy = p[1] - m[1], dz = p[2] - m[2];
            mare = Math.max(mare, 1 - smoothstep(0.6f * m[3], 1.15f * m[3], (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + wobble));
        }
        float mottle = 0.6f * moonNoise(ex * 3.5f + 3.7f, ey * 3.5f + 1.9f, MOON_MARIA_SEED) + 0.4f * moonNoise(ex * 9 + 0.7f, ey * 9 + 5.3f, MOON_MARIA_SEED + 3);
        float albedo = (1 - MOON_MOTTLE * mottle) * (1 - MOON_MARIA * mare * (0.8f + 0.4f * moonNoise(ex * 7 + 9.1f, ey * 7 + 3.3f, MOON_MARIA_SEED + 4)));
        float[] slope = new float[3], rim = new float[1];
        float rays = 0;
        for (int k = 0; k < MOON_CRATER_COUNT; k++) {
            float[] c = MOON_CRATER[k], look = MOON_CRATER_LOOK[k];
            float[] cc = {c[0], c[1], c[2]};
            float s = moonCrater(p, cc, c[3], 1, slope, rim);
            if (look[0] > 0 && s < 30) rays += look[0] * moonRays(p, cc, c[3], s);
            if (look[1] < 0) albedo *= 1 + look[1] * (1 - smoothstep(0.75f, 0.95f, s));
        }
        int cx = (int) Math.floor(ex / MOON_CRATER_CELL), cy = (int) Math.floor(ey / MOON_CRATER_CELL);
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                int ix = cx + x, iy = cy + y;
                float h = moonHash(ix, iy, MOON_CRATER_SEED);
                float ax = (ix + 0.2f + 0.6f * moonHash(ix, iy, MOON_CRATER_SEED + 2)) * MOON_CRATER_CELL;
                float ay = (iy + 0.2f + 0.6f * moonHash(ix, iy, MOON_CRATER_SEED + 6)) * MOON_CRATER_CELL;
                float ll = ax * ax + ay * ay;
                if (h < MOON_CRATER_SHARE && ll < 0.96f) {
                    float w = h / MOON_CRATER_SHARE;
                    moonCrater(p, new float[]{ax, ay, (float) Math.sqrt(1 - ll)}, MOON_CRATER_MIN + (MOON_CRATER_MAX - MOON_CRATER_MIN) * w * w * w,
                            1 - 0.65f * mare, slope, rim);
                }
            }
        }
        float[] n = {p[0] - slope[0], p[1] - slope[1], p[2] - slope[2]};
        n = scale(n, 1 / (float) Math.sqrt(dot(n, n)));
        float mu0 = dot(n, sunward), smooth0 = dot(p, sunward);
        float lit = Math.min(smoothstep(-MOON_TERMINATOR, MOON_TERMINATOR, mu0), smoothstep(-MOON_NIGHT_REACH, -MOON_NIGHT_REACH * 0.3f, smooth0));
        float relief = 1;
        if (smooth0 > 0) {
            float tilted = Math.max(mu0, 0) / (Math.max(mu0, 0) + Math.max(n[2], 0.05f));
            float even = smooth0 / (smooth0 + Math.max(z, 0.05f));
            relief = Math.max(0, Math.min(tilted / Math.max(even, 0.0001f), MOON_RELIEF_MAX));
        }
        albedo *= 1 + (rim[0] + rays) * smoothstep(0.1f, 0.6f, smooth0);
        return albedo * (MOON_EARTHSHINE + (relief - MOON_EARTHSHINE) * lit);
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
        //2026-11-10 (session 8): times the picked moon colour's tint (Atmosphere.moonTint)
        float[] base = {MOON_RED, MOON_GREEN, MOON_BLUE}, t = a.moonTint();
        float[] tint = {base[0] * t[0], base[1] * t[1], base[2] * t[2]};
        float night = a.moonLight()[3] > 0 ? Math.min(a.moonLight()[3], 1) : 0;
        float overcast = a.sunLight()[3] > 0 ? Math.min(a.sunLight()[3] * DISC_RAIN_HIDE, 1) : 0;
        return scale(tint, MOON_DISC_LUMINANCE / PostMath.luminance(base[0], base[1], base[2]) * night * (1 - overcast)
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
                glintOf(moonRadiance(a), r, moon, moonShape(r, moon, celestial, true), moonLit(celestial), ripple));
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

    // ---- the sun's open share (2026-11-10, sunopen.glsl) ----

    public static final float SUN_OPEN_TAP = 0.66f, SUN_OPEN_COS_START = 0.7f, SUN_OPEN_COS_FULL = 0.85f;

    public static float sunOpenWeight(float cosToSun) {
        return smoothstep(SUN_OPEN_COS_START, SUN_OPEN_COS_FULL, cosToSun);
    }

    public static float sunOpenAt(float share, float weight) {
        float s = share > 0 ? Math.min(share, 1) : 0;
        float w = weight > 0 ? Math.min(weight, 1) : 0;
        return 1 + (s - 1) * w;
    }

    /**
     * sunopen.glsl's taps: the GL screen uv (0..1, y up) of each of the 3 x 3 taps over the sun's disc of half extent extent (sunExtent),
     * null behind the camera.
     */
    public static float[][] sunOpenTaps(org.joml.Matrix4fc viewToClip, float[] sunView, float extent) {
        org.joml.Vector4f c = viewToClip.transform(new org.joml.Vector4f(sunView[0], sunView[1], sunView[2], 0), new org.joml.Vector4f());
        if (!(c.w > 0.000001f)) return null;
        float u = c.x / c.w * 0.5f + 0.5f, v = c.y / c.w * 0.5f + 0.5f;
        float hx = extent * 0.5f * Math.abs(viewToClip.m00()), hy = extent * 0.5f * Math.abs(viewToClip.m11());
        float[][] taps = new float[9][];
        int n = 0;
        for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) taps[n++] = new float[]{u + i * SUN_OPEN_TAP * hx, v + j * SUN_OPEN_TAP * hy};
        return taps;
    }

    /** sunOpenShare: the share of taps open (open.test(uv): the sky there, for one on the screen; off the screen counts open); 1 behind the camera. */
    public static float sunOpenShare(org.joml.Matrix4fc viewToClip, float[] sunView, float extent, java.util.function.Predicate<float[]> open) {
        float[][] taps = sunOpenTaps(viewToClip, sunView, extent);
        if (taps == null) return 1;
        int n = 0;
        for (float[] t : taps) {
            boolean off = t[0] < 0 || t[1] < 0 || t[0] >= 1 || t[1] >= 1;
            if (off || open.test(t)) n++;
        }
        return n / 9f;
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
        float w = RippleMath.STEEPS[k] * rippleFade(RippleMath.RIPPLE_PERIOD / (nx * m), footprint);
        //Session 7: a faded-out octave adds nothing (its gradient is skipped)
        if (!(w > 0)) return new float[]{0, 0};
        float qx = (u * px + v * pz) * nx / RippleMath.RIPPLE_PERIOD, qz = (u * pz - v * px) * ny / RippleMath.RIPPLE_PERIOD;
        float[] g = rippleGradient(qx, qz, nx, ny, k + 1);
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
