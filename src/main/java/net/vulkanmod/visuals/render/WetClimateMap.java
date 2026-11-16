package net.vulkanmod.visuals.render;

/**
 * 2026-11-16 (the user: "사막 지형에서는 비가 내리지 않기 때문에 비온 후 마르는 이팩트도 생기면 안돼"): where the rain falls, for the wet
 * ground (wetground.glsl's wetClimateAt) and PBR's wetness. Until now one wetness, the rain at the camera, wetted every open surface on the
 * screen: from a plain the desert next to it got wet, and walking into a desert after the rain it dried there for 40 s.
 * <p>
 * The map holds, for each CELL x CELL column of blocks (a biome's own resolution) within SIZE / 2 cells of the camera, whether vanilla's
 * rule rains on its ground: Biome.getPrecipitationAt at the first free block above the cell's middle column is RAIN (not NONE, as in
 * deserts, badlands and savannas, and not SNOW, as on snowy ground and high mountains). It is addressed by cell &amp; MASK (it scrolls
 * with the camera without moving anything); each texel carries which of the four wraps along x and z it stands for, so a pixel more than
 * the map away reads no stale cell. Cells are looked up nearest first as they come into the map, then refreshed round-robin, a budget a
 * update. A cell whose chunk is not loaded is not known (0: dry).
 * <p>
 * Pure (WetClimateTest); WetClimate feeds it from the client level, VkPostProcess uploads it.
 */
public final class WetClimateMap {
    public static final int SIZE = 128, MASK = SIZE - 1, HALF = SIZE / 2, SHIFT = 7;
    /** The composite's binding for the map (VkPostProcess.COPY_WET_CLIMATE) and its slot for wetClimate (params). */
    public static final int BINDING = 28, SLOT = 75;
    /** Blocks per cell along x and z. */
    public static final int CELL = 4;
    /** The camera's position wraps here (blocks) in the composite's wetClimate.xy: four wraps of the map, as the tag counts them. */
    public static final int WRAP = 4 * SIZE * CELL;
    /** Blocks from the camera (along x or z) the map answers for; past it the composite takes the rain at the camera. */
    public static final int REACH = (HALF - 1) * CELL;
    /** Cells looked up an update (each a height, a biome and a temperature): the whole map takes SIZE^2 / BUDGET updates. */
    public static final int BUDGET = 512;
    /** The texel's bits: known, rains there, then the wrap tag (4 bits) from TAG_SHIFT. */
    public static final int KNOWN = 1, RAINS = 2, TAG_SHIFT = 2;
    /** Heights the level reports for a column whose chunk is not loaded. */
    public static final int NO_HEIGHT = Integer.MIN_VALUE;

    /** The level as the map reads it. */
    public interface Columns {
        /** The first free block above the column's highest MOTION_BLOCKING block, or NO_HEIGHT when its chunk is not loaded. */
        int height(int x, int z);

        /** Whether vanilla's rule rains (not nothing, not snow) at (x, y, z). */
        boolean rains(int x, int y, int z);
    }

    private final int[] texels = new int[SIZE * SIZE];
    private final int[] cellX = new int[SIZE * SIZE], cellZ = new int[SIZE * SIZE];
    private int cursor;
    private boolean anyRain;

    public WetClimateMap() {
        java.util.Arrays.fill(this.cellX, Integer.MIN_VALUE);
    }

    /** The packed cells (row cell z &amp; MASK, column cell x &amp; MASK), as uploaded. */
    public int[] texels() {
        return this.texels;
    }

    /** Whether a known cell in the map rains (the frame's wetness only rises then). */
    public boolean anyRain() {
        return this.anyRain;
    }

    /** Forgets every cell (a new level). */
    public void clear() {
        java.util.Arrays.fill(this.texels, 0);
        java.util.Arrays.fill(this.cellX, Integer.MIN_VALUE);
        java.util.Arrays.fill(this.cellZ, 0);
        this.anyRain = false;
    }

    /** The cell holding block coordinate v. */
    public static int cellOf(int v) {
        return Math.floorDiv(v, CELL);
    }

