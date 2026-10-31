package net.vulkanmod.visuals.render;

import java.nio.ByteBuffer;

/**
 * P7k design 3: the light of what the player holds, over a SIDE^3 grid around the eye's block; scene_copy reads it where the
 * per-pixel light is known. 2026-10-31 (the user: the held light moved in block steps and banded at block edges): the grid holds the
 * length of the light's way from the eye's cell to each cell, around opaque blocks, and scene_copy takes the level from it and the
 * eye's exact position per pixel (heldLevelAt), so the light follows the eye smoothly between spreads.
 */
public final class HeldLight {
    public static final int RADIUS = 15, SIDE = 2 * RADIUS + 1, CELLS = SIDE * SIDE * SIDE;
    /** The grid's bytes rounded up to whole uints (the shader reads uints). */
    public static final int BYTES = (CELLS + 3) / 4 * 4;
    /**
     * scene_copy's HELD_MAX_GAIN, how far the held light may lift a colour. 2026-10-16 (CTE2 report, held torches too dark): 32
     * kept a torch in a dark room at a third of a placed one (the floor needs about 33x), 256 reaches it.
     */
    public static final float MAX_GAIN = 256f;
    /** A cell's way in 1/DIST_STEPS blocks (scene_copy's HELD_DIST_STEPS); a step along one, two and three axes (1, ~sqrt 2, ~sqrt 3). */
    public static final int DIST_STEPS = 8, STEP_1 = 8, STEP_2 = 11, STEP_3 = 14;
    /** The longest way kept (15 blocks: where a level 15 light ends) and a cell the light does not reach (scene_copy's HELD_UNREACHED). */
    public static final int MAX_DIST = 15 * DIST_STEPS, UNREACHED = 255;

    /** A block's light opacity (BlockState.getLightBlock: 0 clear, 15 opaque), world block coordinates. */
    public interface Opacity {
        int at(int x, int y, int z);
    }

    private HeldLight() {
    }

    /** The cell of (dx, dy, dz) from the grid's corner: x fastest, then y, then z (scene_copy's heldCell mirrors this). */
    static int index(int dx, int dy, int dz) {
        return dx + SIDE * (dy + SIDE * dz);
    }

