package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 25: the desert share (classify, the kernel, the follow) and the desert's night sky, stars and clear sky. */
class BiomeSkyTest {
    private static final float EPS = 1e-5f;

    @Test
    void classifiesDesertsBadlandsAndNotSavannas() {
        //vanilla: desert 2.0 dry, badlands 2.0 dry (tagged), savanna 2.0 dry (tagged savanna), plains 0.8 wet, snowy plains 0.0 wet
        assertEquals(1, BiomeSkyMath.classify(false, false, false, false, 2.0f));
        assertEquals(1, BiomeSkyMath.classify(false, true, false, false, 2.0f));
        assertEquals(0, BiomeSkyMath.classify(false, false, true, false, 2.0f));
        assertEquals(0, BiomeSkyMath.classify(false, false, false, true, 0.8f));
        assertEquals(0, BiomeSkyMath.classify(false, false, false, true, 0.0f));
        //a modded desert tag wins over anything; a hot wet jungle (0.95, rain) is not one; a dry but mild biome is not one
        assertEquals(1, BiomeSkyMath.classify(true, false, true, true, 0.5f));
        assertEquals(0, BiomeSkyMath.classify(false, false, false, true, 2.0f));
        assertEquals(0, BiomeSkyMath.classify(false, false, false, false, 1.0f));
        assertEquals(0, BiomeSkyMath.classify(false, false, false, false, Float.NaN));
    }

    @Test
    void kernelWeightsAreNormalisedSymmetricAndCentred() {
        int n = BiomeSkyMath.KERNEL * BiomeSkyMath.KERNEL;
        float sum = 0;
        for (int i = 0; i < n; i++) {
            sum += BiomeSkyMath.weight(i);
            //the mirror sample has the same weight
            assertEquals(BiomeSkyMath.weight(i), BiomeSkyMath.weight(n - 1 - i), EPS);
        }
        assertEquals(1, sum, 1e-4f);
        int centre = n / 2;
        assertEquals(0, BiomeSkyMath.offset(centre, 0));
        assertEquals(0, BiomeSkyMath.offset(centre, 1));
        for (int i = 0; i < n; i++) if (i != centre) assertTrue(BiomeSkyMath.weight(i) < BiomeSkyMath.weight(centre));
        assertEquals(-2 * BiomeSkyMath.SPACING, BiomeSkyMath.offset(0, 0));
        assertEquals(2 * BiomeSkyMath.SPACING, BiomeSkyMath.offset(n - 1, 1));
    }

    @Test
    void kernelRampsAcrossABorder() {
        int n = BiomeSkyMath.KERNEL * BiomeSkyMath.KERNEL;
        float[] all = new float[n];
        Arrays.fill(all, 1);
        assertEquals(1, BiomeSkyMath.kernel(all), EPS);
        assertEquals(0, BiomeSkyMath.kernel(new float[n]), EPS);
        //a straight border along z: the share rises monotonically as the camera walks from x = -3 cells to +3 cells into the desert
        float last = -1;
        for (int border = 3; border >= -3; border--) {
            float[] s = new float[n];
            for (int i = 0; i < n; i++) s[i] = BiomeSkyMath.offset(i, 0) / BiomeSkyMath.SPACING >= border ? 1 : 0;
            float k = BiomeSkyMath.kernel(s);
            assertTrue(k >= last, "monotonic");
            last = k;
        }
        //right on the border (the middle column half in): one half plus half the middle column
        float[] half = new float[n];
        for (int i = 0; i < n; i++) half[i] = BiomeSkyMath.offset(i, 0) > 0 ? 1 : BiomeSkyMath.offset(i, 0) == 0 ? 0.5f : 0;
        assertEquals(0.5f, BiomeSkyMath.kernel(half), 1e-4f);
    }

