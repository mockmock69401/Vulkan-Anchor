package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-18: the moon's shadows at night (MoonShadowMath, ShadowFrame, LightingUniforms). */
class MoonShadowMathTest {
    private static final float NOON = 0, MIDNIGHT = 0.5f, EVENING = 0.354f;
    private static final float FULL_MOON = MoonShadowMath.strength(MoonShadowMath.MOONLIGHT_DEFAULT, 0);

    @Test
    void theMoonIsOppositeTheSun() {
        assertEquals(NOON, MoonShadowMath.lightTime(NOON));
        assertEquals(0.1f, MoonShadowMath.lightTime(0.1f));
        assertEquals(0f, MoonShadowMath.lightTime(MIDNIGHT), 1e-6f);
        for (float t : new float[]{0.26f, EVENING, MIDNIGHT, 0.6f, 0.74f}) {
            Vector3f sun = SunMath.sunDirection(t), moon = MoonShadowMath.lightDirection(t);
            assertEquals(-sun.x, moon.x, 1e-5f);
            assertEquals(-sun.y, moon.y, 1e-5f);
            assertTrue(moon.y > 0);
            float lt = MoonShadowMath.lightTime(t);
            assertTrue(lt >= 0 && lt < 1);
        }
    }

    @Test
    void theDayIsUnchanged() {
        for (float t = -0.24f; t <= 0.24f; t += 0.01f) {
            float day = (t + 1) % 1;
            assertEquals(SunMath.dayFactor(SunMath.sunDirection(day).y, 0.3f), MoonShadowMath.weight(day, 0.3f, 1), 0);
            assertEquals(MoonShadowMath.lightTime(day), day);
        }
    }

    @Test
    void theMoonIsWeakerThanTheSunAndFollowsTheMoonlightAndPhase() {
        assertEquals(MoonShadowMath.MOON_SHADOW, FULL_MOON, 1e-6f);
        assertTrue(FULL_MOON < 1);
        assertEquals(0f, MoonShadowMath.strength(MoonShadowMath.MOONLIGHT_DEFAULT, 4));
        assertEquals(FULL_MOON / 2, MoonShadowMath.strength(MoonShadowMath.MOONLIGHT_DEFAULT, 2), 1e-6f);
        assertEquals(FULL_MOON / 2, MoonShadowMath.strength(MoonShadowMath.MOONLIGHT_DEFAULT, 6), 1e-6f);
        assertEquals(0f, MoonShadowMath.strength(0, 0));
        assertEquals(1f, MoonShadowMath.strength(2, 0));
        assertEquals(0f, MoonShadowMath.strength(Float.NaN, 0));
        //Strongest with the moon high, none with it on the horizon, none in full rain
        assertEquals(FULL_MOON, MoonShadowMath.weight(MIDNIGHT, 0, FULL_MOON), 1e-6f);
        assertTrue(MoonShadowMath.weight(EVENING, 0, FULL_MOON) > 0.6f * FULL_MOON);
        assertEquals(0f, MoonShadowMath.weight(MIDNIGHT, 1, FULL_MOON));
        assertEquals(0f, MoonShadowMath.weight(MIDNIGHT, 0, 0));
        assertEquals(0f, MoonShadowMath.weight(MIDNIGHT, 0, Float.NaN));
    }

    /** Dusk and dawn: the weight goes to 0 at the horizon from both sides, in small steps (no pop), and only one light at a time. */
    @Test
    void theHandOverIsSmooth() {
        for (float centre : new float[]{0.25f, 0.75f}) {
            float last = Float.NaN;
            for (int i = -400; i <= 400; i++) {
                float t = centre + i * 0.0001f;
                float w = MoonShadowMath.weight(t, 0, FULL_MOON);
                if (!Float.isNaN(last)) assertTrue(Math.abs(w - last) < 0.01f, "step at " + t);
                last = w;
            }
            assertTrue(MoonShadowMath.weight(centre, 0, FULL_MOON) < 1e-3f);
        }
    }

