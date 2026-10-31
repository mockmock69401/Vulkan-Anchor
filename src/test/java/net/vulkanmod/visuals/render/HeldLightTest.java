package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P7k design 3, 2026-10-31: the held light's grid holds the length of the light's way from the eye's cell to each cell (around walls,
 * no way through opaque blocks), in 1/DIST_STEPS blocks; scene_copy turns it and the eye's exact position into the level per pixel.
 */
class HeldLightTest {
    private static int at(byte[] cells, int dx, int dy, int dz) {
        return cells[HeldLight.index(dx + HeldLight.RADIUS, dy + HeldLight.RADIUS, dz + HeldLight.RADIUS)] & 0xFF;
    }

    private static float blocks(byte[] cells, int dx, int dy, int dz) {
        return at(cells, dx, dy, dz) / (float) HeldLight.DIST_STEPS;
    }

    @Test
    void inOpenAirTheWayIsAboutTheStraightLine() {
        byte[] c = HeldLight.distances(100, 64, -20, (x, y, z) -> 0);
        assertEquals(0, at(c, 0, 0, 0));
        assertEquals(1f, blocks(c, 1, 0, 0), 1e-6f);
        assertEquals(1f, blocks(c, 0, -1, 0), 1e-6f);
        for (int[] d : new int[][]{{1, 1, 0}, {1, 1, 1}, {3, 4, 0}, {5, 2, -3}, {-7, 0, 6}, {2, -9, 4}}) {
            float straight = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            //the 26-neighbour steps overestimate some directions by up to about an eighth (scene_copy's HELD_WAY_SHARE takes that off)
            float way = blocks(c, d[0], d[1], d[2]);
            assertTrue(way >= straight * 0.97f && way <= straight * 1.13f, java.util.Arrays.toString(d) + ": " + way + " for " + straight);
        }
    }

    @Test
    void cellsPastTheLightsReachAreUnreached() {
        byte[] c = HeldLight.distances(0, 0, 0, (x, y, z) -> 0);
        assertEquals(HeldLight.UNREACHED, at(c, HeldLight.RADIUS, HeldLight.RADIUS, HeldLight.RADIUS), "the grid's corner, 26 blocks out");
        assertNotEquals(HeldLight.UNREACHED, at(c, HeldLight.RADIUS, 0, 0), "15 blocks out");
    }

    /** Review focus: no light through walls. */
    @Test
    void anOpaqueWallStopsIt() {
        //A wall at world x = 103 (dx = 3), the eye at x = 100; it spans y and z inside the grid, so nothing goes round within it
        byte[] c = HeldLight.distances(100, 64, 0, (x, y, z) -> x == 103 ? 15 : 0);
        assertEquals(2f, blocks(c, 2, 0, 0), 1e-6f);
        assertEquals(HeldLight.UNREACHED, at(c, 3, 0, 0), "the wall itself");
        assertEquals(HeldLight.UNREACHED, at(c, 4, 0, 0), "behind the wall");
    }

    /** No way between two opaque blocks that only touch at an edge. */
    @Test
    void itDoesNotSlipThroughAnEdge() {
        byte[] c = HeldLight.distances(0, 0, 0, (x, y, z) -> (x == 1 && y == 0 || x == 0 && y == 1) ? 15 : 0);
        assertTrue(blocks(c, 1, 1, 0) > 2f, "around through the cells above or below: " + blocks(c, 1, 1, 0));
    }

    @Test
    void aWallMakesTheWayLonger() {
        //A wall at dx = 2 with a gap at dz = 3: the cell behind it straight ahead is reached through the gap
        byte[] c = HeldLight.distances(0, 0, 0, (x, y, z) -> x == 2 && z != 3 ? 15 : 0);
        float way = blocks(c, 3, 0, 0);
        assertTrue(way > 6f && way < 9f, "to the gap, through it, back along the wall: " + way);
    }

