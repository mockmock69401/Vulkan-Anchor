package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-27: vanilla's vignette texture baked per pixel with the dither (VignetteDither, DitheredVignette). */
class VignetteDitherTest {
    private static int grey(int v, int alpha) {
        return alpha << 24 | v << 16 | v << 8 | v;
    }

    @Test
    void theEndsStayAndAFlatTextureKeepsItsValueOnAverage() {
        int[] flat = VignetteDither.bake(new int[]{grey(100, 255), grey(100, 255), grey(100, 255), grey(100, 255)}, 2, 2, 64, 64);
        double sum = 0;
        for (int p : flat) {
            int r = p & 255;
            assertTrue(Math.abs(r - 100) <= VignetteDither.AMPLITUDE, "within the noise's reach: " + r);
            assertEquals(r, (p >>> 8) & 255, "the channels share the noise (a grey stays grey)");
            assertEquals(255, p >>> 24, "the alpha is not dithered");
            sum += r;
        }
        assertEquals(100, sum / flat.length, 0.05);
        //No darkening (0) and full darkening (255) are not moved: the noise never reaches past either end
        for (int v : new int[]{0, 255}) {
            for (int p : VignetteDither.bake(new int[]{grey(v, 255)}, 1, 1, 32, 32)) assertEquals(v, p & 255);
        }
    }

    @Test
    void aGradientsLocalMeansFollowItBetweenThe8BitSteps() {
        //Two texels a step apart stretched over 1024 pixels: the filtered texture rises smoothly from 100 to 101 across the middle half,
        // where plain rounding gives a hard edge (off by up to half a step); the dithered local means follow the ramp
        int width = 1024, height = 64;
        int[] out = VignetteDither.bake(new int[]{grey(100, 255), grey(101, 255)}, 2, 1, width, height);
        double error = 0, plain = 0;
        int n = 0;
        for (int x = 288; x < 736; x += 8) {
            double mean = 0, expected = 0, rounded = 0;
            for (int dx = -8; dx < 8; dx++) {
                for (int y = 0; y < height; y++) mean += out[y * width + x + dx] & 255;
                double f = Math.max(0, Math.min(1, (x + dx + 0.5) * 2 / width - 0.5));
                expected += 100 + f;
                rounded += Math.round(100 + f);
            }
            mean /= 16.0 * height;
            error += Math.abs(mean - expected / 16);
            plain += Math.abs(rounded / 16 - expected / 16);
            n++;
        }
        assertTrue(plain / n > 0.2, "plain rounding steps: " + plain / n);
        assertTrue(error / n < 0.06, "the dithered local means follow the ramp: " + error / n);
    }

    @Test
    void theNoiseIsTheShadersInterleavedGradientNoise() {
        float x = 37, y = 911;
        double a = x * 0.06711056 + y * 0.00583715;
        double b = 52.9829189 * (a - Math.floor(a));
        assertEquals(b - Math.floor(b), VignetteDither.noise(x, y), 1e-4);
    }
}
