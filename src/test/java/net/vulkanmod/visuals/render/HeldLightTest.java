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
}