    @Test
    void followIsExponentialAndFrameRateIndependent() {
        assertEquals(1, BiomeSkyMath.follow(Float.NaN, 1, 0.016f), EPS);
        assertEquals(0.3f, BiomeSkyMath.follow(0.3f, 1, 0), EPS);
        float one = BiomeSkyMath.follow(0, 1, BiomeSkyMath.FOLLOW_SECONDS);
        assertEquals(1 - Math.exp(-1), one, 1e-5);
        float many = 0;
        for (int i = 0; i < 100; i++) many = BiomeSkyMath.follow(many, 1, BiomeSkyMath.FOLLOW_SECONDS / 100);
        assertEquals(one, many, 1e-4f);
        assertEquals(0, BiomeSkyMath.follow(0.5f, -3, 1e9f), EPS);
    }

    @Test
    void nightTintIsOneOutsideDesertsAndDarkerDeeperInside() {
        assertArrayEquals(new float[]{1, 1, 1}, BiomeSkyMath.nightTint(0, 100), EPS);
        assertArrayEquals(new float[]{1, 1, 1}, BiomeSkyMath.nightTint(1, 0), EPS);
        float[] t = BiomeSkyMath.nightTint(1, 100);
        assertArrayEquals(BiomeSkyMath.DESERT_NIGHT_TINT, t, EPS);
        float luma = 0.2126f * t[0] + 0.7152f * t[1] + 0.0722f * t[2];
        assertTrue(luma < 0.8f && luma > 0.55f, "a little darker: " + luma);
        //deeper: blue gains on red and green (toward indigo-violet), red above green (violet, not teal)
        assertTrue(t[2] > t[0] && t[0] > t[1]);
        //half way at half the share; 200% stays non-negative; NaN share is none
        float[] h = BiomeSkyMath.nightTint(0.5f, 100);
        for (int i = 0; i < 3; i++) assertEquals((1 + t[i]) / 2, h[i], EPS);
        for (float v : BiomeSkyMath.nightTint(1, 500)) assertTrue(v >= 0);
        assertArrayEquals(new float[]{1, 1, 1}, BiomeSkyMath.nightTint(Float.NaN, 100), EPS);
    }

    @Test
    void starsAndCloudsFollowTheShare() {
        assertEquals(1, BiomeSkyMath.starBoost(0, 100), EPS);
        assertEquals(1 + BiomeSkyMath.DESERT_STAR_BOOST, BiomeSkyMath.starBoost(1, 100), EPS);
        assertTrue(BiomeSkyMath.starBoost(1, BiomeSkyMath.NIGHT_MAX) <= BiomeSkyMath.STAR_GATE_MAX);
        assertEquals(0, BiomeSkyMath.cloudClear(0, 100), EPS);
        assertEquals(1, BiomeSkyMath.cloudClear(1, 100), EPS);
        assertEquals(0.25f, BiomeSkyMath.cloudClear(0.5f, 50), EPS);
        assertEquals(1, BiomeSkyMath.cloudClear(1, 900), EPS);
        assertEquals(0, BiomeSkyMath.cloudClear(1, -5), EPS);
    }

    @Test
    void cloudColourKeepsAllWithoutClearingAndNoneWhenCleared() {
        float[] march = {0.6f, 0.3f, 0.8f, 500};
        float[] lit = {1, 0.9f, 0.8f, 0}, amb = {0.2f, 0.3f, 0.4f, 0};
        float[] kept = AtmosphereMath.cloudColour(new AtmosphereMath.CloudLight(lit, amb, new float[]{0, 1, 0, 0}), march);
        assertEquals(0.8f, kept[3], EPS);
        assertEquals(0.6f * 1 + 0.3f * 0.2f, kept[0], EPS);
        float[] half = AtmosphereMath.cloudColour(new AtmosphereMath.CloudLight(lit, amb, new float[]{0, 1, 0, 0.5f}), march);
        for (int i = 0; i < 4; i++) assertEquals(kept[i] * 0.5f, half[i], EPS);
        float[] gone = AtmosphereMath.cloudColour(new AtmosphereMath.CloudLight(lit, amb, new float[]{0, 1, 0, 1}), march);
        assertArrayEquals(new float[4], gone, EPS);
    }
}