    /**
     * The way from the eye's cell (cx, cy, cz) to every cell, in 1/DIST_STEPS blocks, UNREACHED past MAX_DIST or behind opaque
     * blocks: steps to the 26 neighbours (STEP_1..3), a step into a block of opacity k costing max(1, k) times its length as vanilla
     * takes max(1, k) levels, none into an opaque block (15), and a diagonal step only where every other cell of its box is open (no
     * way between blocks that touch at an edge). The grid's corner is (cx, cy, cz) - RADIUS.
     */
    public static byte[] distances(int cx, int cy, int cz, Opacity opacity) {
        Opacities op = new Opacities(cx - RADIUS, cy - RADIUS, cz - RADIUS, opacity);
        int[] dist = new int[CELLS];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        int start = index(RADIUS, RADIUS, RADIUS);
        dist[start] = 0;
        //Dijkstra with a bucket per way length (the ways are whole steps up to MAX_DIST): each cell is settled in order of its way
        int[][] buckets = new int[MAX_DIST + 1][];
        int[] sizes = new int[MAX_DIST + 1];
        push(buckets, sizes, 0, start);
        for (int d = 0; d <= MAX_DIST; d++) {
            for (int b = 0; b < sizes[d]; b++) {
                int i = buckets[d][b];
                if (dist[i] != d) continue;
                int x = i % SIDE, y = (i / SIDE) % SIDE, z = i / (SIDE * SIDE);
                for (int k = 0; k < MOVES.length; k += 4) {
                    int dx = MOVES[k], dy = MOVES[k + 1], dz = MOVES[k + 2], step = MOVES[k + 3];
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= SIDE || ny >= SIDE || nz >= SIDE) continue;
                    int n = index(nx, ny, nz);
                    int next = d + step;
                    if (next > MAX_DIST || next >= dist[n]) continue;
                    int o = op.at(n);
                    if (o >= 15) continue;
                    if (o > 1) {
                        next = d + step * o;
                        if (next > MAX_DIST || next >= dist[n]) continue;
                    }
                    if (step != STEP_1 && !boxOpen(op, x, y, z, dx, dy, dz)) continue;
                    dist[n] = next;
                    push(buckets, sizes, next, n);
                }
            }
            buckets[d] = null;
        }
        byte[] cells = new byte[CELLS];
        for (int i = 0; i < CELLS; i++) cells[i] = (byte) (dist[i] == Integer.MAX_VALUE ? UNREACHED : dist[i]);
        return cells;
    }

    /** The grid's opacities, each read once when first needed (the way reaches a sphere of the grid, not its corners). */
    private static final class Opacities {
        private final byte[] cells = new byte[CELLS];
        private final int ox, oy, oz;
        private final Opacity opacity;

        Opacities(int ox, int oy, int oz, Opacity opacity) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.opacity = opacity;
            java.util.Arrays.fill(this.cells, (byte) -1);
        }

        int at(int i) {
            int o = this.cells[i];
            if (o < 0) {
                o = Math.max(0, Math.min(15, this.opacity.at(this.ox + i % SIDE, this.oy + (i / SIDE) % SIDE, this.oz + i / (SIDE * SIDE))));
                this.cells[i] = (byte) o;
            }
            return o;
        }
    }

    /** The 26 moves: dx, dy, dz and the step's length (STEP_1..3). */
    private static final int[] MOVES = moves();

    private static int[] moves() {
        int[] m = new int[26 * 4];
        int k = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes == 0) continue;
                    m[k++] = dx;
                    m[k++] = dy;
                    m[k++] = dz;
                    m[k++] = axes == 1 ? STEP_1 : axes == 2 ? STEP_2 : STEP_3;
                }
            }
        }
        return m;
    }

    private static void push(int[][] buckets, int[] sizes, int d, int cell) {
        int[] b = buckets[d];
        if (b == null) buckets[d] = b = new int[64];
        else if (sizes[d] == b.length) buckets[d] = b = java.util.Arrays.copyOf(b, b.length * 2);
        b[sizes[d]++] = cell;
    }

    /** Whether every cell of a diagonal step's box but its two ends is open (not opaque). */
    private static boolean boxOpen(Opacities op, int x, int y, int z, int dx, int dy, int dz) {
        int ex = dx != 0 ? 1 : 0, ey = dy != 0 ? 1 : 0, ez = dz != 0 ? 1 : 0;
        for (int az = 0; az <= ez; az++) {
            for (int ay = 0; ay <= ey; ay++) {
                for (int ax = 0; ax <= ex; ax++) {
                    if (ax + ay + az == 0 || ax == ex && ay == ey && az == ez) continue;
                    if (op.at(index(x + ax * dx, y + ay * dy, z + az * dz)) >= 15) return false;
                }
            }
        }
        return true;
    }

    /**
     * 2026-10-15: scene_copy's bound on the held gain (heldRoom): the scale for a gain whose largest channel is gainMax on a colour
     * (vanilla's, times the sky's gain) whose largest channel is litMax, so that it ends at most at the held light's lightmap
     * colour (largest channel heldMax), a white surface under it, and never darker than it was; 1 when the gain stays below that.
     */
    static float whiteSurfaceScale(float litMax, float gainMax, float heldMax) {
        float room = Math.max(heldMax / Math.max(litMax, 1e-4f), 1);
        return Math.min(1, room / gainMax);
    }

    /** Main and offhand: the brighter of the two items' light (each 0..15). */
    public static int held(int mainHand, int offHand) {
        return Math.max(0, Math.min(15, Math.max(mainHand, offHand)));
    }

    /** The cells as bytes in a buffer of BYTES (uint-padded). */
    public static void pack(byte[] cells, ByteBuffer out) {
        for (int i = 0; i < BYTES; i++) out.put(i, i < cells.length ? cells[i] : 0);
    }
}
