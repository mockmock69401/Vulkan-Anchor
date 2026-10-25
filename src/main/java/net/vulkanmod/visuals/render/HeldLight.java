package net.vulkanmod.visuals.render;

import java.nio.ByteBuffer;

/**
 * P7k design 3: the light of what the player holds, spread from the eye block as vanilla spreads block light (a level less
 * per block at least, stopped by opaque blocks) over a SIDE^3 grid; scene_copy reads it where the per-pixel light is known.
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

    /** A block's light opacity (BlockState.getLightBlock: 0 clear, 15 opaque), world block coordinates. */
    public interface Opacity {
        int at(int x, int y, int z);
    }

    private HeldLight() {
    }

    /** The cell of (dx, dy, dz) from the grid's corner: x fastest, then y, then z (scene_copy's heldLightAt mirrors this). */
    static int index(int dx, int dy, int dz) {
        return dx + SIDE * (dy + SIDE * dz);
    }

    /**
     * The light levels around (cx, cy, cz), the eye block at the centre with level, as vanilla spreads it: each step costs
     * max(1, opacity) and an opaque block (15) takes it all. The grid's corner is (cx, cy, cz) - RADIUS.
     */
    public static byte[] spread(int cx, int cy, int cz, int level, Opacity opacity) {
        byte[] cells = new byte[CELLS];
        if (level <= 0) return cells;
        int[] queue = new int[CELLS];
        int head = 0, tail = 0;
        int start = index(RADIUS, RADIUS, RADIUS);
        cells[start] = (byte) Math.min(15, level);
        queue[tail++] = start;
        int ox = cx - RADIUS, oy = cy - RADIUS, oz = cz - RADIUS;
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (head < tail) {
            int i = queue[head++];
            int x = i % SIDE, y = (i / SIDE) % SIDE, z = i / (SIDE * SIDE);
            int l = cells[i];
            if (l <= 1) continue;
            for (int[] s : steps) {
                int nx = x + s[0], ny = y + s[1], nz = z + s[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= SIDE || ny >= SIDE || nz >= SIDE) continue;
                int n = index(nx, ny, nz);
                int next = l - Math.max(1, opacity.at(ox + nx, oy + ny, oz + nz));
                if (next > cells[n]) {
                    cells[n] = (byte) next;
                    queue[tail++] = n;
                }
            }
        }
        return cells;
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
