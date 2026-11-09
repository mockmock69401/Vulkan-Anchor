package net.vulkanmod.visuals.render;

/**
 * 2026-11-09 (session 6): the precipitation map weather.glsl reads: for each world column around the camera (WEATHER_MAP_SIZE per side,
 * addressed by world x, z & WEATHER_MAP_MASK, so it scrolls with the camera without moving anything), vanilla's MOTION_BLOCKING height
 * (where rain stops: roofs, leaves, water) and the column's climate (whether its biome has precipitation, whether it snows at and below
 * y 80, and the snow line above it), packed by WeatherMath.pack. The heights are read again every frame (blocks change); the climate,
 * which costs biome and temperature lookups, is looked up for columns that came into the map first, nearest first, then refreshed
 * round-robin, at most a budget of columns per frame. A column whose chunk is not loaded or whose climate is not known yet is 0 (no
 * precipitation drawn there).
 */
public final class PrecipitationMap {
    public static final int SIZE = WeatherMath.WEATHER_MAP_SIZE, MASK = WeatherMath.WEATHER_MAP_MASK, HALF = SIZE / 2;
    /** Columns height() reports when their chunk is not loaded. */
    public static final int NO_HEIGHT = Integer.MIN_VALUE;
    /** Climate lookups per frame (each a biome and up to about 13 temperature lookups); the whole map takes SIZE^2 / BUDGET frames. */
    public static final int BUDGET = 512;
    /** hasRain and hasSnow look this far around the camera: the far layer's end and one of its cells. */
    public static final int REACH = (int) WeatherMath.WEATHER_LAYER_END_2 + (1 << (WeatherMath.WEATHER_LAYERS - 1)) + 1;

    /** A column's climate: precipitation, snow at and below WeatherMath.WEATHER_TEMPERATURE_Y, the lowest y above it where it snows. */
    public record Climate(boolean precipitation, boolean snowLow, int snowLine) {
        public static final Climate NONE = new Climate(false, false, WeatherMath.Y_MAX);
    }

    /** The level as the map reads it. */
    public interface Columns {
        /** The first free block above the column's highest MOTION_BLOCKING block, or NO_HEIGHT when its chunk is not loaded. */
        int height(int x, int z);

        /** The climate of the biome at (x, y, z) (vanilla reads the biome at the camera's height). */
        Climate climate(int x, int y, int z);

        /** 2026-11-10: whether the column's top MOTION_BLOCKING block holds water (looked up with the climate). */
        default boolean water(int x, int z) {
            return false;
        }
    }

    private final int[] texels = new int[SIZE * SIZE];
    private final int[] climateX = new int[SIZE * SIZE], climateZ = new int[SIZE * SIZE];
    private final Climate[] climates = new Climate[SIZE * SIZE];
    private final boolean[] water = new boolean[SIZE * SIZE];
    private int cursor;
    private boolean rain, snow;

    public PrecipitationMap() {
        java.util.Arrays.fill(this.climateX, Integer.MIN_VALUE);
    }

    /** The packed columns (row z & MASK, column x & MASK), as uploaded. */
    public int[] texels() {
        return this.texels;
    }

    /** Whether the last update's map holds, within REACH of the camera, a column where it rains, or snows, for the camera's height (weatherKind). */
    public boolean hasRain() {
        return this.rain;
    }

    public boolean hasSnow() {
        return this.snow;
    }

    /** Forgets every column (a new level). */
    public void clear() {
        java.util.Arrays.fill(this.texels, 0);
        java.util.Arrays.fill(this.climateX, Integer.MIN_VALUE);
        java.util.Arrays.fill(this.climates, null);
        java.util.Arrays.fill(this.water, false);
    }

    /** The map around the camera's block (cx, cy, cz); true when a texel changed (the map must be uploaded again). */
    public boolean update(Columns level, int cx, int cy, int cz, int budget) {
        int left = budget;
        //New columns first, nearest first: rings of growing Chebyshev distance
        for (int r = 0; r < HALF && left > 0; r++) {
            for (int dz = -r; dz <= r && left > 0; dz++) {
                int step = dz == -r || dz == r ? 1 : 2 * r;
                for (int dx = -r; dx <= r && left > 0; dx += Math.max(step, 1)) {
                    int x = cx + dx, z = cz + dz;
                    int i = index(x, z);
                    if (this.climateX[i] != x || this.climateZ[i] != z) {
                        this.lookUp(level, i, x, cy, z);
                        left--;
                    }
                }
            }
        }
        //Then the oldest ones again (the camera's height changes the biome vanilla reads; a modded biome may change)
        for (int n = 0; n < SIZE * SIZE && left > 0; n++) {
            int i = this.cursor;
            this.cursor = (this.cursor + 1) % (SIZE * SIZE);
            int x = cx - HALF + Math.floorMod((i & MASK) - (cx - HALF), SIZE);
            int z = cz - HALF + Math.floorMod((i >> 7) - (cz - HALF), SIZE);
            if (this.climateX[i] == x && this.climateZ[i] == z) {
                this.lookUp(level, i, x, cy, z);
                left--;
            }
        }
        boolean changed = false;
        this.rain = false;
        this.snow = false;
        for (int z = cz - HALF; z < cz + HALF; z++) {
            for (int x = cx - HALF; x < cx + HALF; x++) {
                int i = index(x, z);
                Climate c = this.climateX[i] == x && this.climateZ[i] == z ? this.climates[i] : null;
                int h = c == null ? NO_HEIGHT : level.height(x, z);
                int texel = h == NO_HEIGHT ? 0 : WeatherMath.pack(h, c.snowLine(), c.precipitation(), c.snowLow(), this.water[i]);
                //Only the columns the shader's walk can reach (its far layer's end and a cell)
                if (Math.abs(x - cx) <= REACH && Math.abs(z - cz) <= REACH) {
                    int kind = WeatherMath.kind(texel, cy);
                    this.rain |= kind == WeatherMath.RAIN;
                    this.snow |= kind == WeatherMath.SNOW;
                }
                if (this.texels[i] != texel) {
                    this.texels[i] = texel;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void lookUp(Columns level, int i, int x, int y, int z) {
        Climate c;
        boolean w;
        try {
            c = level.climate(x, y, z);
            w = c != null && c.precipitation() && level.water(x, z);
        } catch (RuntimeException e) {
            c = Climate.NONE;
            w = false;
        }
        this.water[i] = w;
        this.climates[i] = c != null ? c : Climate.NONE;
        this.climateX[i] = x;
        this.climateZ[i] = z;
    }

    static int index(int x, int z) {
        return (x & MASK) + SIZE * (z & MASK);
    }

    /**
     * The snow line from vanilla's rule by bisection: snows(y) is Biome.getPrecipitationAt(x, y, z) == SNOW, which for y above
     * WEATHER_TEMPERATURE_Y falls monotonically with the height (Biome.getHeightAdjustedTemperature). Y_MAX when it does not snow even there.
     */
    public static int snowLine(java.util.function.IntPredicate snows) {
        int lo = WeatherMath.WEATHER_TEMPERATURE_Y + 1, hi = WeatherMath.Y_MAX;
        if (!snows.test(hi)) return WeatherMath.Y_MAX;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (snows.test(mid)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}
