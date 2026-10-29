package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** P7k design 3: the held light spreads as vanilla block light does. */
class HeldLightTest {
    private static int at(byte[] cells, int dx, int dy, int dz) {
        return cells[HeldLight.index(dx + HeldLight.RADIUS, dy + HeldLight.RADIUS, dz + HeldLight.RADIUS)];
    }

    @Test
    void inOpenAirTheLevelFallsByTheBlockDistance() {
        byte[] c = HeldLight.spread(100, 64, -20, 14, (x, y, z) -> 0);
        assertEquals(14, at(c, 0, 0, 0));
        assertEquals(13, at(c, 1, 0, 0));
        assertEquals(11, at(c, 1, 1, -1), "three steps");
        assertEquals(1, at(c, 13, 0, 0));
        assertEquals(0, at(c, 14, 0, 0), "a torch's light ends 14 blocks out");
    }

    /** Review focus: no light through walls. */
    @Test
    void anOpaqueWallStopsIt() {
        //A wall at world x = 103 (dx = 3), the eye at x = 100
        byte[] c = HeldLight.spread(100, 64, 0, 14, (x, y, z) -> x == 103 ? 15 : 0);
        assertEquals(12, at(c, 2, 0, 0));
        assertEquals(0, at(c, 3, 0, 0), "the wall itself");
        //Around the wall (it spans y and z inside the grid, so nothing goes round within the grid)
        assertEquals(0, at(c, 4, 0, 0), "behind the wall");
    }

    @Test
    void translucentBlocksCostMore() {
        byte[] c = HeldLight.spread(0, 0, 0, 14, (x, y, z) -> x == 1 ? 3 : 0);
        assertEquals(11, at(c, 1, 0, 0), "water-like opacity 3");
    }

    @Test
    void nothingHeldNothingSpread() {
        byte[] c = HeldLight.spread(0, 0, 0, 0, (x, y, z) -> 0);
        for (byte b : c) assertEquals(0, b);
        assertEquals(15, HeldLight.held(15, 3));
        assertEquals(7, HeldLight.held(0, 7));
        assertEquals(15, HeldLight.held(20, 0), "clamped");
    }

    @Test
    void packsIntoWholeUints() {
        assertEquals(0, HeldLight.BYTES % 4);
        assertTrue(HeldLight.BYTES >= HeldLight.CELLS);
        byte[] c = HeldLight.spread(0, 0, 0, 5, (x, y, z) -> 0);
        ByteBuffer out = ByteBuffer.allocate(HeldLight.BYTES);
        HeldLight.pack(c, out);
        assertEquals(5, out.get(HeldLight.index(HeldLight.RADIUS, HeldLight.RADIUS, HeldLight.RADIUS)));
        assertEquals(0, out.get(HeldLight.BYTES - 1));
    }

    /** 2026-10-30 (audit): modded opacities (step costs 2 to 14) raise cells more than once; the spread still ends at the true levels. */
    @Test
    void unevenStepCostsSpreadToTheTrueLevels() {
        java.util.Random r = new java.util.Random(7);
        int[] cost = new int[HeldLight.CELLS];
        for (int i = 0; i < cost.length; i++) cost[i] = 1 + r.nextInt(14);
        int S = HeldLight.SIDE;
        HeldLight.Opacity o = (x, y, z) -> cost[(x + 1000 * S) % S + S * ((y + 1000 * S) % S) + S * S * ((z + 1000 * S) % S)];
        byte[] cells = HeldLight.spread(0, 0, 0, 15, o);
        //relax until nothing changes: the levels a full Bellman-Ford pass gives
        int[] want = new int[HeldLight.CELLS];
        int ox = -HeldLight.RADIUS;
        want[HeldLight.RADIUS + S * HeldLight.RADIUS + S * S * HeldLight.RADIUS] = 15;
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < want.length; i++) {
                if (want[i] <= 1) continue;
                int x = i % S, y = (i / S) % S, z = i / (S * S);
                int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                for (int[] st : steps) {
                    int nx = x + st[0], ny = y + st[1], nz = z + st[2];
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= S || ny >= S || nz >= S) continue;
                    int n = nx + S * ny + S * S * nz;
                    int next = want[i] - Math.max(1, o.at(ox + nx, ox + ny, ox + nz));
                    if (next > want[n]) {
                        want[n] = next;
                        changed = true;
                    }
                }
            }
        }
        for (int i = 0; i < want.length; i++) assertEquals(want[i], cells[i], "cell " + i);
    }
}
