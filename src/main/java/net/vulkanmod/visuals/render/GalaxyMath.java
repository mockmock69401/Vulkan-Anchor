package net.vulkanmod.visuals.render;

/**
 * 2026-11-13 (shader roadmap session 19): the Java mirror of galaxy.glsl (the End's galaxy sky: the map's lookup and the stars). Every
 * const and function there is here under the same name (DimensionEffectsTest compares them). The band's light the map holds is
 * GalaxyMap's. Directions are world-space unit vectors {x, y, z}; colours scene units {r, g, b}.
 */
public final class GalaxyMath {
    public static final float GALAXY_POLE_X = 0.6198f;
    public static final float GALAXY_POLE_Y = 0.5499f;
    public static final float GALAXY_POLE_Z = -0.5599f;
    public static final float GALAXY_CORE_X = 0.4188f;
    public static final float GALAXY_CORE_Y = 0.3715f;
    public static final float GALAXY_CORE_Z = 0.8286f;
    public static final float GALAXY_DISC_WIDTH = 0.11f;
    public static final float GALAXY_BULGE_WIDEN = 0.9f;
    public static final float GALAXY_BULGE_REACH = 0.7f;
    public static final float GALAXY_DISC_FLOOR = 0.35f;
    public static final float GALAXY_DISC_REACH = 1.3f;
    public static final float GALAXY_MAP_SCALE = 0.6f;
    public static final float GALAXY_FAINT_CELLS = 700.0f;
    public static final float GALAXY_FAINT_BASE = 0.05f;
    public static final float GALAXY_FAINT_BAND = 0.35f;
    public static final float GALAXY_FAINT_LIGHT = 0.6f;
    public static final float GALAXY_BRIGHT_CELLS = 220.0f;
    public static final float GALAXY_BRIGHT_BASE = 0.1f;
    public static final float GALAXY_BRIGHT_BAND = 0.25f;
    public static final float GALAXY_BRIGHT_LIGHT = 3.0f;
    public static final float GALAXY_GRAIN_CELLS = 1500.0f;
    public static final float GALAXY_GRAIN_BAND = 0.55f;
    public static final float GALAXY_GRAIN_LIGHT = 0.25f;
    public static final float GALAXY_STAR_MIN = 0.08f;
    public static final float GALAXY_STAR_POWER = 6.0f;
    public static final float GALAXY_STAR_INSET = 0.2f;
    public static final float GALAXY_STAR_SIGMA = 0.65f;
    public static final float GALAXY_STAR_BRIGHTNESS = 0.1f;
    public static final int GALAXY_PRIME_X = 73856093;
    public static final int GALAXY_PRIME_Y = 19349663;
    public static final int GALAXY_PRIME_Z = 83492791;
    public static final float GALAXY_PI = 3.14159265f;

    private GalaxyMath() {
    }

    /** galaxy.glsl galaxyHash. */
    public static float galaxyHash(int x, int y, int z) {
        int h = (x * GALAXY_PRIME_X) ^ (y * GALAXY_PRIME_Y) ^ (z * GALAXY_PRIME_Z);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65535f;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** galaxy.glsl galaxyNoise. */
    public static float galaxyNoise(float x, float y, float z) {
        float ix = (float) Math.floor(x), iy = (float) Math.floor(y), iz = (float) Math.floor(z);
        float fx = x - ix, fy = y - iy, fz = z - iz;
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy), uz = fz * fz * (3 - 2 * fz);
        int cx = (int) ix, cy = (int) iy, cz = (int) iz;
        float a = mix(galaxyHash(cx, cy, cz), galaxyHash(cx + 1, cy, cz), ux);
        float b = mix(galaxyHash(cx, cy + 1, cz), galaxyHash(cx + 1, cy + 1, cz), ux);
        float d = mix(galaxyHash(cx, cy, cz + 1), galaxyHash(cx + 1, cy, cz + 1), ux);
        float e = mix(galaxyHash(cx, cy + 1, cz + 1), galaxyHash(cx + 1, cy + 1, cz + 1), ux);
        return mix(mix(a, b, uy), mix(d, e, uy), uz);
    }

    /** galaxy.glsl galaxyMapCoord: {u, v}. */
    public static float[] galaxyMapCoord(float[] dir) {
        return new float[]{(float) (Math.atan2(dir[0], -dir[2]) / (2 * GALAXY_PI) + 0.5),
                (float) (Math.acos(Math.max(-1, Math.min(1, dir[1]))) / GALAXY_PI)};
    }

    /** The direction at map coordinate (u, v) (galaxyMapCoord's inverse). */
    public static float[] mapDirection(double u, double v) {
        double az = (u - 0.5) * 2 * Math.PI, polar = v * Math.PI;
        double s = Math.sin(polar);
        return new float[]{(float) (s * Math.sin(az)), (float) Math.cos(polar), (float) (-s * Math.cos(az))};
    }

