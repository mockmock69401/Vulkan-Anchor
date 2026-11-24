package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.config.VisualsConfig;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-17 (the user: "at night the clouds should differ little from the sky; in game they look like storm clouds"): through the night a
 * cloud's sky light is the smooth sky in its direction, skyColour + sunGlow + the Milky Way band's glow (AtmosphereMath.cloudOver,
 * nightClouds), so a cloud is never darker than the sky behind it, a little lighter and greyer; by day and at sunset nothing changes.
 */
class NightCloudTest {
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
    /** Vanilla's time of day at 21000 ticks (the moon 45 degrees up in the west), noon, and the sunset (the sun about 3.6 degrees up). */
    private static final float NIGHT = 0.625f, NOON = 0, DUSK = 0.24f;

    private static AtmosphereFrame frame(float timeOfDay) {
        return new AtmosphereFrame(11, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(PROJ), 66, 63,
                0.75f, 0.4f, 0.3f, false, false, timeOfDay, 0, 0, null, new float[]{121 / 255f, 167 / 255f, 1}, 256, null,
                new CloudFrame(192, 100, 200, new float[]{1, 1, 1}));
    }

    private record Sky(AtmosphereMath.Atmosphere atmosphere, AtmosphereMath.CloudLight light) {
    }

    private static Sky sky(int palette, int style, float timeOfDay) {
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, palette);
        c.cloudStyle = style;
        c.farClouds = true;
        PostProcessSettings s = PostProcessSettings.from(c);
        AtmosphereFrame f = frame(timeOfDay);
        AtmosphereUniforms.Values a = AtmosphereUniforms.compute(s, f, 11, true, false);
        return new Sky(a.atmosphere(), CloudUniforms.compute(s, f, a, 11, true, true, 16).light());
    }

    private static float[] dir(float elevation, float azimuth) {
        double e = Math.toRadians(elevation), z = Math.toRadians(azimuth);
        return new float[]{(float) (Math.cos(e) * Math.sin(z)), (float) Math.sin(e), (float) (Math.cos(e) * Math.cos(z))};
    }

    /** The smooth sky behind a cloud: skyColour + sunGlow + band. */
    private static float[] behind(AtmosphereMath.Atmosphere a, float[] d, float[] band) {
        float[] sky = AtmosphereMath.skyColour(a, d), glow = AtmosphereMath.sunGlow(a, d);
        return new float[]{sky[0] + glow[0] + band[0], sky[1] + glow[1] + band[1], sky[2] + glow[2] + band[2]};
    }

    private static float luma(float[] c) {
        return PostMath.luminance(c[0], c[1], c[2]);
    }

    @Test
    void aNightCloudIsNeverDarkerThanTheSkyBehindIt() {
        for (int palette = 0; palette < ColourPresets.LEVELS; palette++) {
            for (int style : new int[]{CloudMath.STYLE_VANILLA, CloudMath.STYLE_REALISTIC, CloudMath.STYLE_ANIME}) {
                Sky s = sky(palette, style, NIGHT);
                assertEquals(1, AtmosphereMath.nightClouds(s.atmosphere()), 1e-6f);
                for (float el : new float[]{0.5f, 2, 5, 10, 20, 45, 80}) {
                    for (float az : new float[]{-90, 0, 90, 180}) {
                        float[] d = dir(el, az);
                        float[] behind = behind(s.atmosphere(), d, new float[3]);
                        //A whole cloud seen at its shaded base: no moonlight, the style's lowest sky-lit weight (0.25), no haze in front
                        float[] r = AtmosphereMath.cloudOver(s.atmosphere(), s.light(), new float[]{0, 0.25f, 1, 0}, d, 1);
                        String at = "palette " + palette + " style " + style + " el " + el + " az " + az;
                        assertTrue(luma(r) >= luma(behind) * 0.99f, at + ": " + luma(r) + " < sky " + luma(behind));
                        //A little lighter, not white: at most NIGHT_CLOUD_LIGHT of the sky without the moonlit part
                        assertTrue(luma(r) <= luma(behind) * AtmosphereMath.NIGHT_CLOUD_LIGHT * 1.01f + 1e-7f, at);
                    }
                }
            }
        }
    }

    @Test
    void theNightCloudsFollowTheSkysColourLessSaturated() {
        Sky s = sky(ColourPresets.ANIME, CloudMath.STYLE_ANIME, NIGHT);
        float[] d = dir(5, 0);
        float[] behind = behind(s.atmosphere(), d, new float[3]);
        float[] r = AtmosphereMath.cloudOver(s.atmosphere(), s.light(), new float[]{0, 1, 1, 0}, d, 1);
        //Blue as the sky (the Anime palette's blue night), with a smaller spread relative to its luminance
        assertTrue(behind[2] > behind[0] && r[2] > r[0], java.util.Arrays.toString(r));
        assertTrue((r[2] - r[0]) / luma(r) < (behind[2] - behind[0]) / luma(behind));
    }

    @Test
    void aCloudInFrontOfTheMilkyWayTakesItsGlow() {
        //The default palette's dark night with the band's teal glow (animestars.glsl's colour at a bright part of the map) behind
        Sky s = sky(ColourPresets.DEFAULT, CloudMath.STYLE_REALISTIC, NIGHT);
        float[] d = dir(30, 90);
        float[] band = {0.002f, 0.005f, 0.0064f};
        float[] r = AtmosphereMath.cloudOver(s.atmosphere(), s.light(), new float[]{0, 0.25f, 1, 0}, d, 1, band);
        float[] without = AtmosphereMath.cloudOver(s.atmosphere(), s.light(), new float[]{0, 0.25f, 1, 0}, d, 1);
        assertTrue(luma(r) >= luma(behind(s.atmosphere(), d, band)) * 0.99f, luma(r) + " < " + luma(behind(s.atmosphere(), d, band)));
        assertTrue(luma(r) > luma(without));
    }

    @Test
    void theMoonlitSideIsBrighterThanTheShadedSide() {
        Sky s = sky(ColourPresets.DEFAULT, CloudMath.STYLE_ANIME, NIGHT);
        float[] d = dir(10, -90);
        float[] shaded = AtmosphereMath.cloudOver(s.atmosphere(), s.light(), new float[]{0.4f, 1, 1, 50}, d, 1);
        float[] moonlit = AtmosphereMath.cloudOver(s.atmosphere(), s.light(), new float[]{1, 1, 1, 50}, d, 1);
        assertTrue(luma(moonlit) > luma(shaded));
    }

    @Test
    void dayAndSunsetCloudsAreUnchanged() {
        for (float t : new float[]{NOON, DUSK}) {
            Sky s = sky(ColourPresets.ANIME, CloudMath.STYLE_ANIME, t);
            assertEquals(0, AtmosphereMath.nightClouds(s.atmosphere()), 0f, "time " + t);
            float[] march = {0.6f, 0.8f, 1, 300};
            float[] d = dir(5, 90);
            float[] cloud = AtmosphereMath.cloudColour(s.light(), march);
            AtmosphereMath.Haze h = AtmosphereMath.haze(s.atmosphere(), d, 300, 1);
            float[] want = new float[4];
            for (int i = 0; i < 3; i++) want[i] = cloud[i] * h.transmittance()[i] + h.inscatter()[i] * cloud[3];
            want[3] = cloud[3];
            assertArrayEquals(want, AtmosphereMath.cloudOver(s.atmosphere(), s.light(), march, d, 1), 0f, "time " + t);
        }
    }

    @Test
    void theDesertsClearingStillTakesTheNightClouds() {
        Sky s = sky(ColourPresets.ANIME, CloudMath.STYLE_ANIME, NIGHT);
        float[] l = s.light().light().clone();
        l[3] = 1;
        AtmosphereMath.CloudLight cleared = new AtmosphereMath.CloudLight(s.light().lit(), s.light().ambient(), l);
        assertArrayEquals(new float[4], AtmosphereMath.cloudOver(s.atmosphere(), cleared, new float[]{1, 1, 1, 50}, dir(5, 0), 1), 1e-9f);
    }
}
