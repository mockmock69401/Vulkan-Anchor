package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W2: the final pass's maths (PostMath mirrors postmath.glsl / postfinal.glsl / post_blit.frag). */
class PostMathTest {
    private static final int W = 2560, H = 1440;
    private static final float NOON = 0, SUNSET = 0.235f;

    private static PostProcessSettings settings(Consumer<VisualsConfig> change) {
        VisualsConfig c = new VisualsConfig();
        change.accept(c);
        return PostProcessSettings.from(c);
    }

    /** Every effect but the one under test neutral, so a single setting can be checked on its own. */
    private static VisualsConfig neutral(VisualsConfig c) {
        c.exposure = 100;
        c.toneStrength = 0;
        c.contrast = 100;
        c.saturation = 100;
        c.gradeStrength = 0;
        c.bloomStrength = 0;
        c.vignetteStrength = 0;
        return c;
    }

    private static PostUniforms.Values values(Consumer<VisualsConfig> change) {
        return PostUniforms.compute(settings(change), W, H, NOON, true, 7, new float[]{0, 0, 0});
    }

    // ---- 1. tone mapping ----

    @Test
    void toneMappingMapsZeroToZeroIsMonotonicAndStaysInUnitRange() {
        for (float strength : new float[]{0, 0.25f, 0.5f, 1}) {
            float knee = PostMath.toneKnee(strength);
            assertEquals(0f, PostMath.toneMap(0, knee), 0f, "strength " + strength);
            float last = -1;
            for (int i = 0; i <= 200_000; i++) {
                float x = i * 0.001f;//0 .. 200 scene units, past any sun disc
                float y = PostMath.toneMap(x, knee);
                assertTrue(y >= 0 && y <= 1, "strength " + strength + " x " + x + " -> " + y);
                assertTrue(y >= last, "not monotonic at strength " + strength + " x " + x);
                last = y;
            }
            assertTrue(PostMath.toneMap(Float.MAX_VALUE, knee) <= 1);
        }
    }

    @Test
    void atToneStrengthZeroTheToneMappingIsTheIdentityUpToClipping() {
        float knee = PostMath.toneKnee(0);
        for (int i = 0; i <= 5000; i++) {
            float x = i * 0.001f;
            assertEquals(Math.min(x, 1f), PostMath.toneMap(x, knee), 1e-6f, "x " + x);
        }
    }

    @Test
    void theShoulderRollsHighlightsOffWithoutClippingThem() {
        float knee = PostMath.toneKnee(1);
        //Midtones are kept as they are; a white surface in full daylight is bright but not clipped; the sun is still brighter
        assertEquals(0.2f, PostMath.toneMap(0.2f, knee), 1e-6f);
        float white = PostMath.linearToDisplay(PostMath.toneMap(PostMath.SCENE_WHITE, knee));
        assertTrue(white > 0.88f && white < 0.97f, "daylight white -> display " + white);
        assertTrue(PostMath.toneMap(4, knee) > PostMath.toneMap(2, knee));
        assertTrue(PostMath.toneMap(2, knee) < 1);
    }

    // ---- 2. each setting neutral at its neutral value ----

    @Test
    void contrastOneHundredIsNeutral() {
        for (int i = 0; i <= 1000; i++) {
            float x = i / 1000f;
            assertEquals(x, PostMath.contrast(x, 1), 1e-6f);
        }
        //and contrast keeps black, mid-grey and white where they are, monotonic either way
        for (float c : new float[]{0.5f, 1.5f}) {
            assertEquals(0f, PostMath.contrast(0, c), 1e-6f);
            assertEquals(0.5f, PostMath.contrast(0.5f, c), 1e-6f);
            assertEquals(1f, PostMath.contrast(1, c), 1e-6f);
            float last = -1;
            for (int i = 0; i <= 1000; i++) {
                float y = PostMath.contrast(i / 1000f, c);
                assertTrue(y >= last);
                last = y;
            }
        }
        assertTrue(PostMath.contrast(0.8f, 1.5f) > 0.8f);
        assertTrue(PostMath.contrast(0.2f, 1.5f) < 0.2f);
    }

    @Test
    void saturationOneHundredIsNeutral() {
        float[] c = {0.7f, 0.3f, 0.1f};
        assertArrayEquals(c, PostMath.saturate(c.clone(), 1), 1e-7f);
        float[] grey = PostMath.saturate(c.clone(), 0);
        assertEquals(grey[0], grey[1], 1e-6f);
        assertEquals(grey[1], grey[2], 1e-6f);
        assertEquals(PostMath.luminance(c[0], c[1], c[2]), grey[0], 1e-6f);
    }

