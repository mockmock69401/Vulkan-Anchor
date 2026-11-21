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

    /**
     * 2026-11-21 (the user, Tensura pack: bright lines along a cave's ledges): no lift where vanilla's sky light is 0 (no open air to
     * the sky, so the sun visibility there is the shadow map's error), faded in to sky light 1. lightvolume.glsl mirrors this.
     */
    public static float liftedSky(float sky, float lift) {
        return sky + (15 - sky) * clamp01(lift) * clamp01(sky);
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

    /** lightvolume.glsl's LIGHT_SIDE_OPEN_LOW and _HIGH: the share of the horizon seen open over which the side sky fades in. */
    public static final float SIDE_OPEN_LOW = 0.125f, SIDE_OPEN_HIGH = 0.375f;

    /**
     * The side sky from the 8 directions' levels (sideSeen; -1 unknown counts as 0): the brightest / 15, faded in by the share of the
     * horizon seen open (their mean / 15) from SIDE_OPEN_LOW to SIDE_OPEN_HIGH (smoothstep). 2026-11-19: the brightest alone lifted a
     * cave pool's cells to full sky light through one doorway, in blocky patches; the mean alone darkened water under a roof open on
     * two sides and stepped from cell to cell.
     */
    public static float side(int[] directionLevels) {
        int sum = 0, best = 0;
        for (int s : directionLevels) {
            sum += Math.max(s, 0);
            best = Math.max(best, s);
        }
        float t = clamp01((sum / (15f * SIDE_DIRECTIONS) - SIDE_OPEN_LOW) / (SIDE_OPEN_HIGH - SIDE_OPEN_LOW));
        return best / 15f * t * t * (3 - 2 * t);
    }

    /**
     * lightvolume.glsl's LIGHT_SIDE_FULL_SUM: the directions' level sum at which the open share reaches SIDE_OPEN_HIGH (15 x 8 x 0.375),
     * where side is exactly its brightest / 15; with a brightest of 15 that is 1 and the walk's later directions cannot change it.
     */
    public static final int SIDE_FULL_SUM = 45;

    /** The ring's directions: +x, -x, +z, -z and the four diagonals. */
    public static final int SIDE_DIRECTIONS = 8;

    /** lightvolume.glsl's LIGHT_SIDE_STEPS: cells read along each of the 8 directions out to the far ring (the near ring is the middle one). */
    public static final int SIDE_STEPS = 4;

    /** lightvolume.glsl's LIGHT_SIDE_GRID: the world grid (blocks) whose corners the side sky is read at. */
    public static final int SIDE_GRID = 4;

    /**
     * lightvolume.glsl's sideSky blends sideSkyCell (side of the 8 directions' sideSeen) at the 4 corners of the SIDE_GRID-block grid
     * cell around the surface point bilinearly: under a roof open on one side one block's value lay on the water in patches.
     */
    public static float sideBlend(float s00, float s10, float s01, float s11, float tx, float tz) {
        float a = s00 + (s10 - s00) * tx, b = s01 + (s11 - s01) * tx;
        return a + (b - a) * tz;
    }

    /** Cell j (0..SIDE_STEPS-1) along a far-ring offset (x, z), as the shader's integer division gives it. */
    public static int[] sideCell(int[] far, int j) {
        return new int[]{far[0] * (j + 1) / SIDE_STEPS, far[1] * (j + 1) / SIDE_STEPS};
    }

    /**
     * 2026-11-19 (cave water hazy through the rock): the sky level a direction's ring cells give, from the water's own sky level and
     * the sky levels (-1 unknown) of its SIDE_STEPS cells (sideCell) along the far-ring offset far. The walk out from the water stops
     * at an unknown cell or where the level jumps by more than the Manhattan distance between two cells (vanilla's sky light changes
     * by at most 1 per block of open air: a jump is a wall); a ring cell counts only when reached. 0 when none is.
     */
    public static int sideSeen(float sky, int[] levels, int[] far) {
        int best = 0;
        float prev = sky;
        int[] last = {0, 0};
        for (int j = 0; j < SIDE_STEPS; j++) {
            if (levels[j] < 0) break;
            int[] at = sideCell(far, j);
            if (Math.abs(levels[j] - prev) > Math.abs(at[0] - last[0]) + Math.abs(at[1] - last[1])) break;
            if (j == SIDE_STEPS / 2 - 1 || j == SIDE_STEPS - 1) best = Math.max(best, levels[j]);
            if (best == 15) break;
            prev = levels[j];
            last = at;
        }
        return best;
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
