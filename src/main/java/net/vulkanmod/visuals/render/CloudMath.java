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
    public static final float REAL_COVER = 0.38f, REAL_TOP_MIN = 0.3f, REAL_TOWER = 0.45f, REAL_BOTTOM = 0.08f, REAL_ROUND = 0.3f;
    public static final float REAL_ERODE = 0.65f, REAL_NOISE_CELL = 48, REAL_NOISE_CELL2 = 16, REAL_NOISE_DETAIL = 0.45f, REAL_DENSITY = 0.45f;
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
     * stand about 260 over it, so a 200-block tower spans some 40 degrees overhead, as a real one at 1.5 km), REAL_THICKNESS thick.
     */
    public static final float REAL_LIFT = 128, REAL_THICKNESS = 96;
    /** The cloud style setting: vanilla's cell shape (the far clouds as before session 3) or the realistic clouds. */
    public static final int STYLE_VANILLA = 0, STYLE_REALISTIC = 1, STYLE_MAX = 1;
    /** The cloud quality setting: low, medium, high (cloudOffset.z's whole part is the setting plus 1). */
    public static final int QUALITY_MAX = 2;

    /** The realistic layer as the shader has it: cloudShape (base relative to the camera, thickness, LOD distance), cloudOffset xy, the coverage (cloudOffset.w). */
    public record Realistic(float base, float thickness, float lodDistance, float offsetX, float offsetZ, float cover) {
    }

    /** farCloudMarch's counts: (samples, light samples, erosion octaves) for quality 1..3 (cloudOffset.z's whole part). */
    public static int[] quality(int q) {
        return q >= 3 ? new int[]{REAL_SAMPLES_HIGH, REAL_LIGHT_HIGH, 2} : q == 2 ? new int[]{REAL_SAMPLES_MEDIUM, REAL_LIGHT_MEDIUM, 2}
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

    /** realErosion: octaves 0 the mean, 1 the first octave, 2 with the billows (|2n - 1|). */
    public static float erosion(float x, float y, float z, int octaves) {
        if (octaves <= 0) return 0.5f;
        float e = valueNoise(x / REAL_NOISE_CELL, y / REAL_NOISE_CELL, z / REAL_NOISE_CELL, MAP * CLOUD_CELL / REAL_NOISE_CELL);
        if (octaves >= 2) e = mix(e, Math.abs(2 * valueNoise(x / REAL_NOISE_CELL2, y / REAL_NOISE_CELL2, z / REAL_NOISE_CELL2, MAP * CLOUD_CELL / REAL_NOISE_CELL2) - 1), REAL_NOISE_DETAIL);
        return e;
    }

    /** realDensity: the cumulus density 0..1 at camera-relative (x, y, z). */
    public static float realDensity(byte[] map, Realistic r, float x, float y, float z, int octaves) {
        float h = (y - r.base) / r.thickness, f = Math.min(r.cover, 1);
        if (!(h > 0 && h < 1) || !(f > 0)) return 0;
        float mx = r.offsetX + x, mz = r.offsetZ + z;
        float s = (noise(map, mx, mz, 1) - 1 + f) / f;
        if (s <= 0) return 0;
        float top = mix(REAL_TOP_MIN, 1, s) * mix(1 - REAL_TOWER, 1, noise(map, mx, mz, 3));
        float hp = h / top;
        if (hp >= 1) return 0;
        float d = s * smoothstep(0, REAL_BOTTOM, hp) * (1 - smoothstep(REAL_ROUND, 1, hp));
        float e = REAL_ERODE * erosion(mx, y - r.base, mz, octaves);
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

    // ---- the noise map (CPU only): cloudMap's g, b, a channels ----

    /** Seeds of the three channels' noises. */
    private static final int SEED_WEATHER = 11, SEED_CIRRUS = 23, SEED_TOWER = 37, SEED_WARP = 51;

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

    /** Fractal sums of gradient noise over the MAP x MAP torus: octave k has cellsX[k] x cellsZ[k] cells and weight weights[k]; (wx, wz) warp the texel first. */
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

    /**
     * The realistic style's three noises over the cloud map, MAP x MAP texels each (index z * MAP + x), equalised, repeating with the
     * map: [0] the weather (where cumulus grow: octaves of 256 to 32 blocks, shifted up to 48 blocks by a 768-block noise so the patches are not round),
     * [1] the cirrus (streaks four times longer along x, the drift, than across, bent by the shift), [2] the towers' height.
     */
    public static float[][] noiseMap() {
        int n = MAP * MAP;
        float[] weather = new float[n], cirrus = new float[n], tower = new float[n];
        for (int z = 0; z < MAP; z++) {
            for (int x = 0; x < MAP; x++) {
                int i = z * MAP + x;
                float wx = 4 * gradient(x * 4f / MAP, z * 4f / MAP, 4, 4, SEED_WARP), wz = 4 * gradient(x * 4f / MAP + 0.5f, z * 4f / MAP + 0.5f, 4, 4, SEED_WARP + 1);
                weather[i] = fbm(x + wx, z + wz, new int[]{12, 24, 48, 96}, new int[]{12, 24, 48, 96}, new float[]{1, 0.6f, 0.3f, 0.15f}, SEED_WEATHER);
                cirrus[i] = fbm(x + wx, z + 2 * wz, new int[]{4, 8, 16, 32}, new int[]{16, 32, 64, 96}, new float[]{1, 0.55f, 0.3f, 0.1f}, SEED_CIRRUS);
                tower[i] = fbm(x, z, new int[]{16, 32}, new int[]{16, 32}, new float[]{1, 0.5f}, SEED_TOWER);
            }
        }
        return new float[][]{equalise(weather), equalise(cirrus), equalise(tower)};
    }

    /** The cloud map with the noise map in its g, b, a channels (r keeps vanilla's mask, which the cell shape reads); a copy of rgba. */
    public static byte[] withNoise(byte[] rgba) {
        byte[] out = rgba.clone();
        float[][] noise = noiseMap();
        for (int i = 0; i < MAP * MAP; i++) {
            for (int c = 0; c < 3; c++) out[i * 4 + 1 + c] = (byte) Math.round(noise[c][i] * 255);
        }
        return out;
    }
}