    /** {galactic latitude b, longitude l} along dir (radians). */
    public static float[] galactic(float[] dir) {
        float px = GALAXY_POLE_X, py = GALAXY_POLE_Y, pz = GALAXY_POLE_Z, cx = GALAXY_CORE_X, cy = GALAXY_CORE_Y, cz = GALAXY_CORE_Z;
        float sx = py * cz - pz * cy, sy = pz * cx - px * cz, sz = px * cy - py * cx;
        float gx = dir[0] * cx + dir[1] * cy + dir[2] * cz, gy = dir[0] * px + dir[1] * py + dir[2] * pz, gz = dir[0] * sx + dir[1] * sy + dir[2] * sz;
        return new float[]{(float) Math.asin(Math.max(-1, Math.min(1, gy))), (float) Math.atan2(gz, gx)};
    }

    /** galaxy.glsl galaxyDisc. */
    public static float galaxyDisc(float[] dir) {
        float[] bl = galactic(dir);
        float b = bl[0], l = bl[1];
        float width = GALAXY_DISC_WIDTH * (1 + GALAXY_BULGE_WIDEN * (float) Math.exp(-(l / GALAXY_BULGE_REACH) * (l / GALAXY_BULGE_REACH)));
        float along = GALAXY_DISC_FLOOR + (1 - GALAXY_DISC_FLOOR) * (float) Math.exp(-(l / GALAXY_DISC_REACH) * (l / GALAXY_DISC_REACH));
        return (float) Math.exp(-(b / width) * (b / width)) * along;
    }

    /** galaxy.glsl galaxyStars. */
    public static float[] galaxyStars(float[] dir, float cells, float chance, float light, int seed, float sigma) {
        float ax = Math.abs(dir[0]), ay = Math.abs(dir[1]), az = Math.abs(dir[2]);
        int axis = ax >= ay && ax >= az ? 0 : (ay >= az ? 1 : 2);
        float major = dir[axis];
        int ua = axis == 0 ? 2 : 0;
        int va = axis == 1 ? 2 : 1;
        float u = dir[ua] / Math.abs(major), v = dir[va] / Math.abs(major);
        float cu = (float) Math.floor((u * 0.5f + 0.5f) * cells), cv = (float) Math.floor((v * 0.5f + 0.5f) * cells);
        int face = axis * 2 + (major > 0 ? 1 : 0);
        int ix = (int) cu, iy = (int) cv, iz = face * 997 + seed * 7919;
        float rank = galaxyHash(ix, iy, iz);
        if (!(rank < chance)) return new float[3];
        float span = 1 - 2 * GALAXY_STAR_INSET;
        float atU = (cu + GALAXY_STAR_INSET + span * galaxyHash(ix, iy, iz + 1)) / cells * 2 - 1;
        float atV = (cv + GALAXY_STAR_INSET + span * galaxyHash(ix, iy, iz + 2)) / cells * 2 - 1;
        float[] s = new float[3];
        s[axis] = Math.signum(major);
        s[ua] = atU;
        s[va] = atV;
        float len = (float) Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
        float dx = dir[0] - s[0] / len, dy = dir[1] - s[1] / len, dz = dir[2] - s[2] / len;
        float flux = light * (GALAXY_STAR_MIN + (float) Math.pow(rank / chance, GALAXY_STAR_POWER));
        float t = galaxyHash(ix, iy, iz + 3);
        float[] colour = t < 0.35f ? new float[]{0.7f, 0.8f, 1.0f} : t < 0.6f ? new float[]{1, 1, 1} : t < 0.82f ? new float[]{1.0f, 0.7f, 0.92f} : new float[]{1.0f, 0.86f, 0.62f};
        float k = flux * (float) Math.exp(-(dx * dx + dy * dy + dz * dz) / (2 * sigma * sigma));
        return new float[]{colour[0] * k, colour[1] * k, colour[2] * k};
    }

    /** galaxy.glsl galaxy, mapTexel the map's stored rgb (0..1) along dir. */
    public static float[] galaxy(float[] dir, float strength, float pixelAngle, float[] mapTexel) {
        if (!(strength > 0)) return new float[3];
        float band = Math.max(0, Math.min(1, galaxyDisc(dir) * 1.5f));
        float sigma = Math.max(pixelAngle, 1e-5f) * GALAXY_STAR_SIGMA;
        float[] f = galaxyStars(dir, GALAXY_FAINT_CELLS, GALAXY_FAINT_BASE + GALAXY_FAINT_BAND * band, GALAXY_FAINT_LIGHT, 1, sigma);
        float[] s = galaxyStars(dir, GALAXY_BRIGHT_CELLS, GALAXY_BRIGHT_BASE + GALAXY_BRIGHT_BAND * band, GALAXY_BRIGHT_LIGHT, 2, sigma);
        float[] r = galaxyStars(dir, GALAXY_GRAIN_CELLS, GALAXY_GRAIN_BAND * band * band, GALAXY_GRAIN_LIGHT, 3, sigma);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = (mapTexel[i] * mapTexel[i] * GALAXY_MAP_SCALE + (f[i] + s[i] + r[i]) * GALAXY_STAR_BRIGHTNESS) * strength;
        return out;
    }
}
