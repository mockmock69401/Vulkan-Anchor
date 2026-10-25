package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7h design 3.1-3.3: the cloud shape, interval, march and scroll, mirrored for the tests. */
class CloudMathTest {
    private static final CloudMath.Map FULL = (x, z) -> 1, EMPTY = (x, z) -> 0;
    private static final float BASE = 10, THICK = 4, LOD = 1000;
    private static final float[] UP = {0, 1, 0};

    /** Design 2: vanilla's x drift (ticks + partial) x 0.03 and z shift 0.33 cells, wrapped at 3072 blocks. */
    @Test
    void theOffsetIsVanillasScroll() {
        float[] o = CloudMath.offset(100.5, -20.25, 1000, 0.5f);
        assertEquals(130.515f, o[0], 1e-3f);
        assertEquals(3055.71f, o[1], 1e-3f);
        for (float v : CloudMath.offset(-1e7, 1e7, 0, 0)) assertTrue(v >= 0 && v < CloudMath.PERIOD, "" + v);
    }

    @Test
    void theMaskMarksCloudCells() {
        byte[] m = CloudMath.mask(new int[]{0x00FFFFFF, 0xFF000000, 0x01000000, 0x80FF0000});
        assertArrayEquals(new byte[]{0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1}, m);
    }

    /** Design 3.1: a cell's middle is the cell, the boundary half way, the next cell's middle that cell; it repeats every 256 cells. */
    @Test
    void theCoverIsRoundedCells() {
        CloudMath.Map one = (x, z) -> x == 5 && z == 7 ? 1 : 0;
        assertEquals(1f, CloudMath.cover(one, 0, 0, 66, 90), 1e-6f);
        assertEquals(1f, CloudMath.cover(one, 0, 0, 68.4f, 90), 1e-6f, "flat in the cell's middle");
        assertEquals(0.5f, CloudMath.cover(one, 0, 0, 72, 90), 1e-6f, "half way at the boundary");
        assertEquals(0f, CloudMath.cover(one, 0, 0, 78, 90), 1e-6f, "the next cell");
        assertEquals(1f, CloudMath.cover(one, 0, 0, 66 + 3072, 90), 1e-6f, "repeats every 256 cells");
        assertEquals(1f, CloudMath.cover(one, 24, 0, 42, 90), 1e-6f, "the offset moves the map");
        CloudMath.Map last = (x, z) -> x == 255 && z == 7 ? 1 : 0;
        assertEquals(1f, CloudMath.cover(last, 0, 0, -6, 90), 1e-6f, "below zero wraps to cell 255");
    }

    /** Design 3.1: inside the layer's middle a full map is dense; outside and at the soft top and bottom it is empty. */
    @Test
    void theDensityHasRoundedTopsAndBottoms() {
        assertEquals(1f, CloudMath.density(FULL, 0, 0, BASE, THICK, 0, 12, 0), 1e-6f);
        assertEquals(0f, CloudMath.density(FULL, 0, 0, BASE, THICK, 0, 9, 0));
        assertEquals(0f, CloudMath.density(FULL, 0, 0, BASE, THICK, 0, 10.2f, 0), "the soft bottom");
        assertEquals(0f, CloudMath.density(FULL, 0, 0, BASE, THICK, 0, 13.9f, 0), "the soft top");
        assertEquals(0f, CloudMath.density(EMPTY, 0, 0, BASE, THICK, 0, 12, 0));
    }

    /** Review focus 3, 4: from below, from inside, cut by the scene distance and by the LOD distance; a level ray outside has none. */
    @Test
    void intervalsFromBelowInsideAndCut() {
        assertNull(CloudMath.interval(BASE, THICK, LOD, new float[]{0, 0, 0}, new float[]{0, 0, -1}, 500));
        assertArrayEquals(new float[]{10, 14}, CloudMath.interval(BASE, THICK, LOD, new float[]{0, 0, 0}, UP, 100), 1e-5f);
        assertArrayEquals(new float[]{0, 2}, CloudMath.interval(BASE, THICK, LOD, new float[]{0, 12, 0}, new float[]{0, -1, 0}, 100), 1e-5f);
        assertArrayEquals(new float[]{10, 11}, CloudMath.interval(BASE, THICK, LOD, new float[]{0, 0, 0}, UP, 11), 1e-5f);
        assertArrayEquals(new float[]{0, 1000}, CloudMath.interval(BASE, THICK, LOD, new float[]{0, 12, 0}, new float[]{1, 0, 0}, 1e6f), 1e-3f);
        assertNull(CloudMath.interval(BASE, THICK, LOD, new float[]{0, 0, 0}, UP, 5), "the scene is nearer than the layer");
    }

    /** Design 3.2: an empty map draws nothing; a full layer seen from below covers most of the sky; level through it, all. */
    @Test
    void theMarchCoversByThickness() {
        assertEquals(0f, CloudMath.march(EMPTY, 0, 0, BASE, THICK, LOD, new float[3], UP, 100, UP).coverage());
        CloudMath.March up = CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[3], UP, 100, UP);
        assertTrue(up.coverage() > 0.7f && up.coverage() < 0.95f, "" + up.coverage());
        assertTrue(up.distance() > 10 && up.distance() < 14, "" + up.distance());
        CloudMath.March level = CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[]{0, 12, 0}, new float[]{1, 0, 0}, 1e6f, UP);
        assertTrue(level.coverage() > 0.99f, "" + level.coverage());
        assertEquals(0f, CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[3], UP, 5, UP).coverage(), "behind the scene");
    }

    /** Design 3.2: the shading keeps the light between CLOUD_SHADE_MIN and full per unit of coverage. */
    @Test
    void theShadingStaysInRange() {
        CloudMath.March up = CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[3], UP, 100, UP);
        float ratio = up.light() / up.coverage();
        assertTrue(ratio >= CloudMath.CLOUD_SHADE_MIN && ratio <= 1, "" + ratio);
    }

    /** P7h final review: the sun and moon swap at the horizon, so the shading fades out as the light turns level (no jump when they swap). */
    @Test
    void theShadingFadesWhenTheLightIsLevel() {
        CloudMath.March level = CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[3], UP, 100, new float[]{1, 0, 0});
        assertEquals(level.coverage(), level.light(), 1e-5f, "a level light has no shading");
        CloudMath.March high = CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[3], UP, 100, UP);
        assertTrue(high.light() < high.coverage() - 1e-3f, "a light overhead shades: " + high.light() + " of " + high.coverage());
        //Light on either side of the horizon shades the same way (the swap is continuous)
        CloudMath.March sun = CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[3], UP, 100, new float[]{1, 0.001f, 0});
        CloudMath.March moon = CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[3], UP, 100, new float[]{-1, -0.001f, 0});
        assertEquals(sun.light(), moon.light(), 1e-3f);
        assertEquals(sun.coverage(), sun.light(), 1e-3f);
    }

    /** Review focus 4: beyond the LOD distance the fade leaves nothing. */
    @Test
    void farBeyondTheLodNothingIsDrawn() {
        assertEquals(0f, CloudMath.march(FULL, 0, 0, BASE, THICK, LOD, new float[]{2000, 12, 0}, new float[]{1, 0, 0}, 1e6f, UP).coverage());
    }
}
