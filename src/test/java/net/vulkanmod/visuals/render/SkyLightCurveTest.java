package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7j design 3.4: the flattened sky brightness. */
class SkyLightCurveTest {
    /** LightTexture.getBrightness in the overworld (ambient light 0). */
    private static float vanilla(int level) {
        float x = level / 15f;
        return x / (4 - 3 * x);
    }

    @Test
    void zeroStrengthIsVanillaAndCavesStayDark() {
        for (int level = 0; level <= 15; level++) {
            assertEquals(vanilla(level), SkyLightCurve.apply(vanilla(level), 0), 0f);
        }
        assertEquals(0f, SkyLightCurve.apply(0, 1));
        assertEquals(1f, SkyLightCurve.apply(1, 1), 1e-6f);
    }

    /** Gate G7j: at full strength the overhang darkening is nearly gone (level 4 well above half), caves still 0. */
    @Test
    void fullStrengthNearlyRemovesTheOverhangDarkening() {
        assertEquals(0.95f, SkyLightCurve.FLATTEN, 0f);
        assertTrue(SkyLightCurve.apply(vanilla(4), 1) > 0.85f, String.valueOf(SkyLightCurve.apply(vanilla(4), 1)));
        assertEquals(0f, SkyLightCurve.apply(vanilla(0), 1));
    }

    @Test
    void flatterButStillRising() {
        float previous = 0;
        for (int level = 1; level <= 15; level++) {
            float g = SkyLightCurve.apply(vanilla(level), 0.6f);
            assertTrue(g >= vanilla(level), "level " + level);
            assertTrue(g >= previous, "level " + level);
            previous = g;
        }
        assertTrue(SkyLightCurve.apply(vanilla(8), 1) > SkyLightCurve.apply(vanilla(8), 0.5f));
        assertEquals((float) Math.pow(vanilla(8), 1 - SkyLightCurve.FLATTEN * 0.6), SkyLightCurve.apply(vanilla(8), 0.6f), 1e-6f);
    }
}