    /** The texel for a cell (cx, cz): known, whether it rains, its wrap tag. */
    public static int pack(int cx, int cz, boolean rains) {
        return KNOWN | (rains ? RAINS : 0) | (tag(cx, cz) << TAG_SHIFT);
    }

    /** Which of the four map wraps along x and z a cell stands for. */
    public static int tag(int cx, int cz) {
        return ((cx >> SHIFT) & 3) | (((cz >> SHIFT) & 3) << 2);
    }

    static int index(int cx, int cz) {
        return (cx & MASK) + SIZE * (cz & MASK);
    }

    /** The map around the camera's block (x, z); true when a texel changed (the map must be uploaded again). */
    public boolean update(Columns level, int x, int z, int budget) {
        int ccx = cellOf(x), ccz = cellOf(z);
        int left = budget;
        boolean changed = false;
        //New cells first, nearest first: rings of growing Chebyshev distance
        for (int r = 0; r < HALF && left > 0; r++) {
            for (int dz = -r; dz <= r && left > 0; dz++) {
                int step = dz == -r || dz == r ? 1 : 2 * r;
                for (int dx = -r; dx <= r && left > 0; dx += Math.max(step, 1)) {
                    int cx = ccx + dx, cz = ccz + dz;
                    int i = index(cx, cz);
                    if (this.cellX[i] != cx || this.cellZ[i] != cz) {
                        changed |= this.lookUp(level, i, cx, cz);
                        left--;
                    }
                }
            }
        }
        //Then the oldest again (blocks change the ground's height; a chunk loads)
        for (int n = 0; n < SIZE * SIZE && left > 0; n++) {
            int i = this.cursor;
            this.cursor = (this.cursor + 1) % (SIZE * SIZE);
            int cx = ccx - HALF + Math.floorMod((i & MASK) - (ccx - HALF), SIZE);
            int cz = ccz - HALF + Math.floorMod((i >> SHIFT) - (ccz - HALF), SIZE);
            if (this.cellX[i] == cx && this.cellZ[i] == cz) {
                changed |= this.lookUp(level, i, cx, cz);
                left--;
            }
        }
        boolean any = false;
        for (int i = 0; i < SIZE * SIZE && !any; i++) {
            any = (this.texels[i] & RAINS) != 0;
        }
        this.anyRain = any;
        return changed;
    }

    private boolean lookUp(Columns level, int i, int cx, int cz) {
        int texel;
        try {
            int x = cx * CELL + CELL / 2, z = cz * CELL + CELL / 2;
            int h = level.height(x, z);
            texel = h == NO_HEIGHT ? 0 : pack(cx, cz, level.rains(x, h, z));
        } catch (RuntimeException e) {
            texel = 0;
        }
        this.cellX[i] = cx;
        this.cellZ[i] = cz;
        if (this.texels[i] == texel) return false;
        this.texels[i] = texel;
        return true;
    }

    /**
     * The composite's wetClimate: xy the camera's x, z wrapped at WRAP, z 1 (the map is read), w whether it rains at the camera (for
     * pixels past REACH). All 0 without a map: the composite then wets by the frame's wetness alone, as before.
     */
    public static float[] params(boolean ready, double cameraX, double cameraZ, boolean rainsAtCamera) {
        if (!ready) return new float[4];
        return new float[]{(float) wrap(cameraX), (float) wrap(cameraZ), 1f, rainsAtCamera ? 1f : 0f};
    }

    static double wrap(double v) {
        return v - WRAP * Math.floor(v / WRAP);
    }

    /** wetground.glsl's wetClimateAt for a pixel at rel (blocks from the camera) with these params and texels (1: rain falls there). */
    public static float at(float[] params, int[] texels, double relX, double relZ) {
        if (!(params[2] > 0)) return 1f;
        if (Math.abs(relX) > REACH || Math.abs(relZ) > REACH) return params[3];
        int cx = (int) Math.floor((params[0] + relX) / CELL), cz = (int) Math.floor((params[1] + relZ) / CELL);
        int w = texels[index(cx, cz)];
        if ((w & KNOWN) == 0 || ((w >> TAG_SHIFT) & 15) != tag(cx, cz)) return 0f;
        return (w & RAINS) != 0 ? 1f : 0f;
    }
}