    @Test
    void theShadowMapsTakeTheMoonAtNight() {
        assertNull(ShadowFrame.of(1, true, true, MIDNIGHT, 0, 1, 0, 64, 0, 0));
        assertNull(ShadowFrame.of(1, true, true, MIDNIGHT, 0, 1, 0, 64, 0, 0, 0));
        assertNull(ShadowFrame.of(1, true, true, MIDNIGHT, 0, 1, 0, 64, 0, 0, MoonShadowMath.strength(0.5f, 4)));
        assertNull(ShadowFrame.of(1, true, true, MIDNIGHT, 1, 1, 0, 64, 0, 0, FULL_MOON));
        ShadowFrame f = ShadowFrame.of(1, true, true, EVENING, 0, 1, 0, 64, 0, 0, FULL_MOON);
        assertNotNull(f);
        assertEquals(MoonShadowMath.lightTime(EVENING), f.timeOfDay(), 1e-6f);
        assertEquals(ShadowMath.cascade(MoonShadowMath.lightDirection(EVENING), 0, 64, 0, ShadowMath.NEAR_RADIUS, ShadowMath.NEAR_WIDTH,
                ShadowMath.NEAR_HEIGHT).viewProjection(), f.near().viewProjection());
        //By day the moon's strength changes nothing
        ShadowFrame day = ShadowFrame.of(1, true, true, 0.1f, 0, 1, 0, 64, 0, 0, FULL_MOON);
        assertEquals(ShadowFrame.of(1, true, true, 0.1f, 0, 1, 0, 64, 0, 0).near().viewProjection(), day.near().viewProjection());
        assertEquals(0.1f, day.timeOfDay());
    }

    @Test
    void theLightingPassIsLitByTheMoonAtNight() {
        VisualsConfig c = new VisualsConfig();
        c.sunShading = 100;
        PostProcessSettings s = PostProcessSettings.from(c);
        LightingChoice both = new LightingChoice(7, true, true, false, false);
        Matrix4f modelView = new Matrix4f().rotateX(0.4f).rotateY(0.9f);
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 2048);
        AtmosphereFrame night = AtmosphereFrame.of(7, proj, new Matrix4f(proj), modelView, 100, 63,
                new FogParameters(0.6f, 0.7f, 0.9f, 1f, -8, 1e6f, -8, 1e6f), 512, true, EVENING, 0, 0, null);
        LightingUniforms.Values v = LightingUniforms.compute(s, both, night, 7, true, 1, true, true, FULL_MOON);
        Vector3f moon = modelView.transformDirection(MoonShadowMath.lightDirection(EVENING)).normalize();
        assertEquals(moon.x, v.sunView()[0], 1e-5f);
        assertEquals(moon.y, v.sunView()[1], 1e-5f);
        assertEquals(moon.z, v.sunView()[2], 1e-5f);
        assertEquals(MoonShadowMath.weight(EVENING, 0, FULL_MOON), v.sunWeight(), 1e-6f);
        //The sun's warm colour and the day exposures stay the sun's (none at night); the term selects the moon's levels
        assertEquals(0f, v.lightSun()[4]);
        assertEquals(-1f, v.sunHeightTerm());
        assertEquals(0f, v.sunUp());
        //Without the moon's strength the night is as before
        assertEquals(0f, LightingUniforms.compute(s, both, night, 7, true, 1, true, true).sunWeight());
        assertEquals(0f, LightingUniforms.compute(s, both, night, 7, true, 1, true, false, FULL_MOON).sunWeight());
    }

    /** The moon's levels: the lit ground about as bright as the night without shading, the shade well below it, both below the sun's. */
    @Test
    void theMoonsLevels() {
        assertEquals(LightingMath.MOON_SHADE_LEVEL, LightingMath.shadeLevel(-1), 0);
        assertEquals(LightingMath.MOON_SUN_LEVEL, LightingMath.sunLevel(-1), 0);
        assertEquals(LightingMath.LOW_SUN_SHADE_LEVEL, LightingMath.shadeLevel(0), 0);
        assertEquals(-1f, LightingMath.lightTerm(-0.01f));
        assertEquals(LightingMath.sunHeightTerm(0.5f), LightingMath.lightTerm(0.5f));
        float w = MoonShadowMath.weight(MIDNIGHT, 0, FULL_MOON);
        float lit = 1 + (LightingMath.MOON_SUN_LEVEL - 1) * w, shade = 1 + (LightingMath.MOON_SHADE_LEVEL - 1) * w;
        assertTrue(lit > 1 && lit < 1.1f);
        assertTrue(shade > 0.4f && shade < 0.6f);
        assertTrue(lit / shade < LightingMath.SUN_LEVEL / LightingMath.SHADE_LEVEL);
    }
}
