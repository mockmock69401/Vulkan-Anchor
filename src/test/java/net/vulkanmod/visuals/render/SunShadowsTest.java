package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P7j design 3.4: the lightmap follows the last post-processed frame's shadows. Review focus 4. */
class SunShadowsTest {
    private static final PostProcessSettings DEFAULTS = PostProcessSettings.from(new VisualsConfig());

    @Test
    void theFlagFlattensTheSkyAndClamps() {
        try {
            SunShadows.set(0);
            assertEquals(0.25f, SunShadows.skyBrightness(0.25f), 0f);
            SunShadows.set(0.6f);
            assertEquals(SkyLightCurve.apply(0.25f, 0.6f), SunShadows.skyBrightness(0.25f), 0f);
            SunShadows.set(3);
            assertEquals(1f, SunShadows.flatten());
        } finally {
            SunShadows.set(0);
        }
    }

    @Test
    void onlyWhileTheSunShadingAndTheMapsAreOn() {
        LightingChoice sun = LightingChoice.of(42, DEFAULTS, true, true, false, true);
        assertEquals(1f, SunShadows.strength(DEFAULTS, sun, 42, true), 1e-6f);
        assertEquals(0f, SunShadows.strength(DEFAULTS, sun, 42, false));
        assertEquals(0f, SunShadows.strength(DEFAULTS, sun, 43, true));
        assertEquals(0f, SunShadows.strength(DEFAULTS, LightingChoice.of(42, DEFAULTS, true, true, false, false), 42, true));
        VisualsConfig off = new VisualsConfig();
        off.sunShadows = false;
        assertEquals(0f, SunShadows.strength(PostProcessSettings.from(off), sun, 42, true));
    }
}