    @Test
    void gradeZeroIsNeutralAndFullGradeWarmsTheSunset() {
        for (float time : new float[]{NOON, SUNSET, 0.5f}) {
            PostUniforms.Values v = PostUniforms.compute(settings(c -> c.gradeStrength = 0), W, H, time, true, 7, null);
            assertArrayEquals(new float[]{1, 1, 1}, v.warm(), 0f);
            assertArrayEquals(new float[]{1, 1, 1}, v.cool(), 0f);
        }
        PostUniforms.Values noon = PostUniforms.compute(settings(c -> c.gradeStrength = 100), W, H, NOON, true, 7, null);
        PostUniforms.Values sunset = PostUniforms.compute(settings(c -> c.gradeStrength = 100), W, H, SUNSET, true, 7, null);
        PostUniforms.Values night = PostUniforms.compute(settings(c -> c.gradeStrength = 100), W, H, 0.5f, true, 7, null);
        PostUniforms.Values noSky = PostUniforms.compute(settings(c -> c.gradeStrength = 100), W, H, NOON, false, 7, null);
        float noonRatio = noon.warm()[0] / noon.warm()[2], sunsetRatio = sunset.warm()[0] / sunset.warm()[2];
        assertTrue(noonRatio > 1, "noon warm " + noonRatio);
        assertTrue(sunsetRatio > noonRatio * 1.3f && sunset.warm()[0] > sunset.warm()[1] && sunset.warm()[1] > sunset.warm()[2], "sunset " + sunsetRatio + " noon " + noonRatio);
        assertTrue(noon.cool()[2] > noon.cool()[0], "shade is cooled");
        //Tints keep luminance
        assertEquals(1f, PostMath.luminance(sunset.warm()[0], sunset.warm()[1], sunset.warm()[2]), 1e-5f);
        assertEquals(1f, PostMath.luminance(noon.cool()[0], noon.cool()[1], noon.cool()[2]), 1e-5f);
        //Night and no-sky dimensions take no grade
        assertArrayEquals(new float[]{1, 1, 1}, night.warm(), 0f);
        assertArrayEquals(new float[]{1, 1, 1}, night.cool(), 0f);
        assertArrayEquals(new float[]{1, 1, 1}, noSky.warm(), 0f);
        assertArrayEquals(new float[]{1, 1, 1}, noSky.cool(), 0f);
    }

    @Test
    void vignetteZeroIsNeutralAndFullVignetteDarkensOnlyTheCorners() {
        for (float x : new float[]{0.5f, 100.5f, W / 2f, W - 0.5f}) {
            for (float y : new float[]{0.5f, H / 2f, H - 0.5f}) {
                assertEquals(1f, PostMath.vignette(x, y, W, H, 0), 0f);
            }
        }
        assertEquals(1f, PostMath.vignette(W / 2f, H / 2f, W, H, 1), 1e-6f);
        float corner = PostMath.vignette(0, 0, W, H, 1);
        assertTrue(corner > 0.6f && corner < 0.9f, "corner " + corner);
        assertTrue(PostMath.vignette(W / 4f, H / 4f, W, H, 1) > corner);
    }

    @Test
    void bloomZeroIsNeutral() {
        PostUniforms.Values v = values(c -> neutral(c));
        float[] scene = {0.3f, 0.2f, 0.1f};
        float[] a = PostMath.finalColour(v, scene, new float[]{0, 0, 0}, 0, W / 2f, H / 2f);
        float[] b = PostMath.finalColour(v, scene, new float[]{50, 40, 30}, 0, W / 2f, H / 2f);
        assertArrayEquals(a, b, 0f);
        PostUniforms.Values on = values(c -> neutral(c).bloomStrength = 100);
        float[] glow = PostMath.finalColour(on, scene, new float[]{50, 40, 30}, 0, W / 2f, H / 2f);
        assertTrue(glow[0] > a[0]);
    }

