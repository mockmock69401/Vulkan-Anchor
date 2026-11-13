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
    static float mix(float a, float b, float t) {
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

    /** A march result as farCloudMarch gives it (sunlit weight, sky-lit weight, coverage, distance): three values are a vanilla-shape march, its coverage the sky-lit weight. */
    public static float[] four(float[] m) {
        return m.length >= 4 ? m : new float[]{m[0], m[1], m[1], m[2]};
    }

    // ---- Session 3 (2026-11-09): the realistic style. clouds.glsl's realistic part mirrored (change both together), and its noise map ----

    /** clouds.glsl's REAL_* constants (see there). */
    public static final float REAL_COVER = 0.38f, REAL_TOP_MIN = 0.3f, REAL_TOWER = 0.45f, REAL_BOTTOM = 0.35f, REAL_ROUND = 0.35f, REAL_BASE_LIFT = 0.25f;
    public static final float REAL_ERODE = 0.6f, REAL_NOISE_CELL = 48, REAL_NOISE_CELL2 = 16, REAL_NOISE_CELL3 = 6, REAL_NOISE_DETAIL = 0.45f,
            REAL_NOISE_DETAIL2 = 0.25f, REAL_NOISE_MEAN = 0.6f, REAL_DENSITY = 0.45f;
    public static final int REAL_SAMPLES_LOW = 16, REAL_SAMPLES_MEDIUM = 32, REAL_SAMPLES_HIGH = 56, REAL_SAMPLES_MIN = 8;
    public static final float REAL_STEP = 4;
    public static final int REAL_LIGHT_LOW = 2, REAL_LIGHT_MEDIUM = 3, REAL_LIGHT_HIGH = 4;
    public static final float REAL_LIGHT_STEP = 6;
    public static final float REAL_FORWARD_G = 0.6f, REAL_BACK_G = -0.25f, REAL_BACK = 0.3f;
    public static final int REAL_OCTAVES = 3;
    public static final float REAL_OCTAVE = 0.5f, REAL_POWDER = 0.6f, REAL_POWDER_DEPTH = 8, REAL_AMBIENT_LOW = 0.25f, REAL_FLAT_DEPTH = 2;
    public static final int REAL_REFLECT_SAMPLES = 12;
    public static final float REAL_CIRRUS_HEIGHT = 480, REAL_CIRRUS_COVER = 0.3f, REAL_CIRRUS_OPACITY = 0.45f, REAL_CIRRUS_G = 0.7f, REAL_CIRRUS_AMBIENT = 1.2f;
    public static final int REAL_PRIME_X = 73856093, REAL_PRIME_Y = 19349663, REAL_PRIME_Z = 83492791;

    /**
     * CPU side: the cumulus base REAL_LIFT blocks above vanilla's layer (vanilla's sits about 130 blocks over the sea; the cumulus then
     * stand about 260 over it, so a 200-block tower spans some 40 degrees overhead, as a real one at 1.5 km), REAL_THICKNESS thick
     * (3b: 96 to 144, so the towers stand about as tall as they are wide).
     */
    public static final float REAL_LIFT = 128, REAL_THICKNESS = 144;
    /** The cloud style setting: vanilla's cell shape (the far clouds as before session 3) or the realistic clouds. */
    public static final int STYLE_VANILLA = 0, STYLE_REALISTIC = 1, STYLE_ANIME = 2, STYLE_MAX = 2;
    /** Session 11: cloudOffset.z's whole part is ANIME_Z plus the quality (0..2) in the anime style (1..3: the realistic style's). */
    public static final int ANIME_Z = 4;
    /** The cloud quality setting: low, medium, high (cloudOffset.z's whole part is the setting plus 1). */
    public static final int QUALITY_MAX = 2;

    /** The realistic layer as the shader has it: cloudShape (base relative to the camera, thickness, LOD distance), cloudOffset xy, the coverage (cloudOffset.w). */
    public record Realistic(float base, float thickness, float lodDistance, float offsetX, float offsetZ, float cover) {
    }

    /** farCloudMarch's counts: (samples, light samples, erosion octaves) for quality 1..3 (cloudOffset.z's whole part). */
    public static int[] quality(int q) {
        return q >= 3 ? new int[]{REAL_SAMPLES_HIGH, REAL_LIGHT_HIGH, 3} : q == 2 ? new int[]{REAL_SAMPLES_MEDIUM, REAL_LIGHT_MEDIUM, 3}
                : new int[]{REAL_SAMPLES_LOW, REAL_LIGHT_LOW, 1};
    }

    private static float channel(byte[] map, int x, int z, int c) {
        int ix = (int) mod(x, MAP), iz = (int) mod(z, MAP);
        return (map[(iz * MAP + ix) * 4 + c] & 0xFF) / 255f;
    }

    /** clouds.glsl's cloudNoise: the map's channel c (0..3) bilinearly at (mx, mz) blocks on the cloud map, wrapped. */
    public static float noise(byte[] map, float mx, float mz, int c) {
        float ux = mx / CLOUD_CELL - 0.5f, uz = mz / CLOUD_CELL - 0.5f;
        int ix = (int) Math.floor(ux), iz = (int) Math.floor(uz);
        float fx = ux - ix, fz = uz - iz;
        return mix(mix(channel(map, ix, iz, c), channel(map, ix + 1, iz, c), fx), mix(channel(map, ix, iz + 1, c), channel(map, ix + 1, iz + 1, c), fx), fz);
    }

    /** realCorner: 0..1 at lattice corner (x, y, z), x and z wrapped into n cells. */
    static float corner(float x, float y, float z, float n) {
        int ix = (int) (x - n * (float) Math.floor(x / n)), iy = (int) y, iz = (int) (z - n * (float) Math.floor(z / n));
        int h = (ix * REAL_PRIME_X) ^ (iy * REAL_PRIME_Y) ^ (iz * REAL_PRIME_Z);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777215f;
    }

    /** realValueNoise: 3D value noise 0..1 at (x, y, z) lattice units, repeating every n cells along x and z. */
    public static float valueNoise(float x, float y, float z, float n) {
        float ix = (float) Math.floor(x), iy = (float) Math.floor(y), iz = (float) Math.floor(z);
        float fx = x - ix, fy = y - iy, fz = z - iz;
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy), uz = fz * fz * (3 - 2 * fz);
        float a = mix(corner(ix, iy, iz, n), corner(ix + 1, iy, iz, n), ux);
        float b = mix(corner(ix, iy + 1, iz, n), corner(ix + 1, iy + 1, iz, n), ux);
        float c = mix(corner(ix, iy, iz + 1, n), corner(ix + 1, iy, iz + 1, n), ux);
        float d = mix(corner(ix, iy + 1, iz + 1, n), corner(ix + 1, iy + 1, iz + 1, n), ux);
        return mix(mix(a, b, uy), mix(c, d, uy), uz);
    }

    /** realErosion: octaves 0 the mean (REAL_NOISE_MEAN), 1 the first octave, 2 and 3 with the lumps (1 - |2n - 1|). */
    public static float erosion(float x, float y, float z, int octaves) {
        if (octaves <= 0) return REAL_NOISE_MEAN;
        float e = valueNoise(x / REAL_NOISE_CELL, y / REAL_NOISE_CELL, z / REAL_NOISE_CELL, MAP * CLOUD_CELL / REAL_NOISE_CELL);
        if (octaves >= 2) e = mix(e, lumps(x / REAL_NOISE_CELL2, y / REAL_NOISE_CELL2, z / REAL_NOISE_CELL2, MAP * CLOUD_CELL / REAL_NOISE_CELL2), REAL_NOISE_DETAIL);
        if (octaves >= 3) e = mix(e, lumps(x / REAL_NOISE_CELL3, y / REAL_NOISE_CELL3, z / REAL_NOISE_CELL3, MAP * CLOUD_CELL / REAL_NOISE_CELL3), REAL_NOISE_DETAIL2);
        return e;
    }

    /** realErosionFor's REAL_KEEP_MARGIN: room for rounding in the octaves' lower bound. */
    public static final float REAL_KEEP_MARGIN = 0.999f;

    /**
     * Session 7, realErosionFor: erosion for a cumulus density d, or -1 where the octaves evaluated so far prove the density 0 (each later
     * octave keeps at least 1 - its detail of the erosion, so REAL_ERODE times that floor at or above d leaves nothing); the later
     * octaves are then skipped.
     */
    public static float erosionFor(float x, float y, float z, int octaves, float d) {
        if (octaves <= 0) return REAL_NOISE_MEAN;
        float e = valueNoise(x / REAL_NOISE_CELL, y / REAL_NOISE_CELL, z / REAL_NOISE_CELL, MAP * CLOUD_CELL / REAL_NOISE_CELL);
        if (octaves >= 2) {
            float keep = (1 - REAL_NOISE_DETAIL) * (octaves >= 3 ? 1 - REAL_NOISE_DETAIL2 : 1) * REAL_KEEP_MARGIN;
            if (REAL_ERODE * e * keep >= d) return -1;
            e = mix(e, lumps(x / REAL_NOISE_CELL2, y / REAL_NOISE_CELL2, z / REAL_NOISE_CELL2, MAP * CLOUD_CELL / REAL_NOISE_CELL2), REAL_NOISE_DETAIL);
        }
        if (octaves >= 3) {
            if (REAL_ERODE * e * (1 - REAL_NOISE_DETAIL2) * REAL_KEEP_MARGIN >= d) return -1;
            e = mix(e, lumps(x / REAL_NOISE_CELL3, y / REAL_NOISE_CELL3, z / REAL_NOISE_CELL3, MAP * CLOUD_CELL / REAL_NOISE_CELL3), REAL_NOISE_DETAIL2);
        }
        return e;
    }

    /** realLumps: 1 - |2n - 1| of the value noise: low inside rounded lumps, 1 along the creases between them. */
    static float lumps(float x, float y, float z, float n) {
        return 1 - Math.abs(2 * valueNoise(x, y, z, n) - 1);
    }

    /** realDensity: the cumulus density 0..1 at camera-relative (x, y, z). */
    public static float realDensity(byte[] map, Realistic r, float x, float y, float z, int octaves) {
        float h = (y - r.base) / r.thickness, f = Math.min(r.cover, 1);
        if (!(h > 0 && h < 1) || !(f > 0)) return 0;
        float mx = r.offsetX + x, mz = r.offsetZ + z;
        float s = (noise(map, mx, mz, 1) - 1 + f) / f;
        if (s <= 0) return 0;
        float tower = noise(map, mx, mz, 3), lift = REAL_BASE_LIFT * (1 - tower);
        float hp = (h - lift) / ((1 - lift) * mix(REAL_TOP_MIN, 1, s) * mix(1 - REAL_TOWER, 1, tower));
        if (!(hp > 0 && hp < 1)) return 0;
        float d = s * smoothstep(0, REAL_BOTTOM, hp) * (1 - smoothstep(REAL_ROUND, 1, hp));
        float er = erosionFor(mx, y - r.base, mz, octaves, d);
        if (er < 0) return 0;
        float e = REAL_ERODE * er;
        return Math.max((d - e) / (1 - e), 0);
    }

    /** realHg: Henyey-Greenstein scaled so an even phase is 1. */
    public static float hg(float c, float g) {
        float k = 1 + g * g - 2 * g * c;
        return (1 - g * g) / (k * (float) Math.sqrt(k));
    }

    private static float[] interval(Realistic r, float[] origin, float[] dir, float maxDist) {
        return interval(r.base, r.thickness, r.lodDistance, origin, dir, maxDist);
    }

    /** realMarch: (sunlit weight, sky-lit weight, coverage, coverage-weighted distance) of the cumulus and the cirrus behind them. */
    public static float[] realMarch(byte[] map, Realistic r, float[] origin, float[] dir, float maxDist, float[] lightDir, float jitter, int samples,
                                    int lights, int octaves) {
        float level = smoothstep(0, CLOUD_SHADE_LEVEL, Math.abs(lightDir[1]));
        float c = dot3(dir, lightDir);
        float[] phase = new float[REAL_OCTAVES];
        float g = level;
        for (int k = 0; k < REAL_OCTAVES; k++) {
            phase[k] = mix(hg(c, REAL_FORWARD_G * g), hg(c, REAL_BACK_G * g), REAL_BACK);
            g *= REAL_OCTAVE;
        }
        float powder = REAL_POWDER * (0.5f - 0.5f * c);
        float sun = 0, amb = 0, trans = 1, dsum = 0, wsum = 0;
        float[] span = interval(r, origin, dir, maxDist);
        if (span != null) {
            float len = span[1] - span[0];
            int n = (int) Math.max(REAL_SAMPLES_MIN, Math.min(Math.max(samples, REAL_SAMPLES_MIN), Math.ceil(len / REAL_STEP)));
            float dt = len / n;
            for (int i = 0; i < REAL_SAMPLES_HIGH; i++) {
                if (i >= n || trans < 0.01f) break;
                float t = span[0] + (i + jitter) * dt;
                float px = origin[0] + dir[0] * t, py = origin[1] + dir[1] * t, pz = origin[2] + dir[2] * t;
                float d = realDensity(map, r, px, py, pz, octaves);
                if (d <= 0) continue;
                float fade = 1 - smoothstep(CLOUD_FADE_START, 1, (float) Math.sqrt(px * px + pz * pz) / r.lodDistance);
                float sigma = d * REAL_DENSITY;
                float a = (1 - (float) Math.exp(-sigma * dt)) * fade;
                float h = clamp01((py - r.base) / r.thickness);
                float od = lights > 0 ? lightDepth(map, r, px, py, pz, lightDir, lights) : REAL_FLAT_DEPTH * (1 - h);
                od *= level;
                float s = 0, share = 1, depth = 1;
                for (int k = 0; k < REAL_OCTAVES; k++) {
                    s += share * (float) Math.exp(-od * depth) * phase[k];
                    share *= REAL_OCTAVE;
                    depth *= REAL_OCTAVE;
                }
                s *= 1 - powder * (float) Math.exp(-sigma * REAL_POWDER_DEPTH);
                float w = a * trans;
                sun += w * s;
                amb += w * mix(REAL_AMBIENT_LOW, 1, h);
                dsum += w * t;
                wsum += w;
                trans *= 1 - a;
            }
        }
        float top = r.base + REAL_CIRRUS_HEIGHT;
        if (trans >= 0.01f && dir[1] > 0 && origin[1] < top) {
            float t = (top - origin[1]) / dir[1];
            float qx = origin[0] + dir[0] * t, qz = origin[2] + dir[2] * t;
            float rr = (float) Math.sqrt(qx * qx + qz * qz) / r.lodDistance;
            float fc = Math.min(REAL_CIRRUS_COVER * r.cover / REAL_COVER, 0.95f);
            if (t < maxDist && rr < 1 && fc > 0) {
                float v = clamp01((noise(map, r.offsetX + qx, r.offsetZ + qz, 2) - 1 + fc) / fc);
                float a = v * v * REAL_CIRRUS_OPACITY * (1 - smoothstep(CLOUD_FADE_START, 1, rr));
                float w = a * trans;
                sun += w * mix(hg(c, REAL_CIRRUS_G * level), 1, 0.5f);
                amb += w * REAL_CIRRUS_AMBIENT;
                dsum += w * t;
                wsum += w;
                trans *= 1 - a;
            }
        }
        return new float[]{sun, amb, 1 - trans, wsum > 0 ? dsum / wsum : 0};
    }

    /** realMarch's light march: the optical depth toward lightDir from (x, y, z), lights samples REAL_LIGHT_STEP x 1, 4, 9, 16 blocks out (segment midpoints). */
    public static float lightDepth(byte[] map, Realistic r, float x, float y, float z, float[] lightDir, int lights) {
        float od = 0, prev = 0;
        for (int j = 1; j <= Math.min(lights, REAL_LIGHT_HIGH); j++) {
            float reach = REAL_LIGHT_STEP * j * j, m = 0.5f * (reach + prev);
            od += realDensity(map, r, x + lightDir[0] * m, y + lightDir[1] * m, z + lightDir[2] * m, 0) * (reach - prev);
            prev = reach;
        }
        return od * REAL_DENSITY;
    }

    private static float dot3(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    // ---- Session 11 (2026-11-10, redone in session 18): the anime style. clouds.glsl's anime part mirrored (change both together) ----

    /** clouds.glsl's ANIME_* constants (see there). */
    public static final float ANIME_PUFF_CELL = 104, ANIME_PUFF_JITTER = 0.12f, ANIME_PUFF_MIN = 0.6f, ANIME_PUFF_MAX = 0.88f, ANIME_PUFF_SHRINK = 0.6f;
    public static final float ANIME_PUFF_LOW = 0.15f, ANIME_PUFF_HIGH = 0.55f, ANIME_PUFF_TALL = 1, ANIME_PUFF_PROFILE = 0.8f, ANIME_PUFF_BLEND = 0.35f;
    public static final float ANIME_EDGE = 0.5f, ANIME_TOWER = 0.35f;
    public static final float ANIME_STEP = 6, ANIME_NORMAL_STEP = 12;
    public static final int ANIME_SAMPLES_LOW = 16, ANIME_SAMPLES_MEDIUM = 28, ANIME_SAMPLES_HIGH = 44, ANIME_SAMPLES_MIN = 10;
    public static final float ANIME_SOFT = 30, ANIME_SOFT_BASE = 60, ANIME_DENSITY = 0.12f;
    public static final float ANIME_FADE_NEAR = 500, ANIME_FADE_FAR = 1800, ANIME_FADE_SHRINK = 24, ANIME_HAZE = 0.5f;
    public static final float ANIME_WRAP = 0.8f, ANIME_SHADE_FROM = 0, ANIME_SHADE_TO = 1, ANIME_SHADOW = 0.4f, ANIME_FLAT = 0.6f;
    public static final float ANIME_CREASE_SHADE = 0.25f, ANIME_LOW_LIT = 0.65f, ANIME_LOW_REACH = 0.6f, ANIME_AMBIENT_LOW = 0.6f;
    public static final float ANIME_SILVER = 0.15f, ANIME_SILVER_G = 0.5f;
    /** CPU side: the anime layer's base ANIME_LIFT above vanilla's, ANIME_THICKNESS thick at cloud size 100% (scaled as realThickness). */
    public static final float ANIME_LIFT = 32, ANIME_THICKNESS = 140;

    /** The anime layer's thickness at a cloud size (fraction): ANIME_THICKNESS at 1. */
    public static float animeThickness(float size) {
        return ANIME_THICKNESS * (1 + size) / 2;
    }

    /** farCloudMarch's march samples for the anime style's quality 0..2 (cloudOffset.z's whole part minus ANIME_Z). */
    public static int animeSamples(int q) {
        return q >= 2 ? ANIME_SAMPLES_HIGH : q == 1 ? ANIME_SAMPLES_MEDIUM : ANIME_SAMPLES_LOW;
    }

    /** animeCell: a lattice's cell in blocks, base x k (the layer's thickness over ANIME_THICKNESS), whole cells to the PERIOD. */
    public static float animeCell(float base, float k) {
        return (float) (PERIOD / Math.max(1, Math.round(PERIOD / (base * k))));
    }

    /** animeHash: the 32-bit hash of lattice cell (x, z) on layer y, x and z wrapped into n cells (realCorner's, unscaled). */
    static int animeHash(int x, int y, int z, int n) {
        int h = (Math.floorMod(x, n) * REAL_PRIME_X) ^ (y * REAL_PRIME_Y) ^ (Math.floorMod(z, n) * REAL_PRIME_Z);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    /** A lattice cell's sphere centre along one axis in cells, from 8 bits of its hash: up to ANIME_PUFF_JITTER off the cell's middle. */
    private static float centre(int c, int h) {
        return c + 0.5f + ANIME_PUFF_JITTER * ((h & 0xFF) / 127.5f - 1);
    }

    /** animeShape: (bottom, top, puffs) above the layer's base in blocks at map position (mx, mz); top below bottom where no cloud stands. */
    public static float[] animeShape(byte[] map, Realistic r, float mx, float mz) {
        return animeShape(map, r, mx, mz, -1);
    }

    /**
     * animeShape for a sample h blocks above the base (negative: none): the puffs (rounded domes on a lattice, blended where they meet) in
     * the column at (mx, mz), (bottom, top, puffs) with puffs how near the column is to a puff's middle (1) from every puff's edge (0);
     * (0, -1, 0) where no puff reaches. Above the highest top any puff could give: none, before the lattice.
     */
    public static float[] animeShape(byte[] map, Realistic r, float mx, float mz, float h) {
        float[] none = {0, -1, 0};
        float f = Math.min(r.cover, 1);
        if (!(f > 0)) return none;
        float k = r.thickness / ANIME_THICKNESS;
        float cell = animeCell(ANIME_PUFF_CELL, k);
        if (h >= 0 && h >= Math.min(((ANIME_PUFF_HIGH + ANIME_PUFF_TALL) * ANIME_PUFF_MAX + 0.75f * ANIME_PUFF_BLEND) * cell, r.thickness)) return none;
        int n = (int) Math.round(PERIOD / cell);
        float ux = mx / cell, uz = mz / cell;
        int ix = (int) Math.floor(ux), iz = (int) Math.floor(uz);
        int ox = ux - ix < 0.5f ? -1 : 0, oz = uz - iz < 0.5f ? -1 : 0;
        float bottom = 0, top = -1, puffs = 0;
        for (int b = 0; b < 2; b++) {
            for (int a = 0; a < 2; a++) {
                int cx = ix + ox + a, cz = iz + oz + b;
                int hash = animeHash(cx, 0, cz, n);
                float px = centre(cx, hash), pz = centre(cz, hash >>> 8);
                int tx = (int) Math.floor(px * cell / CLOUD_CELL), tz = (int) Math.floor(pz * cell / CLOUD_CELL);
                float s = (channel(map, tx, tz, 1) - 1 + f) / f;
                if (s <= 0) continue;
                float rad = cell * mix(ANIME_PUFF_MIN, ANIME_PUFF_MAX, ((hash >>> 16) & 0xFF) / 255f) * mix(ANIME_PUFF_SHRINK, 1, Math.min(s / ANIME_EDGE, 1));
                float dx = (ux - px) * cell, dz = (uz - pz) * cell;
                float q2 = rad * rad - dx * dx - dz * dz;
                if (q2 <= 0) continue;
                float lift = mix(1 - ANIME_TOWER, 1, channel(map, tx, tz, 3));
                float q = rad * (float) Math.pow(q2 / (rad * rad), ANIME_PUFF_PROFILE), cy = rad * mix(ANIME_PUFF_LOW, ANIME_PUFF_HIGH, Math.min(s, 1)) * lift;
                float lo = Math.max(cy - q * ANIME_PUFF_TALL, 0), hi = cy + q * ANIME_PUFF_TALL;
                puffs = Math.max(puffs, q / rad);
                if (top < bottom) {
                    bottom = lo;
                    top = hi;
                } else {
                    //A smooth union (polynomial smooth max and min over ANIME_PUFF_BLEND cells): no crease where two puffs meet
                    float kb = ANIME_PUFF_BLEND * cell;
                    float ht = Math.max(kb - Math.abs(top - hi), 0) / kb, hb = Math.max(kb - Math.abs(bottom - lo), 0) / kb;
                    top = Math.max(top, hi) + ht * ht * kb * 0.25f;
                    bottom = Math.max(Math.min(bottom, lo) - hb * hb * kb * 0.25f, 0);
                }
            }
        }
        if (!(top > bottom)) return none;
        return new float[]{bottom, Math.min(top, r.thickness), puffs};
    }

    /** True where camera-relative (x, y, z) is inside an anime cloud. */
    public static boolean animeInside(byte[] map, Realistic r, float x, float y, float z) {
        return animeDepth(map, r, x, y, z) > 0;
    }

    /** animeDepth: how far camera-relative (x, y, z) is inside its column's pillow, in blocks (min of the distances to bottom and top; at most 0 outside). */
    public static float animeDepth(byte[] map, Realistic r, float x, float y, float z) {
        float h = y - r.base;
        if (!(h > 0 && h < r.thickness)) return 0;
        float[] bt = animeShape(map, r, r.offsetX + x, r.offsetZ + z, h);
        return Math.max(Math.min((h - bt[0]) * (ANIME_SOFT / ANIME_SOFT_BASE), bt[1] - h), 0);
    }

    /**
     * animeNormal: the cloud surface's unit normal at camera-relative (x, y, z): straight down at the column's bottom turning smoothly to the
     * top's normal (from its heights ANIME_NORMAL_STEP blocks either side) at its top (the flat base's cut rims drew bright arcs).
     */
    public static float[] animeNormal(byte[] map, Realistic r, float x, float y, float z) {
        float mx = r.offsetX + x, mz = r.offsetZ + z, e = ANIME_NORMAL_STEP;
        float[] c = animeShape(map, r, mx, mz), px = animeShape(map, r, mx + e, mz), nx = animeShape(map, r, mx - e, mz),
                pz = animeShape(map, r, mx, mz + e), nz = animeShape(map, r, mx, mz - e);
        float w = smoothstep(0, 1, clamp01((y - r.base - c[0]) / Math.max(c[1] - c[0], 1e-3f)));
        float[] top = unit(new float[]{-(px[1] - nx[1]), 2 * e, -(pz[1] - nz[1])});
        return unit(new float[]{top[0] * w, mix(-1, top[1], w), top[2] * w});
    }

    private static float[] unit(float[] v) {
        float l = (float) Math.sqrt(dot3(v, v));
        return l > 0 ? new float[]{v[0] / l, v[1] / l, v[2] / l} : new float[]{0, 1, 0};
    }

    /**
     * animeLight: (sunlit weight, sky-lit weight) of a cloud point with normal n, puffs p (0 at every puff's edge) at height hc (0 the
     * layer's base, 1 its top), lit from lightDir: soft wrapped light, darker where puffs meet and toward the base, the sky light dimmer
     * toward the base.
     */
    public static float[] animeLight(float[] n, float p, float hc, float[] lightDir) {
        float level = smoothstep(0, CLOUD_SHADE_LEVEL, Math.abs(lightDir[1]));
        float x = (dot3(n, lightDir) + ANIME_WRAP) / (1 + ANIME_WRAP);
        float lit = mix(ANIME_SHADOW, 1, smoothstep(ANIME_SHADE_FROM, ANIME_SHADE_TO, x));
        lit *= mix(1 - ANIME_CREASE_SHADE, 1, smoothstep(0, 0.5f, p)) * mix(ANIME_LOW_LIT, 1, smoothstep(0, ANIME_LOW_REACH, hc));
        return new float[]{mix(ANIME_FLAT, lit, level), mix(ANIME_AMBIENT_LOW, 1, clamp01(hc))};
    }

    /** animeFade: the anime clouds' opacity at horizontal distance d from the camera: 1 to ANIME_FADE_NEAR, 0 from ANIME_FADE_FAR (into the haze). */
    public static float animeFade(float d) {
        return 1 - smoothstep(ANIME_FADE_NEAR, ANIME_FADE_FAR, d);
    }

    /**
     * animeMarch: (sunlit weight, sky-lit weight, coverage, distance) of the anime clouds along dir: samples through the layer (cut where
     * the fade ends) from jitter 0..1 along each step, each as dense as it is deep inside its pillow (soft over ANIME_SOFT blocks), then
     * the light at the coverage-weighted point. Fading out, a cloud also thins (its outer ANIME_FADE_SHRINK blocks go first), so the far ones
     * shrink away; the distance returned (for the haze) is ANIME_HAZE of the point's.
     */
    public static float[] animeMarch(byte[] map, Realistic r, float[] origin, float[] dir, float maxDist, float[] lightDir, float jitter, int samples) {
        float horizontal = (float) Math.sqrt(dir[0] * dir[0] + dir[2] * dir[2]);
        float[] span = interval(r, origin, dir, Math.min(maxDist, ANIME_FADE_FAR / Math.max(horizontal, CLOUD_MIN_HORIZONTAL)));
        if (span == null) return new float[4];
        float len = span[1] - span[0];
        int n = (int) Math.max(ANIME_SAMPLES_MIN, Math.min(Math.max(samples, ANIME_SAMPLES_MIN), Math.ceil(len / ANIME_STEP)));
        float dt = len / n;
        float trans = 1, tsum = 0, wsum = 0;
        for (int i = 0; i < ANIME_SAMPLES_HIGH; i++) {
            if (i >= n || trans < 0.01f) break;
            float t = span[0] + (i + jitter) * dt;
            float px = origin[0] + dir[0] * t, py = origin[1] + dir[1] * t, pz = origin[2] + dir[2] * t;
            float d = animeDepth(map, r, px, py, pz);
            if (d <= 0) continue;
            float fade = animeFade((float) Math.sqrt(px * px + pz * pz));
            float a = (1 - (float) Math.exp(-ANIME_DENSITY * smoothstep(0, ANIME_SOFT, d - (1 - fade) * ANIME_FADE_SHRINK) * dt)) * fade;
            float w = a * trans;
            tsum += w * t;
            wsum += w;
            trans *= 1 - a;
        }
        if (!(wsum > 0)) return new float[4];
        float t = tsum / wsum;
        float px = origin[0] + dir[0] * t, py = origin[1] + dir[1] * t, pz = origin[2] + dir[2] * t;
        float[] c = animeShape(map, r, r.offsetX + px, r.offsetZ + pz);
        float hc = clamp01((py - r.base) / r.thickness);
        float[] l = animeLight(animeNormal(map, r, px, py, pz), c[1] > c[0] ? c[2] : 1, hc, lightDir);
        float cov = 1 - trans;
        //The silver lining: thin cloud (low coverage) toward the light glows with its forward scattering
        float level = smoothstep(0, CLOUD_SHADE_LEVEL, Math.abs(lightDir[1]));
        float silver = ANIME_SILVER * (1 - cov) * hg(dot3(dir, lightDir), ANIME_SILVER_G * level);
        return new float[]{cov * (l[0] + silver), cov * l[1], cov, t * ANIME_HAZE};
    }

    // ---- the noise map (CPU only): cloudMap's g, b, a channels ----

    /** Seeds of the channels' noises (SEED_COUNT: which clouds the count keeps). */
    private static final int SEED_WEATHER = 11, SEED_CIRRUS = 23, SEED_TOWER = 37, SEED_WARP = 51, SEED_COUNT = 63;

    /**
     * 2026-11-09 (after session 3b): the cloud size and count settings, as fractions (1 = 100%). The size scales the weather and tower
     * noises' cells (each cumulus and the gaps between them; the 3D erosion, the cauliflower, keeps its size) and, by (1 + size) / 2, the
     * layer's thickness, so a bigger cloud is also taller. The count keeps COUNT_KEEP x count of the clouds the weather would grow (all of
     * them from count 1 / COUNT_KEEP): each cloud at the default coverage (a 4-connected patch of texels above 1 - REAL_COVER) owns the
     * texels nearer to it than to any other, and a hash of the cloud picks whether it stays; a dropped cloud's texels are scaled by
     * COUNT_DROP, so it is gone at coverages up to 1 - COUNT_DROP and only heavy rain or a high coverage setting brings part of it back.
     * The kept clouds keep their values exactly. The coverage (cloudOffset.w) still thresholds what is left: it swells or thins every
     * cloud at once.
     */
    public static final int SIZE_MIN = 50, SIZE_MAX = 200, COUNT_MAX = 200;
    public static final float COUNT_KEEP = 0.5f, COUNT_DROP = 0.5f;

    /** The layer's thickness at a cloud size (fraction): REAL_THICKNESS at 1. */
    public static float realThickness(float size) {
        return REAL_THICKNESS * (1 + size) / 2;
    }

    /** An octave's cells over the map at a cloud size: base / size, whole, from 1 to MAP / 2 (two texels a cell at least). */
    static int cells(int base, float size) {
        return Math.max(1, Math.min(MAP / 2, Math.round(base / size)));
    }

    /** The share of the clumps the count keeps (0..1). */
    public static float countKept(float count) {
        return clamp01(COUNT_KEEP * count);
    }

    /**
     * The count on an equalised weather map (in place): every texel goes to its nearest cloud (the 4-connected patches above
     * 1 - REAL_COVER, flooded outward over the wrapping map), and the texels of a cloud whose hash is not below k are scaled by COUNT_DROP.
     */
    static void keepClouds(float[] weather, float k) {
        int n = MAP, size = n * n;
        int[] owner = new int[size];
        java.util.Arrays.fill(owner, -1);
        int[] queue = new int[size];
        int head = 0, tail = 0;
        for (int i = 0; i < size; i++) {
            if (weather[i] <= 1 - REAL_COVER || owner[i] >= 0) continue;
            //A new cloud, named by its first texel
            int start = tail;
            owner[i] = i;
            queue[tail++] = i;
            for (int h = start; h < tail; h++) {
                int j = queue[h], x = j % n, z = j / n;
                for (int m : new int[]{z * n + (x + 1) % n, z * n + (x + n - 1) % n, ((z + 1) % n) * n + x, ((z + n - 1) % n) * n + x}) {
                    if (owner[m] < 0 && weather[m] > 1 - REAL_COVER) {
                        owner[m] = i;
                        queue[tail++] = m;
                    }
                }
            }
        }
        //The clear texels go to the nearest cloud (breadth first from every cloud texel at once)
        for (head = 0; head < tail; head++) {
            int j = queue[head], x = j % n, z = j / n;
            for (int m : new int[]{z * n + (x + 1) % n, z * n + (x + n - 1) % n, ((z + 1) % n) * n + x, ((z + n - 1) % n) * n + x}) {
                if (owner[m] < 0) {
                    owner[m] = owner[j];
                    queue[tail++] = m;
                }
            }
        }
        for (int i = 0; i < size; i++) {
            if (owner[i] >= 0 && cloudHash(owner[i]) >= k) weather[i] *= COUNT_DROP;
        }
    }

    /** 0..1 for the cloud named by its first texel, even over the clouds. */
    static float cloudHash(int texel) {
        int h = texel * REAL_PRIME_X ^ SEED_COUNT * REAL_PRIME_Z;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777216f;
    }

    /** A periodic 2D gradient noise (Perlin 2002's quintic fade) about -1..1 at (x, z) lattice units, repeating every nx by nz cells. */
    static float gradient(float x, float z, int nx, int nz, int seed) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        float ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10), uz = fz * fz * fz * (fz * (fz * 6 - 15) + 10);
        float a = cornerDot(ix, iz, fx, fz, nx, nz, seed), b = cornerDot(ix + 1, iz, fx - 1, fz, nx, nz, seed);
        float c = cornerDot(ix, iz + 1, fx, fz - 1, nx, nz, seed), d = cornerDot(ix + 1, iz + 1, fx - 1, fz - 1, nx, nz, seed);
        return 1.41f * mix(mix(a, b, ux), mix(c, d, ux), uz);
    }

    private static float cornerDot(int ix, int iz, float dx, float dz, int nx, int nz, int seed) {
        int h = (Math.floorMod(ix, nx) * REAL_PRIME_X) ^ (Math.floorMod(iz, nz) * REAL_PRIME_Y) ^ (seed * REAL_PRIME_Z);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        double angle = (h & 0xFFFFFF) / 16777216.0 * 2 * Math.PI;
        return (float) (Math.cos(angle) * dx + Math.sin(angle) * dz);
    }

    /** Fractal sums of gradient noise over the MAP x MAP torus: octave k has cellsX[k] x cellsZ[k] cells and weight weights[k]. */
    private static float fbm(float tx, float tz, int[] cellsX, int[] cellsZ, float[] weights, int seed) {
        float v = 0;
        for (int k = 0; k < weights.length; k++) {
            v += weights[k] * gradient(tx * cellsX[k] / MAP, tz * cellsZ[k] / MAP, cellsX[k], cellsZ[k], seed + k);
        }
        return v;
    }

    /** The values ranked into an even 0..1 (the share of the map below each value), so a threshold 1 - f leaves the share f. */
    static float[] equalise(float[] v) {
        Integer[] order = new Integer[v.length];
        for (int i = 0; i < v.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(v[a], v[b]));
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[order[i]] = i / (float) (v.length - 1);
        return out;
    }

    /** noiseMap at the default size and count (1, 1). */
    public static float[][] noiseMap() {
        return noiseMap(1, 1);
    }

    /**
     * The realistic style's three noises over the cloud map, MAP x MAP texels each (index z * MAP + x), equalised, repeating with the
     * map: [0] the weather (where cumulus grow: octaves of 192 to 24 blocks at size 1, shifted up to 48 blocks by a 768-block noise so the
     * patches are not round; 3b: from 256 to 32, so the cumulus stand as separate rounded clumps rather than wide slabs), then thinned by
     * the count (keepClouds: below count 1 / COUNT_KEEP it no longer fills its share evenly), [1] the cirrus (streaks four times longer
     * along x, the drift, than across, bent by the shift; size and count leave it), [2] the towers' height (scaled with the size).
     */
    public static float[][] noiseMap(float size, float count) {
        int n = MAP * MAP;
        float[] weather = new float[n], cirrus = new float[n], tower = new float[n];
        int[] w = {cells(16, size), cells(32, size), cells(64, size), cells(128, size)}, t = {cells(16, size), cells(32, size)};
        for (int z = 0; z < MAP; z++) {
            for (int x = 0; x < MAP; x++) {
                int i = z * MAP + x;
                float wx = 4 * gradient(x * 4f / MAP, z * 4f / MAP, 4, 4, SEED_WARP), wz = 4 * gradient(x * 4f / MAP + 0.5f, z * 4f / MAP + 0.5f, 4, 4, SEED_WARP + 1);
                weather[i] = fbm(x + wx, z + wz, w, w, new float[]{1, 0.6f, 0.3f, 0.15f}, SEED_WEATHER);
                cirrus[i] = fbm(x + wx, z + 2 * wz, new int[]{4, 8, 16, 32}, new int[]{16, 32, 64, 96}, new float[]{1, 0.55f, 0.3f, 0.1f}, SEED_CIRRUS);
                tower[i] = fbm(x, z, t, t, new float[]{1, 0.5f}, SEED_TOWER);
            }
        }
        weather = equalise(weather);
        float k = countKept(count);
        if (k < 1) keepClouds(weather, k);
        return new float[][]{weather, equalise(cirrus), equalise(tower)};
    }

    /** The cloud map with the noise map in its g, b, a channels (r keeps vanilla's mask, which the cell shape reads); a copy of rgba. */
    public static byte[] withNoise(byte[] rgba) {
        return withNoise(rgba, 1, 1);
    }

    /** withNoise at a cloud size and count (fractions, 1 = 100%). */
    public static byte[] withNoise(byte[] rgba, float size, float count) {
        byte[] out = rgba.clone();
        float[][] noise = noiseMap(size, count);
        for (int i = 0; i < MAP * MAP; i++) {
            for (int c = 0; c < 3; c++) out[i * 4 + 1 + c] = (byte) Math.round(noise[c][i] * 255);
        }
        return out;
    }
}
