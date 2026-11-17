package net.vulkanmod.visuals.render;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * 2026-11-16 (shader roadmap session 12 follow-up): the anime star type's Milky Way band (AnimeStarMath, animestars.glsl), baked once on
 * the CPU into a small equirectangular RGBA8 map in the stars' frame (galaxy.glsl galaxyMapCoord's layout of AnimeStarMath.skyDir):
 * r the square root of the band's glow over ANIME_MAP_SCALE, g the fine dust stars' density 0..1, b the clump field 0..1 (2026-11-17: where
 * the small and big stars gather or thin out over the whole sky, so they do not spread evenly; AnimeStarMath.ANIME_CLUMP_*). Baked on the common pool the first
 * time the anime stars are wanted (start(), well under a second); until it is uploaded the composite draws the stars without the band.
 * <p>
 * THE BAND (a painted band after the user's screenshot, not a photograph): a broad soft great circle (latitude b from its pole, longitude
 * l from a brighter part), wider and brighter around that part; soft clouds (value-noise fBm, GalaxyMap.fbm, stretched along the band)
 * shape its glow and its dust stars; darker gaps (another fBm, warped by the clouds, past a threshold) cut into its middle; a faint haze
 * reaches a little beyond it. At midnight the band climbs from the horizon to about 65 degrees up, across the sky at a slant.
 */
public final class AnimeSkyMap {
    public static final int WIDTH = 1024, HEIGHT = 512;
    /** The glow's scale in the map (the shader's ANIME_MAP_SCALE). */
    public static final float ANIME_MAP_SCALE = 1.3f;
    /** The band's pole and its brighter part at midnight, in world directions (the stars' frame turns them: x, y negated). */
    public static final float POLE_X = 0.62f, POLE_Y = 0.42f, POLE_Z = 0.66f;
    /** The bright part's turn along the band from its highest point (radians). */
    public static final float CORE_TURN = 0.7f;
    public static final float WIDTH_B = 0.24f, WIDEN = 0.6f, WIDEN_REACH = 0.9f, ALONG_FLOOR = 0.5f, ALONG_REACH = 1.5f;
    public static final float CLOUD_ALONG = 3.2f, CLOUD_ACROSS = 7.0f, CLOUD_LOW = 0.32f, CLOUD_HIGH = 0.72f;
    public static final float GLOW_BASE = 0.25f, GLOW_CLOUD = 1.1f;
    public static final float WISP_CELLS = 9.0f, WISP_LOW = 0.75f, WISP_GAIN = 0.5f;
    public static final float GAP_ALONG = 4.5f, GAP_ACROSS = 9.0f, GAP_WARP = 1.2f, GAP_LOW = 0.5f, GAP_HIGH = 0.7f, GAP_WIDTH = 0.6f,
            GAP_DEPTH = 0.7f;
    public static final float HAZE = 0.05f, HAZE_WIDTH = 2.2f;
    public static final float DUST_BASE = 0.25f, DUST_GAIN = 1.4f, DUST_GAP = 0.9f;
    /** The clump field: fBm at CLUMP_SCALE over the stars' frame, CLUMP_LOW..CLUMP_HIGH smoothed to 0..1. */
    public static final float CLUMP_SCALE = 5.0f, CLUMP_LOW = 0.3f, CLUMP_HIGH = 0.7f;

    private static final float[] POLE, CORE, SIDE;

    static {
        float[] p = norm(new float[]{POLE_X, POLE_Y, POLE_Z});
        //the band's highest point: up less its part along the pole
        float[] h = norm(new float[]{-p[1] * p[0], 1 - p[1] * p[1], -p[1] * p[2]});
        float[] s = cross(p, h);
        float c = (float) Math.cos(CORE_TURN), n = (float) Math.sin(CORE_TURN);
        float[] core = {h[0] * c + s[0] * n, h[1] * c + s[1] * n, h[2] * c + s[2] * n};
        POLE = toSky(p);
        CORE = toSky(norm(core));
        SIDE = cross(POLE, CORE);
    }

    private static volatile CompletableFuture<byte[]> bake;

    private AnimeSkyMap() {
    }

    /** A world direction at midnight in the stars' frame (AnimeStarMath.skyDir with the sun straight down). */
    static float[] toSky(float[] d) {
        return new float[]{-d[0], -d[1], d[2]};
    }

