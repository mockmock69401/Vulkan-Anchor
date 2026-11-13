package net.vulkanmod.visuals.render;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * 2026-11-13 (shader roadmap session 19): the End galaxy's band, its dust and the nebulae (galaxy.glsl's map), baked once on the CPU into
 * an equirectangular RGBA8 image (galaxy.glsl galaxyMapCoord's layout; rgb the square root of the light over GalaxyMath.GALAXY_MAP_SCALE,
 * a 1). Value-noise fBm the composite took 4 to 5 ms a frame for on an AMD iGPU at 1280x720 (about 0.35 ms on the RTX 5070 at 2560x1440):
 * now one texture read. Baked on the common pool the first time the End's galaxy is wanted (start()), about a second; the composite
 * draws no galaxy until the map is uploaded.
 * <p>
 * THE MODEL (a painted sky; the shapes after the Milky Way as photographed, e.g. Mellinger 2009, PASP 121), in galactic coordinates
 * (GalaxyMath.galactic) with the disc (GalaxyMath.galaxyDisc):
 * <ul>
 * <li>the bulge: a warm ellipse around the core;</li>
 * <li>star clouds: the disc's light times value-noise fBm squashed across the band (structure stretched along it), its octaves turned by
 * a fixed rotation (no grid-aligned streaks); the brighter clouds pinker;</li>
 * <li>dust: patches where another fBm (warped by the clouds) passes a threshold take light away, most along the band's middle;</li>
 * <li>nebulae: two faint low-frequency fBm fields (magenta and blue) over the whole sky, brighter near the band.</li>
 * </ul>
 */
public final class GalaxyMap {
    public static final int WIDTH = 4096, HEIGHT = 2048;
    public static final float GALAXY_BULGE_L = 0.32f;
    public static final float GALAXY_BULGE_B = 0.16f;
    public static final float GALAXY_BULGE_GAIN = 1.6f;
    public static final float GALAXY_CLOUD_CELLS = 9.0f;
    public static final float GALAXY_CLOUD_SQUASH = 2.0f;
    public static final int GALAXY_CLOUD_OCTAVES = 5;
    public static final float GALAXY_CLOUD_BASE = 0.25f;
    public static final float GALAXY_CLOUD_LOW = 0.25f;
    public static final float GALAXY_CLOUD_GAIN = 2.2f;
    public static final float GALAXY_CLOUD_MAX = 1.6f;
    public static final float GALAXY_HOT_LOW = 0.5f;
    public static final float GALAXY_HOT_RANGE = 0.3f;
    public static final float GALAXY_DUST_CELLS = 14.0f;
    public static final float GALAXY_DUST_SQUASH = 2.0f;
    public static final float GALAXY_DUST_WARP = 1.5f;
    public static final float GALAXY_DUST_DEPTH = 0.85f;
    public static final float GALAXY_DUST_WIDTH = 0.07f;
    public static final float GALAXY_DUST_LOW = 0.45f;
    public static final float GALAXY_DUST_RANGE = 0.2f;
    public static final float GALAXY_DUST_OUTSIDE = 0.35f;
    public static final float GALAXY_NEBULA_CELLS = 2.2f;
    public static final float GALAXY_NEBULA_LOW = 0.45f;
    public static final float GALAXY_NEBULA_A_GAIN = 0.35f;
    public static final float GALAXY_NEBULA_B_LOW = 0.5f;
    public static final float GALAXY_NEBULA_B_GAIN = 0.2f;
    public static final float GALAXY_NEBULA_FLOOR = 0.25f;
    public static final float GALAXY_LACUNARITY = 2.03f;
    public static final float GALAXY_OCTAVE_SHIFT = 17.0f;
    /** The band's light is worked out only where the disc's could pass this (beyond: the nebulae only). */
    public static final float GALAXY_BAND_CUT = 0.002f;
    /** The light's scale (scene units at strength 1). */
    public static final float GALAXY_BRIGHTNESS = 0.12f;

    private static volatile CompletableFuture<byte[]> bake;

    private GalaxyMap() {
    }

    /** Starts the bake once (on the common pool); later calls do nothing. */
    public static void start() {
        if (bake == null) {
            synchronized (GalaxyMap.class) {
                if (bake == null) bake = CompletableFuture.supplyAsync(() -> bake(WIDTH, HEIGHT));
            }
        }
    }

    /** The baked map (WIDTH x HEIGHT RGBA8, rows from straight up), or null while it bakes or before start(). */
    public static byte[] ready() {
        CompletableFuture<byte[]> b = bake;
        return b != null && b.isDone() && !b.isCompletedExceptionally() ? b.join() : null;
    }