    @Test
    void translucentBlocksCostMore() {
        //Vanilla takes max(1, opacity) levels for a step into such a block: a plane of water-like opacity 3
        byte[] c = HeldLight.distances(0, 0, 0, (x, y, z) -> x == 1 ? 3 : 0);
        assertEquals(3f, blocks(c, 1, 0, 0), 1e-6f);
        assertEquals(4f, blocks(c, 2, 0, 0), 1e-6f);
    }

    @Test
    void theBrighterHandCounts() {
        assertEquals(15, HeldLight.held(15, 3));
        assertEquals(7, HeldLight.held(0, 7));
        assertEquals(15, HeldLight.held(20, 0), "clamped");
    }

    @Test
    void packsIntoWholeUints() {
        assertEquals(0, HeldLight.BYTES % 4);
        assertTrue(HeldLight.BYTES >= HeldLight.CELLS);
        byte[] c = HeldLight.distances(0, 0, 0, (x, y, z) -> 0);
        ByteBuffer out = ByteBuffer.allocate(HeldLight.BYTES);
        HeldLight.pack(c, out);
        assertEquals(HeldLight.DIST_STEPS, out.get(HeldLight.index(HeldLight.RADIUS + 1, HeldLight.RADIUS, HeldLight.RADIUS)));
        assertEquals(0, out.get(HeldLight.BYTES - 1));
    }

    /** Modded opacities (2 to 14): the field is the shortest way, as a full relaxation finds it. */
    @Test
    void unevenCostsGiveTheShortestWay() {
        java.util.Random r = new java.util.Random(7);
        int S = HeldLight.SIDE;
        int[] cost = new int[HeldLight.CELLS];
        for (int i = 0; i < cost.length; i++) cost[i] = r.nextInt(10) == 0 ? 15 : r.nextInt(4) == 0 ? 1 + r.nextInt(14) : 0;
        int ox = -HeldLight.RADIUS;
        HeldLight.Opacity o = (x, y, z) -> cost[(x - ox) + S * (y - ox) + S * S * (z - ox)];
        byte[] cells = HeldLight.distances(0, 0, 0, o);
        int[] want = relaxed(o);
        for (int i = 0; i < want.length; i++) assertEquals(want[i], cells[i] & 0xFF, "cell " + i);
    }

    /** The field by relaxing every move until nothing changes (Bellman-Ford), with HeldLight's moves and rules written out again. */
    private static int[] relaxed(HeldLight.Opacity o) {
        int S = HeldLight.SIDE, R = HeldLight.RADIUS, max = HeldLight.MAX_DIST;
        int[] d = new int[HeldLight.CELLS];
        java.util.Arrays.fill(d, Integer.MAX_VALUE);
        d[HeldLight.index(R, R, R)] = 0;
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < d.length; i++) {
                if (d[i] == Integer.MAX_VALUE) continue;
                int x = i % S, y = (i / S) % S, z = i / (S * S);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes == 0) continue;
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= S || ny >= S || nz >= S) continue;
                    int op = o.at(nx - R, ny - R, nz - R);
                    if (op >= 15) continue;
                    //every cell on the way (each other cell of the move's box) is open
                    boolean open = true;
                    for (int ax = 0; ax <= Math.abs(dx); ax++) for (int ay = 0; ay <= Math.abs(dy); ay++) for (int az = 0; az <= Math.abs(dz); az++) {
                        int cx = x + ax * dx, cy = y + ay * dy, cz = z + az * dz;
                        if ((cx != x || cy != y || cz != z) && (cx != nx || cy != ny || cz != nz) && o.at(cx - R, cy - R, cz - R) >= 15) open = false;
                    }
                    if (!open) continue;
                    int step = (axes == 1 ? 8 : axes == 2 ? 11 : 14) * Math.max(1, op);
                    int next = d[i] + step;
                    int n = HeldLight.index(nx, ny, nz);
                    if (next <= max && next < d[n]) {
                        d[n] = next;
                        changed = true;
                    }
                }
            }
        }
        for (int i = 0; i < d.length; i++) if (d[i] == Integer.MAX_VALUE) d[i] = HeldLight.UNREACHED;
        return d;
    }
}
