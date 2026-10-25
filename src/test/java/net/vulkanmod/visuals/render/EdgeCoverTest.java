package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-16 (CTE2 report: x-ray through chunks not built yet): a built vanilla section next to a horizontal neighbour that is not
 * built is covered on that side, below the surface. 2026-10-17 (second CTE2 report: the caves still showed through the per-block
 * cover, "one stone face is better"): one stone face a column, one light (the surface's), so nothing behind it shows.
 */
class EdgeCoverTest {
    private static final float[] STONE = {0.1f, 0.2f, 0.3f, 0.4f};

    /**
     * Section (0,0,0) built; its +X neighbour (1,0,0) a hole; (-1,0,0) outside the grid (a hole too since 2026-10-17: the loaded
     * area's own edge, seen after an L-shaped move); (0,0,-1) never built because VulkanMod never sees it or it is empty air.
     */
    private static final EdgeCover.Sections SECTIONS = new EdgeCover.Sections() {
        @Override
        public boolean compiled(int sx, int sy, int sz) {
            return sx == 0 && sz >= 0;
        }

        @Override
        public boolean pending(int sx, int sy, int sz) {
            return (sx == 1 && sz == 0) || sx == -1;
        }
    };

    /** Solid below y = 8 except a cave cell at (15, 3, 5) and the cell across at (16, 4, 5); surface 8, sky 15 above it. */
    private static class World implements EdgeCover.Blocks {
        final Set<Long> open = new HashSet<>();

        World() {
            this.open.add(key(15, 3, 5));
            this.open.add(key(16, 4, 5));
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) | (((long) y & 0xFFFFF) << 20) | (((long) z & 0xFFFFF) << 40);
        }

        @Override
        public boolean full(int x, int y, int z) {
            return y < 8 && !this.open.contains(key(x, y, z));
        }

        @Override
        public int surface(int x, int z) {
            return 8;
        }

        @Override
        public int sky(int x, int y, int z) {
            return y >= 8 ? 15 : (x >= 16 ? 7 : 0);
        }

        @Override
        public int block(int x, int y, int z) {
            return x >= 16 ? 2 : 0;
        }

        @Override
        public float[] sprite(int x, int y, int z) {
            return null;
        }
    }

    private static FloatBuffer run(EdgeCover.Blocks world, int ox, int oy, int oz) {
        FloatBuffer out = FloatBuffer.allocate(16 * EdgeCover.FLOATS_PER_QUAD);
        EdgeCover.quads(new EdgeCover.Face(0, 0, 0, EdgeCover.POS_X), world, STONE, ox, oy, oz, out);
        return out;
    }

    @Test
    void coversNeighboursThatAreHoles() {
        assertTrue(EdgeCover.covers(SECTIONS, 0, 0, 0, EdgeCover.POS_X));
        assertTrue(EdgeCover.covers(SECTIONS, 0, 0, 0, EdgeCover.NEG_X), "the loaded area's edge");
        assertFalse(EdgeCover.covers(SECTIONS, 0, 0, 0, EdgeCover.POS_Z), "(0,0,1) is built");
        assertFalse(EdgeCover.covers(SECTIONS, 1, 0, 0, EdgeCover.NEG_X), "a section not built covers nothing");
        assertFalse(EdgeCover.covers(SECTIONS, 0, 0, 0, EdgeCover.NEG_Z), "never seen or empty: not a hole the camera looks into");
    }

    /** VulkanMod never builds the chunks at the render distance itself (their outer neighbours are not loaded). */
    @Test
    void theRenderDistancesOwnRingNeverBuilds() {
        assertTrue(EdgeCover.buildable(3, -4, 0, 0, 5));
        assertFalse(EdgeCover.buildable(5, 0, 0, 0, 5));
        assertFalse(EdgeCover.buildable(-2, -15, 0, -10, 5));
        assertTrue(EdgeCover.buildable(-2, -14, 0, -10, 5));
    }

    @Test
    void oneStoneQuadAColumnUpToTheSurface() {
        FloatBuffer out = run(new World(), 0, 0, 0);
        assertEquals(16 * EdgeCover.FLOATS_PER_QUAD, out.position(), "16 columns, caves and blocks alike");
        for (int q = 0; q < 16; q++) {
            int o = q * EdgeCover.FLOATS_PER_QUAD;
            assertEquals(0, out.get(o + 1), "from the section's bottom");
            assertEquals(8, out.get(o + 3), "to the surface");
            assertArrayEquals(STONE, new float[]{out.get(o + 4), out.get(o + 5), out.get(o + 6), out.get(o + 7)});
            assertEquals(15, out.get(o + 8), "the surface's sky light for the whole column: no dark cave patches");
            assertEquals(0, out.get(o + 9));
        }
    }

    @Test
    void deepSectionsAreCoveredWholeAndSkyHighOnesNotAtAll() {
        EdgeCover.Blocks high = new World() {
            @Override
            public int surface(int x, int z) {
                return 40;
            }
        };
        FloatBuffer out = run(high, 0, 0, 0);
        assertEquals(16 * EdgeCover.FLOATS_PER_QUAD, out.position());
        assertEquals(16, out.get(3), "the section's whole height");
        EdgeCover.Blocks low = new World() {
            @Override
            public int surface(int x, int z) {
                return -3;
            }
        };
        assertEquals(0, run(low, 0, 0, 0).position(), "above the surface: sky, nothing to cover");
    }

    @Test
    void theQuadSitsJustOutsideTheFace() {
        FloatBuffer out = run(new World(), 2, 3, 4);
        for (int q = 0; q < 16; q++) {
            int o = q * EdgeCover.FLOATS_PER_QUAD;
            assertEquals(16 + EdgeCover.OFFSET - 2, out.get(o), 1e-6f, "x: the section's +X face, outward, origin relative");
            assertEquals(-3, out.get(o + 1));
            assertEquals(q - 4, out.get(o + 2), "z: column q");
            assertEquals(EdgeCover.SHADE_X, out.get(o + 10));
            assertEquals(EdgeCover.POS_X, out.get(o + 11), "the side, for the shader to tell inside from outside");
        }
        assertArrayEquals(new float[]{0.6f, 0.8f}, new float[]{EdgeCover.SHADE_X, EdgeCover.SHADE_Z});
    }
}
