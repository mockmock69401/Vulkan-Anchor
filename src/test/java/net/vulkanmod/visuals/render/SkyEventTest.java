package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ExperimentalFeatures;
import net.vulkanmod.visuals.config.PostOn;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-21: the night sky's events, Experimental > Sky (SkyEventMath, skyevents.glsl): the stars' twinkle and meteors. */
class SkyEventTest {
    private static final float EPS = 1e-6f;

    @Test
    void everyConstMatchesTheJavaMirror() throws Exception {
        String g = ShaderFiles.read("post/skyevents.glsl");
        Matcher m = Pattern.compile("^const float ([A-Z_]+) = ([-0-9.]+);", Pattern.MULTILINE).matcher(g);
        int n = 0;
        while (m.find()) {
            Field f = SkyEventMath.class.getField(m.group(1));
            assertEquals(f.getFloat(null), Float.parseFloat(m.group(2)), EPS, m.group(1));
            n++;
        }
        assertEquals(29, n, "consts found");
    }

    @Test
    void theCompositeReadsTheSlot() {
        String copy = ShaderFiles.read("post/scene_copy.comp");
        assertTrue(copy.contains("layout(location = " + SkyEventMath.SLOT + ") uniform vec4 skyEvents;"), "the slot");
        assertTrue(copy.contains("#import <vulkanmod:visuals/post/skyevents.glsl>"));
        assertTrue(copy.contains("stars += meteors(dir, skyEvents, meteorShape, meteorEase, starGate.x, pixelAngle);"));
        assertTrue(copy.contains("layout(location = " + SkyEventMath.SHAPE_SLOT + ") uniform vec4 meteorShape;"), "the shape's slot");
        assertTrue(copy.contains("layout(location = " + SkyEventMath.EASE_SLOT + ") uniform vec4 meteorEase;"), "the ease's slot");
        assertTrue(copy.contains("twinkle = starTwinkle(animeSkyDir(dir, a.sun.xyz), dir.y, skyEvents);"));
    }

    /** Mostly a shimmer around the star's own light, sometimes a flare well above it; never negative. */
    @Test
    void theTwinkleDipsAndFlares() {
        assertEquals(1f, SkyEventMath.twinkleFactor(0, 1), EPS);
        assertEquals(1 - SkyEventMath.TWINKLE_DIM, SkyEventMath.twinkleFactor(-1, 1), EPS);
        float top = SkyEventMath.twinkleFactor(1, 1);
        assertTrue(top > 2.8f && top < 3f, "the flare at the top: " + top);
        assertTrue(SkyEventMath.twinkleFactor(-1, 4) >= 0);
        //the mean over the sum of three sines stays near the star's own light (it neither dims nor brightens the sky on average)
        double sum = 0;
        int n = 0;
        for (double t = 0; t < 600; t += 0.01, n++) {
            float s = (float) ((Math.sin(t * SkyEventMath.TWINKLE_RATE_A + 0.3) + Math.sin(t * SkyEventMath.TWINKLE_RATE_B + 1.9)
                    + Math.sin(t * SkyEventMath.TWINKLE_RATE_C + 4.1)) / 3);
            sum += SkyEventMath.twinkleFactor(s, 1);
        }
        double mean = sum / n;
        assertTrue(mean > 1.0 && mean < 1.25, "mean " + mean);
    }

    @Test
    void theSlotFollowsTheSettings() {
        VisualsConfig c = PostOn.defaults();
        float[] p = SkyEventMath.params(c, true, 3600.0 + 12.5);
        assertArrayEquals(new float[]{1f, SkyEventMath.METEORS_A_MINUTE, 12.5f, 1f}, p, EPS);
        assertArrayEquals(new float[4], SkyEventMath.params(new VisualsConfig(), true, 5), "the post-process off (the new default)");
        assertArrayEquals(new float[4], SkyEventMath.params(c, false, 5), "no sky drawn");
        assertArrayEquals(new float[4], SkyEventMath.params(null, true, 5));
        c.starTwinkle = false;
        c.meteorAmount = 400;
        c.meteorBrightness = 150;
        assertArrayEquals(new float[]{0, 4 * SkyEventMath.METEORS_A_MINUTE, 5, 1.5f}, SkyEventMath.params(c, true, 5), EPS);
        c.meteors = false;
        assertArrayEquals(new float[4], SkyEventMath.params(c, true, 5), "both off");
        c.starTwinkle = true;
        c.starTwinkleStrength = 999;
        assertArrayEquals(new float[]{2, 0, 5, 0}, SkyEventMath.params(c, true, 5), EPS);
        c.meteors = true;
        c.meteorBrightness = 0;
        assertEquals(0, SkyEventMath.params(c, true, 5)[1], "no light: no meteors evaluated");
    }

