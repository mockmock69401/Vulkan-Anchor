package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** P7l design 3.4, 3.5: the relighting maths that lightvolume.glsl and scene_copy.comp mirror. */
class RelightMathTest {
    @Test
    void theSunLiftsTheSkyAndShadeKeepsIt() {
        assertEquals(15f, RelightMath.liftedSky(4, RelightMath.lift(1, 1)), 1e-6f);
        assertEquals(4f, RelightMath.liftedSky(4, RelightMath.lift(0, 1)), 1e-6f);
        assertEquals(9.5f, RelightMath.liftedSky(4, RelightMath.lift(1, 0.5f)), 1e-6f);
        assertEquals(0f, RelightMath.lift(1, 0));
        assertEquals(1f, RelightMath.lift(3, 2));
    }

    @Test
    void theGainStopsAtFour() {
        assertEquals(2f, RelightMath.gain(0.25f, 0.5f), 1e-6f);
        assertEquals(RelightMath.MAX_GAIN, RelightMath.gain(0.01f, 1f), 0f);
        assertEquals(RelightMath.MAX_GAIN, RelightMath.gain(0f, 1f), 0f);
        assertEquals(1f, RelightMath.gain(0.3f, 0.3f), 1e-6f);
    }

    @Test
    void sunShadingCountsAsFarAsTheSkyIsSeen() {
        assertEquals(0f, RelightMath.skyShare(0));
        assertEquals(1f, RelightMath.skyShare(15), 0f);
        assertEquals(0.25f, RelightMath.vis(7.5f), 1e-6f);
    }

    /** Design 3.5: an island's shade on the sea sees the sky sideways; cave water does not. */
    @Test
    void waterSeesTheSkySideways() {
        assertEquals(1f, RelightMath.waterSky(0, RelightMath.side(new int[]{-1, 0, 15, 3})), 0f);
        assertEquals(0f, RelightMath.waterSky(0, RelightMath.side(new int[]{0, -1, 0})), 0f);
        assertEquals(RelightMath.vis(12), RelightMath.waterSky(12, 0.2f), 1e-6f);
        assertEquals(15f, RelightMath.waterLiftedSky(3, 1), 0f);
        assertEquals(7f, RelightMath.waterLiftedSky(7, 0.1f), 0f);
    }

    @Test
    void theRingIsSixteenCellsAtSixteenAndThirtyTwo() {
        int[][] r = RelightMath.ring();
        assertEquals(16, r.length);
        assertArrayEquals(new int[]{16, 0}, r[0]);
        assertArrayEquals(new int[]{11, 11}, r[4]);
        assertArrayEquals(new int[]{-32, 0}, r[9]);
        assertArrayEquals(new int[]{-23, -23}, r[15]);
    }

    /** Gate G7l: the face's dominant world axis, and the layer of cells in front of it (stable against depth noise). */
    @Test
    void theFrontLayerIsTheAirInFrontOfTheFace() {
        assertEquals(1, RelightMath.faceAxis(0.1f, 0.98f, -0.2f));
        assertEquals(0, RelightMath.faceAxis(-0.9f, 0.3f, 0.3f));
        assertEquals(2, RelightMath.faceAxis(0.2f, -0.1f, -0.97f));
        //a water surface at 62.89 (8/9 of a block) and a top face at 63, each with +-0.3 blocks of depth noise: the air at 63
        for (float noise = -0.3f; noise <= 0.3f; noise += 0.1f) {
            assertEquals(63, RelightMath.frontLayer(62.89f + noise, 1));
            assertEquals(63, RelightMath.frontLayer(63f + noise, 1));
            assertEquals(62, RelightMath.frontLayer(63f + noise, -1));
        }
    }

    /** Design 3.3, gate G7l: the 4 front-layer cells, bilinear; solid cells (0) skipped while another is lit; unknown → unknown. */
    @Test
    void faceBlendSkipsSolidCells() {
        int lit = 15 | 2 << 4;
        assertArrayEquals(new float[]{15, 2, 1}, RelightMath.faceBlend(new int[]{lit, 0, lit, 0}, 0.9f, 0.5f), 1e-5f);
        assertArrayEquals(new float[]{0, 0, 1}, RelightMath.faceBlend(new int[4], 0.5f, 0.5f), 0f);
        assertEquals(10f, RelightMath.faceBlend(new int[]{15, 5, 15, 5}, 0.5f, 0.5f)[0], 1e-5f);
        assertEquals(7.5f, RelightMath.faceBlend(new int[]{15, 5, 15, 5}, 0.75f, 0.3f)[0], 1e-5f);
        assertEquals(0f, RelightMath.faceBlend(new int[]{15, 15, -1, 15}, 0.5f, 0.5f)[2]);
        assertArrayEquals(new float[]{15, 0, 1}, RelightMath.faceBlend(new int[]{0, 15, 0, 0}, 0f, 0f), 0f);
    }

    /** Gate G7l: water's scattered light gets its floor only where the gain hit the cap (an island's black core). */
    @Test
    void theWaterFloorIsOnlyForCappedGains() {
        assertEquals(0.08f, RelightMath.WATER_ALBEDO, 0f);
        assertFalse(RelightMath.capped(0.5f, 0.9f));
        assertFalse(RelightMath.capped(0.25f, 1f));
        assertTrue(RelightMath.capped(0.01f, 1f));
        assertTrue(RelightMath.capped(0f, 0.2f));
    }
}
