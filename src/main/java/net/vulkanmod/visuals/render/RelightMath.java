package net.vulkanmod.visuals.render;

/** P7l design 3.3-3.5: the relighting maths, mirrored by lightvolume.glsl and scene_copy.comp (change both together). */
public final class RelightMath {
    /** Largest linear gain from a lifted sky light: 8-bit colour near black shows its steps when stretched further. */
    public static final float MAX_GAIN = 4f;
    /** The side sky ring around water: axes at SIDE_NEAR and SIDE_FAR blocks, diagonals at the rounded 0.707 of them. */
    public static final int SIDE_NEAR = 16, SIDE_FAR = 32, SIDE_DIAGONAL_NEAR = 11, SIDE_DIAGONAL_FAR = 23;
    /**
     * The share of the target light water's body scatters back, its scattered light at least this bright where the gain hit
     * the cap (gate G7l: 0.2 everywhere turned all near water a bright teal; about open water's own body brightness).
     */
    public static final float WATER_ALBEDO = 0.08f;

    private RelightMath() {
    }

    /** strength: Sky Light Ambient x day. */
    public static float lift(float sunVisible, float strength) {
        return clamp01(sunVisible) * clamp01(strength);
    }

    public static float liftedSky(float sky, float lift) {
        return sky + (15 - sky) * clamp01(lift);
    }

    /** Linear lightmap colours, one channel. */
    public static float gain(float vanillaLinear, float targetLinear) {
        return Math.min(targetLinear / Math.max(vanillaLinear, 1e-4f), MAX_GAIN);
    }

    /** Gate G7l: the gain hit MAX_GAIN (vanilla too dark to scale, e.g. an island's black core): water then gets its floor. */
    public static boolean capped(float vanillaLinear, float targetLinear) {
        return targetLinear / Math.max(vanillaLinear, 1e-4f) > MAX_GAIN;
    }

    public static float skyShare(float sky) {
        return clamp01(sky / 15f);
    }

    public static float vis(float sky) {
        float v = clamp01(sky / 15f);
        return v * v;
    }

    public static float waterSky(float sky, float side) {
        return Math.max(vis(sky), clamp01(side));
    }

    public static float waterLiftedSky(float lifted, float waterSky) {
        return Math.max(lifted, 15 * clamp01(waterSky));
    }

    /** The ring's sky levels (-1 unknown): the brightest known / 15, 0 when none is known. */
    public static float side(int[] skyLevels) {
        int best = 0;
        for (int s : skyLevels) best = Math.max(best, s);
        return best / 15f;
    }

    /** The 16 ring offsets (x, z): at SIDE_NEAR then SIDE_FAR, +x, -x, +z, -z, then the four diagonals. */
    public static int[][] ring() {
        int[][] r = new int[16][];
        for (int i = 0; i < 2; i++) {
            int d = i == 0 ? SIDE_NEAR : SIDE_FAR, k = i == 0 ? SIDE_DIAGONAL_NEAR : SIDE_DIAGONAL_FAR;
            int[][] o = {{d, 0}, {-d, 0}, {0, d}, {0, -d}, {k, k}, {k, -k}, {-k, k}, {-k, -k}};
            System.arraycopy(o, 0, r, i * 8, 8);
        }
        return r;
    }

    /** Gate G7l: the face's dominant world axis (0 x, 1 y, 2 z) from its normal. */
    public static int faceAxis(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        return ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
    }

    /**
     * Gate G7l: the cell layer in front of a face at surface coordinate s along its axis (sign: the normal's), half a block
     * out. Vanilla lights a face by the air in front of it; the half-block step keeps depth noise from picking the block itself.
     */
    public static int frontLayer(float s, int sign) {
        return (int) Math.floor(s + 0.5f * sign);
    }

    /**
     * Sky and block light from the 4 front-layer cells around a point (index bit 0: u + 1, bit 1: v + 1; -1 unknown) at
     * fractions tu, tv, bilinear. Solid cells (0) are skipped while any other is lit; with all weight on skipped cells the
     * lit ones count equally. {sky, block, 1}, or {0, 0, 0} when a cell is unknown.
     */
    public static float[] faceBlend(int[] cells, float tu, float tv) {
        boolean anyLit = false;
        for (int c : cells) {
            if (c < 0) return new float[]{0, 0, 0};
            anyLit |= c != 0;
        }
        float sky = 0, block = 0, weights = 0, plainSky = 0, plainBlock = 0, count = 0;
        for (int i = 0; i < 4; i++) {
            if (anyLit && cells[i] == 0) continue;
            float w = ((i & 1) != 0 ? tu : 1 - tu) * ((i & 2) != 0 ? tv : 1 - tv);
            sky += (cells[i] & 15) * w;
            block += (cells[i] >> 4) * w;
            weights += w;
            plainSky += cells[i] & 15;
            plainBlock += cells[i] >> 4;
            count++;
        }
        return weights > 1e-6f ? new float[]{sky / weights, block / weights, 1} : new float[]{plainSky / count, plainBlock / count, 1};
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