    /** The lanes together bring the rate a minute on average, and a meteor's life fits in its slot even at the most. */
    @Test
    void theSlotsBringTheRate() {
        float rate = SkyEventMath.METEOR_AMOUNT_MAX * SkyEventMath.METEORS_A_MINUTE / 100f;
        float slot = SkyEventMath.slotSeconds(rate);
        assertEquals(rate, SkyEventMath.METEOR_LANES * SkyEventMath.METEOR_CHANCE * 60f / slot, 1e-4f);
        assertTrue(slot > SkyEventMath.longestLife(), "slot " + slot + ", life " + SkyEventMath.longestLife());
    }

    /** The speed's bezier runs from 0 to 1, follows CSS cubic-bezier (x(t) = u, then y(t)), and a steady one is the identity. */
    @Test
    void theSpeedCurveIsACubicBezier() {
        for (float u = 0; u <= 1.0001f; u += 0.05f) {
            assertEquals(u, SkyEventMath.meteorEased(u, 0, 0, 1, 1), 1e-3f, "linear at " + u);
        }
        assertEquals(0, SkyEventMath.meteorEased(0, 0, 0, 0.58f, 1), 1e-3f);
        assertEquals(1, SkyEventMath.meteorEased(1, 0, 0, 0.58f, 1), 1e-3f);
        //ease-out: ahead of a steady meteor midway
        assertTrue(SkyEventMath.meteorEased(0.5f, 0, 0, 0.58f, 1) > 0.6f);
        float[][] curves = {{0, 0, 0.58f, 1}, {0.42f, 0, 1, 1}, {1, 0, 0, 1}, {0, 1, 1, 0}, {1, 1, 1, 1}, {0, 0, 0, 0}};
        for (float[] k : curves) {
            float last = -1;
            for (float u = 0; u <= 1.0001f; u += 0.01f) {
                float y = SkyEventMath.meteorEased(u, k[0], k[1], k[2], k[3]);
                assertTrue(y >= last - 1e-3f && y >= 0 && y <= 1, "monotonic " + Arrays.toString(k) + " at " + u);
                //x(t) = u for the t the halving found: against a fine search
                float best = 0, bestGap = 2;
                for (float t = 0; t <= 1.0001f; t += 1e-4f) {
                    float gap = Math.abs(SkyEventMath.bezierAxis(t, k[0], k[2]) - u);
                    if (gap < bestGap) {
                        bestGap = gap;
                        best = t;
                    }
                }
                assertEquals(SkyEventMath.bezierAxis(best, k[1], k[3]), y, 2e-2f, "y at " + u + " for " + Arrays.toString(k));
                last = y;
            }
        }
    }

    @Test
    void theShapeAndEaseFollowTheSettings() {
        VisualsConfig c = new VisualsConfig();
        assertArrayEquals(new float[]{SkyEventMath.METEOR_BEND, 1, 0, 0}, SkyEventMath.shape(c), EPS);
        assertArrayEquals(new float[]{0, 0, 0.58f, 1}, SkyEventMath.ease(c), EPS);
        c.meteorCurve = 999;
        c.meteorDuration = 1;
        c.meteorEaseX1 = -5;
        c.meteorEaseY2 = 300;
        assertArrayEquals(new float[]{2 * SkyEventMath.METEOR_BEND, SkyEventMath.METEOR_DURATION_MIN / 100f, 0, 0}, SkyEventMath.shape(c), EPS);
        assertArrayEquals(new float[]{0, 0, 0.58f, 1}, SkyEventMath.ease(c), EPS);
        c.meteorCurve = 0;
        c.meteorDuration = 300;
        assertArrayEquals(new float[]{0, 3, 0, 0}, SkyEventMath.shape(c), EPS);
    }

    @Test
    void theSettingsAreOnTheExperimentalPagesSkyBlock() {
        for (String key : new String[]{"star_twinkle", "star_twinkle_strength", "meteors", "meteor_amount", "meteor_brightness", "meteor_curve",
                "meteor_duration", "meteor_ease_x1", "meteor_ease_y1", "meteor_ease_x2", "meteor_ease_y2"}) {
            ExperimentalFeatures.Entry e = ExperimentalFeatures.ENTRIES.stream().filter(x -> x.item().key().equals(key)).findFirst().orElseThrow();
            assertEquals(ExperimentalFeatures.SKY, e.block(), key);
        }
        VisualsConfig c = new VisualsConfig();
        assertTrue(c.starTwinkle && c.meteors);
        assertEquals(100, c.starTwinkleStrength);
        assertEquals(100, c.meteorAmount);
        assertEquals(100, c.meteorBrightness);
    }
}
