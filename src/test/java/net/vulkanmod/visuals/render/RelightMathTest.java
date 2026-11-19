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
        assertEquals(1f, RelightMath.waterSky(0, RelightMath.side(new int[]{15, 15, 15, 15, 15, 15, 15, 15})), 0f);
        assertEquals(0f, RelightMath.waterSky(0, RelightMath.side(new int[]{0, -1, 0, 0, 0, 0, 0, 0})), 0f);
        //2026-11-19: the brightest direction, faded in by the share of the horizon seen open: one doorway lifts nothing, two half,
        // a roof open on two sides (three directions or more) as before
        assertEquals(0f, RelightMath.side(new int[]{-1, 0, 15, 0, 0, 0, 0, 0}), 0f);
        assertEquals(0.5f, RelightMath.side(new int[]{15, 15, 0, 0, 0, 0, 0, 0}), 1e-6f);
        assertEquals(1f, RelightMath.side(new int[]{15, 15, 15, 0, 0, 0, 0, 0}), 0f);
        assertEquals(1f, RelightMath.side(new int[]{15, 15, 15, 15, 15, 15, 0, 0}), 0f);
        assertEquals(RelightMath.vis(12), RelightMath.waterSky(12, 0.2f), 1e-6f);
        assertEquals(15f, RelightMath.waterLiftedSky(3, 1), 0f);
        assertEquals(7f, RelightMath.waterLiftedSky(7, 0.1f), 0f);
    }

    /** 2026-11-19: the ring counts only through open air: a cave pool behind rock does not see the open air outside it. */
    @Test
    void theSideSkyNeedsOpenAirOnTheWay() {
        int[] east = {RelightMath.SIDE_FAR, 0}, diagonal = {RelightMath.SIDE_DIAGONAL_FAR, -RelightMath.SIDE_DIAGONAL_FAR};
        //the cells sit at the near and far rings
        assertArrayEquals(new int[]{RelightMath.SIDE_NEAR, 0}, RelightMath.sideCell(east, RelightMath.SIDE_STEPS / 2 - 1));
        assertArrayEquals(new int[]{RelightMath.SIDE_DIAGONAL_NEAR, -RelightMath.SIDE_DIAGONAL_NEAR}, RelightMath.sideCell(diagonal, 1));
        assertArrayEquals(new int[]{-RelightMath.SIDE_DIAGONAL_FAR, 0}, RelightMath.sideCell(new int[]{-23, 0}, 3));
        //an island's shade on the sea: open air under it, its sky light rising toward the open sea (8 blocks a cell, 1 level a block)
        assertEquals(15, RelightMath.sideSeen(0, new int[]{5, 13, 15, 15}, east));
        //water under an overhang beside the open sea
        assertEquals(15, RelightMath.sideSeen(12, new int[]{15, 15, 15, 15}, east));
        //a cave pool: rock between it and the open air outside the hill
        assertEquals(0, RelightMath.sideSeen(0, new int[]{0, 0, 15, 15}, east));
        assertEquals(0, RelightMath.sideSeen(0, new int[]{0, 15, 15, 15}, east));
        //a sealed room: the near ring is still inside, the far ring outside
        assertEquals(0, RelightMath.sideSeen(0, new int[]{0, 0, 0, 15}, east));
        //a tunnel out to a cave mouth: open air all the way, the light rising toward the mouth
        assertEquals(15, RelightMath.sideSeen(0, new int[]{0, 7, 15, 15}, east));
        //a pillar of rock beside lit water hides what lies behind it
        assertEquals(0, RelightMath.sideSeen(12, new int[]{0, 15, 15, 15}, east));
        //an unknown cell ends the walk: only the near ring was reached
        assertEquals(11, RelightMath.sideSeen(3, new int[]{9, 11, -1, 15}, east));
        assertEquals(0, RelightMath.sideSeen(0, new int[]{-1, 0, 15, 15}, east));
        //a diagonal's cells are 5-6 blocks apart on each axis: Manhattan distance (10, then 12)
        assertEquals(15, RelightMath.sideSeen(0, new int[]{0, 9, 15, 15}, diagonal));
        assertEquals(0, RelightMath.sideSeen(0, new int[]{0, 0, 15, 15}, diagonal));
        //the 4 nearest cells blend bilinearly
        assertEquals(0.5f, RelightMath.sideBlend(0, 1, 0, 1, 0.5f, 0.3f), 1e-6f);
        assertEquals(0.25f, RelightMath.sideBlend(0, 0, 0, 1, 0.5f, 0.5f), 1e-6f);
        assertEquals(1f, RelightMath.sideBlend(0, 0, 0, 1, 1, 1), 0f);
    }

    /** lightvolume.glsl's sideSky follows sideSeen: its step count and its open-air rule. */
    @Test
    void theShaderReadsTheSideSkyAsMirrored() throws java.io.IOException {
        String glsl = new String(RelightMathTest.class.getResourceAsStream("/assets/vulkanmod/visuals/shaders/post/lightvolume.glsl").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(glsl.contains("const int LIGHT_SIDE_STEPS = " + RelightMath.SIDE_STEPS + ";"));
        assertTrue(glsl.contains("ivec2 at = o[i]*(j + 1)/LIGHT_SIDE_STEPS;"));
        assertTrue(glsl.contains("if (abs(level - prev) > float(m.x + m.y)) {"));
        assertTrue(glsl.contains("if (j == LIGHT_SIDE_STEPS/2 - 1 || j == LIGHT_SIDE_STEPS - 1) {"));
        assertTrue(glsl.contains("return float(best)/15.0*smoothstep(LIGHT_SIDE_OPEN_LOW, LIGHT_SIDE_OPEN_HIGH, float(sum)/(15.0*8.0));"));
        assertTrue(glsl.contains("const float LIGHT_SIDE_OPEN_LOW = " + RelightMath.SIDE_OPEN_LOW + ";"));
        assertTrue(glsl.contains("const float LIGHT_SIDE_OPEN_HIGH = " + RelightMath.SIDE_OPEN_HIGH + ";"));
        assertEquals(8, RelightMath.SIDE_DIRECTIONS);
        assertTrue(glsl.contains("float sideSky(vec3 world, float sky) {"));
        assertTrue(glsl.contains("return mix(mix(s00, s10, t.x), mix(s01, s11, t.x), t.y);"));
        assertTrue(glsl.contains("const int LIGHT_SIDE_GRID = " + RelightMath.SIDE_GRID + ";"));
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
