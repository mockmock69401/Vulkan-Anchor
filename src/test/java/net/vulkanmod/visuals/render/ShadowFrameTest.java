package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P7j design 3.1, 3.2: which frames draw the sun's views. Review focus 1. */
class ShadowFrameTest {
    private static final float NOON = 0, MIDNIGHT = 0.5f;

    @Test
    void noShadowsAtNightInRainInCavesOrWhenOff() {
        assertNull(ShadowFrame.of(1, true, true, MIDNIGHT, 0, 1, 0, 64, 0, 0));
        assertNull(ShadowFrame.of(1, true, true, NOON, 1, 1, 0, 64, 0, 0));
        assertNull(ShadowFrame.of(1, true, true, NOON, 0, 0, 0, 64, 0, 0));
        assertNull(ShadowFrame.of(1, true, true, NOON, 0, 0.005f, 0, 64, 0, 0));
        assertNull(ShadowFrame.of(1, false, true, NOON, 0, 1, 0, 64, 0, 0));
        assertNull(ShadowFrame.of(1, true, false, NOON, 0, 1, 0, 64, 0, 0));
        ShadowFrame f = ShadowFrame.of(7, true, true, NOON, 0, 1, 10, 64, 20, 0);
        assertNotNull(f);
        assertEquals(7, f.serial());
        assertEquals(ShadowMath.NEAR_RADIUS, f.near().radius());
        assertEquals(ShadowMath.FAR_RADIUS, f.far().radius());
        assertEquals(10.0, f.near().cameraX());
        //Texel crawl pass (2026-10-16): the close cascade; wobble pass: its map is CLOSE_WIDTH x CLOSE_HEIGHT (texel: the coarser axis)
        assertEquals(ShadowMath.CLOSE_RADIUS, f.close().radius());
        assertEquals(2 * ShadowMath.CLOSE_RADIUS / ShadowMath.CLOSE_HEIGHT, f.close().texel(), 1e-6f);
        //Distant wobble (2026-10-22): the near and far maps are four times wider across map x; their texel (and bias) is unchanged
        assertEquals(4 * ShadowMath.MAP_SIZE, ShadowMath.NEAR_WIDTH);
        assertEquals(4 * ShadowMath.MAP_SIZE, ShadowMath.FAR_WIDTH);
        assertEquals(2 * ShadowMath.NEAR_RADIUS / ShadowMath.MAP_SIZE, f.near().texel(), 1e-6f);
        assertEquals(2 * ShadowMath.FAR_RADIUS / ShadowMath.MAP_SIZE, f.far().texel(), 1e-6f);
    }

    @Test
    void theFarCascadeIsDueEveryFewFrames() {
        int due = 0;
        for (int frame = 0; frame < 2 * ShadowMath.FAR_INTERVAL; frame++) {
            if (ShadowFrame.of(1, true, true, NOON, 0, 1, 0, 64, 0, frame).farDue()) due++;
        }
        assertEquals(2, due);
    }
}
