package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moonlight design 3.1: the lightmap's sky-light factor (skyDarken * 0.95 + 0.05) with a night floor. */
class MoonlightTest {
    //timeOfDay 0 is noon, 0.25 the sun on the horizon, 0.5 midnight (SunMath.sunDirection)
    private static final float NOON = 0, SUNSET = 0.25f, MIDNIGHT = 0.5f;
    private static final float NIGHT = 0.2f * 0.95f + 0.05f, DAY = 1f, DAY_RAIN = ((1 - 5f / 16) * 0.8f + 0.2f) * 0.95f + 0.05f,
            DAY_THUNDER = ((1 - 5f / 16) * (1 - 5f / 16) * 0.8f + 0.2f) * 0.95f + 0.05f;

    @Test
    void nightRisesToTheFloor() {
        assertEquals(0.4f, Moonlight.skyFactor(NIGHT, 0.5f, MIDNIGHT, 0, 0, true), 1e-6f);
        assertEquals(Moonlight.FLOOR_MAX, Moonlight.skyFactor(NIGHT, 1f, MIDNIGHT, 0, 0, true), 1e-6f);
    }

    /** Review focus 2: by day (thunder 0.599 included) and with the sun on the horizon nothing changes, at any setting. */
    @Test
    void daylightIsNeverRaised() {
        for (float vanilla : new float[]{DAY, DAY_RAIN, DAY_THUNDER}) {
            assertEquals(vanilla, Moonlight.skyFactor(vanilla, 1f, NOON, 0, 0, true), "factor " + vanilla);
        }
        assertEquals(0.39f, Moonlight.skyFactor(0.39f, 1f, SUNSET, 0, 0, true), 1e-6f);
    }

    /** Review focus 1: a lightning flash (factor 1), the setting at 0 and the off conditions keep vanilla's value. */
    @Test
    void lightningAndOffConditionsKeepVanilla() {
        assertEquals(1f, Moonlight.skyFactor(1f, 1f, MIDNIGHT, 0, 0, true));
        assertEquals(NIGHT, Moonlight.skyFactor(NIGHT, 0f, MIDNIGHT, 0, 0, true));
        assertEquals(NIGHT, Moonlight.skyFactor(NIGHT, 1f, MIDNIGHT, 0, 0, false));
    }

    @Test
    void outOfRangeSettingsAreClamped() {
        assertEquals(Moonlight.FLOOR_MAX, Moonlight.skyFactor(NIGHT, 3f, MIDNIGHT, 0, 0, true), 1e-6f);
        assertEquals(NIGHT, Moonlight.skyFactor(NIGHT, -1f, MIDNIGHT, 0, 0, true));
    }

    /** ClientLevel.getSkyDarken then updateLightTexture's skyDarken * 0.95 + 0.05, at sun height cos(2 pi timeOfDay). */
    static float vanilla(float sunY, float rain, float thunder) {
        float d = Math.max(0, Math.min(1, sunY * 2 + 0.2f));
        d *= (1 - rain * 5 / 16f) * (1 - thunder * 5 / 16f);
        return (d * 0.8f + 0.2f) * 0.95f + 0.05f;
    }

    static float timeOfDay(float sunY) {
        return (float) (Math.acos(sunY) / (2 * Math.PI));
    }

    /**
     * Final review: as the sun sets, sky-lit terrain moves one way only, from vanilla's horizon value to the night level
     * (vanilla's 0.24 or the moonlight floor, whichever is higher), without first dipping below both.
     */
    @Test
    void duskMovesOneWayFromTheHorizonToTheNight() {
        for (float[] weather : new float[][]{{0, 0}, {1, 0}, {1, 1}})
        for (float moonlight : new float[]{0f, 0.25f, 0.5f, 1f}) {
            float rain = weather[0], thunder = weather[1];
            float horizon = vanilla(0, rain, thunder);
            float night = Moonlight.skyFactor(vanilla(-1, rain, thunder), moonlight, timeOfDay(-1), rain, thunder, true);
            float previous = horizon;
            for (int i = 1; i <= 200; i++) {
                float sunY = -0.2f * i / 200;
                float f = Moonlight.skyFactor(vanilla(sunY, rain, thunder), moonlight, timeOfDay(sunY), rain, thunder, true);
                if (moonlight == 0) assertEquals(vanilla(sunY, rain, thunder), f, "0 is vanilla's dusk");
                assertTrue(f >= Math.min(horizon, night) - 1e-4f && f <= Math.max(horizon, night) + 1e-4f, "moonlight " + moonlight + " sun " + sunY + ": " + f);
                assertTrue(night >= horizon ? f >= previous - 1e-4f : f <= previous + 1e-4f, "moonlight " + moonlight + " sun " + sunY + ": " + previous + " -> " + f);
                previous = f;
            }
        }
    }
}