    private static float[] norm(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[]{v[0] / l, v[1] / l, v[2] / l};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float smooth(float lo, float hi, float x) {
        float t = Math.max(0, Math.min(1, (x - lo) / (hi - lo)));
        return t * t * (3 - 2 * t);
    }

    /** Starts the bake once (on the common pool); later calls do nothing. */
    public static void start() {
        if (bake == null) {
            synchronized (AnimeSkyMap.class) {
                if (bake == null) bake = CompletableFuture.supplyAsync(() -> bake(WIDTH, HEIGHT));
            }
        }
    }

    /** The baked map (WIDTH x HEIGHT RGBA8, rows from the stars' frame's +y), or null while it bakes or before start(). */
    public static byte[] ready() {
        CompletableFuture<byte[]> b = bake;
        return b != null && b.isDone() && !b.isCompletedExceptionally() ? b.join() : null;
    }

    /** The map at a size, rows in parallel. */
    public static byte[] bake(int width, int height) {
        byte[] out = new byte[width * height * 4];
        IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                float[] t = texel(GalaxyMath.mapDirection((x + 0.5) / width, (y + 0.5) / height));
                int i = (y * width + x) * 4;
                out[i] = (byte) Math.round(Math.sqrt(Math.max(0, Math.min(1, t[0] / ANIME_MAP_SCALE))) * 255);
                out[i + 1] = (byte) Math.round(Math.max(0, Math.min(1, t[1])) * 255);
                out[i + 2] = (byte) Math.round(Math.max(0, Math.min(1, t[2])) * 255);
                out[i + 3] = (byte) 255;
            }
        });
        return out;
    }

    /** {the band's glow, the dust stars' density, the clump field} along d (a direction in the stars' frame). */
    public static float[] texel(float[] d) {
        float gp = d[0] * POLE[0] + d[1] * POLE[1] + d[2] * POLE[2];
        float gc = d[0] * CORE[0] + d[1] * CORE[1] + d[2] * CORE[2];
        float gs = d[0] * SIDE[0] + d[1] * SIDE[1] + d[2] * SIDE[2];
        float b = (float) Math.asin(Math.max(-1, Math.min(1, gp)));
        float l = (float) Math.atan2(gs, gc);
        float width = WIDTH_B * (1 + WIDEN * (float) Math.exp(-(l / WIDEN_REACH) * (l / WIDEN_REACH)));
        float base = (float) Math.exp(-(b / width) * (b / width))
                * (ALONG_FLOOR + (1 - ALONG_FLOOR) * (float) Math.exp(-(l / ALONG_REACH) * (l / ALONG_REACH)));
        float haze = HAZE * (float) Math.exp(-(b / (width * HAZE_WIDTH)) * (b / (width * HAZE_WIDTH)));
        float clump = smooth(CLUMP_LOW, CLUMP_HIGH, GalaxyMap.fbm(d[0] * CLUMP_SCALE + 71, d[1] * CLUMP_SCALE + 3, d[2] * CLUMP_SCALE + 29, 4));
        if (base < 0.002f) return new float[]{haze, 0, clump};
        float clouds = GalaxyMap.fbm(gc * CLOUD_ALONG + 5, gs * CLOUD_ALONG, gp * CLOUD_ACROSS, 5);
        float c = smooth(CLOUD_LOW, CLOUD_HIGH, clouds);
        float wisp = GalaxyMap.fbm(gc * WISP_CELLS + 41, gs * WISP_CELLS + 13, gp * WISP_CELLS, 4);
        float glow = base * (GLOW_BASE + GLOW_CLOUD * c) * (WISP_LOW + WISP_GAIN * wisp);
        float g = GalaxyMap.fbm(gc * GAP_ALONG + 31 + GAP_WARP * clouds, gs * GAP_ALONG, gp * GAP_ACROSS + 7, 5);
        float gapWidth = width * GAP_WIDTH;
        float dark = smooth(GAP_LOW, GAP_HIGH, g) * (float) Math.exp(-(b / gapWidth) * (b / gapWidth));
        glow *= 1 - GAP_DEPTH * dark;
        float dust = base * (DUST_BASE + c) * (1 - DUST_GAP * dark) * DUST_GAIN;
        return new float[]{glow + haze, Math.min(1, dust), clump};
    }
}