    @Test
    void sharpenZeroIsNeutral() {
        float[] c = {0.5f, 0.4f, 0.3f}, n = {0.9f, 0.9f, 0.9f}, s = {0.1f, 0.1f, 0.1f}, e = {0.2f, 0.6f, 0.4f}, w = {0.7f, 0.2f, 0.3f};
        assertArrayEquals(c, PostMath.sharpen(c, n, s, e, w, 0), 0f);
        assertEquals(0f, PostMath.sharpenStrength(false));
        float strength = PostMath.sharpenStrength(true);
        assertTrue(strength > 0 && strength <= 2, "TAA softens: sharpen after it");
        //Never outside the neighbourhood's range (no halos)
        float[] sharp = PostMath.sharpen(new float[]{0.5f, 0.5f, 0.5f}, n, s, n, s, 1);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f}, sharp, 1e-6f);
        float[] edge = PostMath.sharpen(new float[]{0.6f, 0.6f, 0.6f}, new float[]{0.5f, 0.5f, 0.5f}, new float[]{0.5f, 0.5f, 0.5f},
                new float[]{0.5f, 0.5f, 0.5f}, new float[]{0.9f, 0.9f, 0.9f}, 1);
        assertTrue(edge[0] >= 0.5f && edge[0] <= 0.9f);
    }

    /** All the neutral values together: the scene comes out as it went in (up to clipping), only converted to display. */
    @Test
    void theWholeChainAtNeutralSettingsOnlyConvertsToDisplay() {
        PostUniforms.Values v = values(c -> neutral(c));
        for (float x : new float[]{0, 0.001f, 0.01f, 0.18f, 0.5f, 0.99f, 3}) {
            float[] out = PostMath.finalColour(v, new float[]{x, x * 0.5f, x * 0.25f}, new float[]{9, 9, 9}, 0, 1.5f, 3.5f);
            assertEquals(PostMath.linearToDisplay(Math.min(x, 1)), out[0], 1e-5f, "x " + x);
            assertEquals(PostMath.linearToDisplay(Math.min(x * 0.5f, 1)), out[1], 1e-5f);
            assertEquals(PostMath.linearToDisplay(Math.min(x * 0.25f, 1)), out[2], 1e-5f);
        }
    }

    // ---- 3. linear <-> display ----

    @Test
    void linearToDisplayToLinearRoundTrips() {
        for (int i = 0; i <= 100_000; i++) {
            float x = i / 100_000f;
            assertEquals(x, PostMath.displayToLinear(PostMath.linearToDisplay(x)), 1e-5f, "x " + x);
            assertEquals(x, PostMath.linearToDisplay(PostMath.displayToLinear(x)), 1e-5f, "d " + x);
        }
        assertEquals(0f, PostMath.linearToDisplay(0), 0f);
        assertEquals(1f, PostMath.linearToDisplay(1), 1e-6f);
        //sRGB: 18% grey is about 46% on the display
        assertEquals(0.4613f, PostMath.linearToDisplay(0.18f), 1e-3f);
    }

    // ---- 4. moonlit terrain ----

    /**
     * refs/S3.png (night, clear, high above a forest): the darkest forest canopy reads about (9, 15, 9) of 255 on screen and typical
     * terrain about (15, 28, 14); decoded from sRGB those are linear luminance 0.0040 and 0.0091. The test feeds the darker value as
     * a slightly green linear colour at exposure 100 and the default settings (and the player's settings of scenes.md) and
     * requires it to land well above black: at least 10 of 255 on screen, and at least 8 steps of 255 above a black input.
     */
    @Test
    void moonlitTerrainStaysClearlyAboveBlack() {
        float[] moonlitTerrain = {0.0030f, 0.0048f, 0.0030f};//luminance 0.0043
        //Bloom level 0 of a uniform dark scene: the scene summed over the 7 levels (no part above the threshold)
        float[] moonlitBloom = {0.0030f * 7, 0.0048f * 7, 0.0030f * 7};
        float[] black = {0, 0, 0};
        for (PostUniforms.Values v : new PostUniforms.Values[]{
                PostUniforms.compute(settings(c -> { }), W, H, 0.5f, true, 7, new float[]{0, 0, 0}),
                PostUniforms.compute(settings(c -> {
                    c.saturation = 107;
                    c.gradeStrength = 0;
                    c.bloomStrength = 12;
                }), W, H, 0.5f, true, 7, new float[]{0, 0, 0})}) {
            for (float[] at : new float[][]{{W / 2f, H / 2f}, {W * 0.2f, H * 0.8f}}) {
                float[] out = PostMath.finalColour(v, moonlitTerrain, moonlitBloom, 0, at[0], at[1]);
                float[] zero = PostMath.finalColour(v, black, black, 0, at[0], at[1]);
                float lum = PostMath.luminance(out[0], out[1], out[2]);
                assertTrue(lum * 255 >= 10, "moonlit terrain on screen " + lum * 255 + "/255");
                assertTrue((lum - PostMath.luminance(zero[0], zero[1], zero[2])) * 255 >= 8, "distinguishable from black");
            }
        }
    }

    // ---- 7. bloom pyramid ----

    @Test
    void bloomSizesGiveSeveralShrinkingLevels() {
        for (int[] size : new int[][]{{1920, 1080}, {2560, 1440}, {1280, 720}, {3840, 2160}, {1921, 1081}}) {
            int[][] levels = PostMath.bloomSizes(size[0], size[1]);
            assertTrue(levels.length >= 2, size[0] + "x" + size[1]);
            for (int i = 0; i < levels.length; i++) {
                assertTrue(levels[i][0] >= 1 && levels[i][1] >= 1);
                if (i > 0) {
                    assertTrue(levels[i][0] <= levels[i - 1][0] && levels[i][1] <= levels[i - 1][1]);
                }
            }
            //Level 0 is the half-resolution image the light shafts also use (shafts.comp reads the scene at twice its coordinate)
            assertEquals((size[0] + 1) / 2, levels[0][0]);
            assertEquals((size[1] + 1) / 2, levels[0][1]);
        }
        //Wide: the smallest level of 1080p is a few dozen texels across at most
        int[][] hd = PostMath.bloomSizes(1920, 1080);
        assertTrue(hd[hd.length - 1][1] <= 16, "smallest level height " + hd[hd.length - 1][1]);
        assertTrue(hd.length <= 8);
        //Tiny or degenerate sizes still give one valid level
        for (int[] size : new int[][]{{1, 1}, {0, 0}, {3, 1}, {16, 9}}) {
            int[][] levels = PostMath.bloomSizes(size[0], size[1]);
            assertTrue(levels.length >= 1);
            for (int[] l : levels) assertTrue(l[0] >= 1 && l[1] >= 1);
        }
    }
}