    /** The map at a size, rows in parallel. */
    public static byte[] bake(int width, int height) {
        byte[] out = new byte[width * height * 4];
        IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                float[] c = light(GalaxyMath.mapDirection((x + 0.5) / width, (y + 0.5) / height));
                int i = (y * width + x) * 4;
                for (int k = 0; k < 3; k++) {
                    double v = Math.sqrt(Math.max(0, Math.min(1, c[k] / GalaxyMath.GALAXY_MAP_SCALE)));
                    out[i + k] = (byte) Math.round(v * 255);
                }
                out[i + 3] = (byte) 255;
            }
        });
        return out;
    }

    /** fBm of octaves value noises (GalaxyMath.galaxyNoise), each GALAXY_LACUNARITY finer, half as strong and turned by a fixed rotation. */
    public static float fbm(float x, float y, float z, int octaves) {
        float sum = 0, amp = 0.5f, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * GalaxyMath.galaxyNoise(x, y, z);
            norm += amp;
            //An orthonormal turn (rows (0, 0.8, 0.6), (-0.8, 0.36, -0.48), (-0.6, -0.48, 0.64)): the octaves' lattices do not line up
            float tx = 0.8f * y + 0.6f * z, ty = -0.8f * x + 0.36f * y - 0.48f * z, tz = -0.6f * x - 0.48f * y + 0.64f * z;
            x = tx * GALAXY_LACUNARITY + GALAXY_OCTAVE_SHIFT;
            y = ty * GALAXY_LACUNARITY + GALAXY_OCTAVE_SHIFT;
            z = tz * GALAXY_LACUNARITY + GALAXY_OCTAVE_SHIFT;
            amp *= 0.5f;
        }
        return sum / norm;
    }

    /** The band's and the nebulae's light along dir (scene units at strength 1, rgb). */
    public static float[] light(float[] dir) {
        float px = GalaxyMath.GALAXY_POLE_X, py = GalaxyMath.GALAXY_POLE_Y, pz = GalaxyMath.GALAXY_POLE_Z;
        float cx = GalaxyMath.GALAXY_CORE_X, cy = GalaxyMath.GALAXY_CORE_Y, cz = GalaxyMath.GALAXY_CORE_Z;
        float sx = py * cz - pz * cy, sy = pz * cx - px * cz, sz = px * cy - py * cx;
        float[] g = {dir[0] * cx + dir[1] * cy + dir[2] * cz, dir[0] * px + dir[1] * py + dir[2] * pz, dir[0] * sx + dir[1] * sy + dir[2] * sz};
        float[] bl = GalaxyMath.galactic(dir);
        float b = bl[0], l = bl[1];
        float nearCore = (float) Math.exp(-(l / GalaxyMath.GALAXY_BULGE_REACH) * (l / GalaxyMath.GALAXY_BULGE_REACH));
        float disc = GalaxyMath.galaxyDisc(dir);
        float[] light = new float[3];
        if (disc > GALAXY_BAND_CUT) {
            float bulge = (float) Math.exp(-((l / GALAXY_BULGE_L) * (l / GALAXY_BULGE_L) + (b / GALAXY_BULGE_B) * (b / GALAXY_BULGE_B)));
            float clouds = fbm(g[0] * GALAXY_CLOUD_CELLS, g[1] * GALAXY_CLOUD_SQUASH * GALAXY_CLOUD_CELLS, g[2] * GALAXY_CLOUD_CELLS, GALAXY_CLOUD_OCTAVES);
            float mottle = (float) Math.pow(Math.max(0, Math.min(GALAXY_CLOUD_MAX, (clouds - GALAXY_CLOUD_LOW) * GALAXY_CLOUD_GAIN)), 1.5);
            float w = (clouds - 0.5f) * GALAXY_DUST_WARP;
            float dn = fbm(g[0] * GALAXY_DUST_CELLS + 40 + w, g[1] * GALAXY_DUST_SQUASH * GALAXY_DUST_CELLS + 40 + w, g[2] * GALAXY_DUST_CELLS + 40 + w, GALAXY_CLOUD_OCTAVES);
            float laneWidth = GALAXY_DUST_WIDTH * (1 + 0.8f * nearCore);
            float lb = (b - 0.02f * (float) Math.sin(3 * l)) / laneWidth;
            float lane = (float) Math.exp(-lb * lb);
            float spot = Math.max(0, Math.min(1, (dn - GALAXY_DUST_LOW) / GALAXY_DUST_RANGE));
            float dust = spot * spot * (GALAXY_DUST_OUTSIDE + (1 - GALAXY_DUST_OUTSIDE) * lane);
            float hot = Math.max(0, Math.min(1, (clouds - GALAXY_HOT_LOW) / GALAXY_HOT_RANGE));
            float dl = disc * (GALAXY_CLOUD_BASE + mottle), bg = bulge * GALAXY_BULGE_GAIN, keep = 1 - GALAXY_DUST_DEPTH * dust;
            //The disc blue-violet (0.7, 0.62, 1) to pink (1, 0.6, 0.9) in the bright clouds; the bulge warm (1, 0.74, 0.7)
            light[0] = ((0.7f + 0.3f * hot) * dl + 1.0f * bg) * keep;
            light[1] = ((0.62f - 0.02f * hot) * dl + 0.74f * bg) * keep;
            light[2] = ((1.0f - 0.1f * hot) * dl + 0.7f * bg) * keep;
        }
        float na = fbm(g[0] * GALAXY_NEBULA_CELLS + 90, g[1] * GALAXY_NEBULA_CELLS + 90, g[2] * GALAXY_NEBULA_CELLS + 90, 3);
        float nc = GALAXY_NEBULA_CELLS * 1.7f;
        float nb = fbm(g[0] * nc + 130, g[1] * nc + 130, g[2] * nc + 130, 2);
        float ma = Math.max(0, Math.min(1, (na - GALAXY_NEBULA_LOW) * 3));
        float mb = Math.max(0, Math.min(1, (nb - GALAXY_NEBULA_B_LOW) * 3));
        float wa = ma * ma * GALAXY_NEBULA_A_GAIN, wb = mb * mb * GALAXY_NEBULA_B_GAIN;
        float near = GALAXY_NEBULA_FLOOR + (1 - GALAXY_NEBULA_FLOOR) * Math.max(0, Math.min(1, disc * 2));
        //Magenta (0.95, 0.35, 0.95) and blue (0.35, 0.45, 1)
        light[0] += (0.95f * wa + 0.35f * wb) * near;
        light[1] += (0.35f * wa + 0.45f * wb) * near;
        light[2] += (0.95f * wa + 1.0f * wb) * near;
        for (int k = 0; k < 3; k++) light[k] *= GALAXY_BRIGHTNESS;
        return light;
    }
}
