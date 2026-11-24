package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-23 (the user, after Genshin Impact's night sky, looked at only: the anime clouds should feel like watercolour, not cumulus; the
 * thin streak clouds look more natural as near-invisible circles around the player, in the anime and realistic styles alike): the
 * watercolour push of the anime puffs' outlines (CloudMath.animeWarp) and the cirrus rings (cirrusRings).
 */
class WatercolourCloudTest {
    private static final byte[] MAP = CloudMath.withNoise(new byte[CloudMath.MAP * CloudMath.MAP * 4]);

    private static float constant(String text, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+float\\s+" + name + "\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
        assertTrue(m.find(), name + " in clouds.glsl");
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theShaderMatchesTheMirror() throws Exception {
        String clouds = ShaderFiles.read("post/clouds.glsl");
        assertEquals(CloudMath.CIRRUS_RING_SCALE, constant(clouds, "CIRRUS_RING_SCALE"));
        assertEquals(CloudMath.CIRRUS_RING_NEAR, constant(clouds, "CIRRUS_RING_NEAR"));
        assertEquals(CloudMath.CIRRUS_RING_FAR, constant(clouds, "CIRRUS_RING_FAR"));
        //both styles draw the rings; the noise scales are whole numbers, so the map's wrap never shows
        assertTrue(clouds.contains("float a = cirrusRings(q, fc)*REAL_CIRRUS_OPACITY"));
        assertTrue(clouds.contains("float a = cirrusRings(q, fc)*ANIME_CIRRUS_OPACITY"));
        for (float k : new float[]{CloudMath.ANIME_WARP_SCALE, CloudMath.ANIME_WARP_FINE_SCALE, CloudMath.ANIME_BLOTCH_SCALE}) {
            assertEquals(Math.round(k), k, 0f);
        }
        //faint: near invisible
        assertTrue(CloudMath.REAL_CIRRUS_OPACITY <= 0.2f && CloudMath.ANIME_CIRRUS_OPACITY <= 0.2f);
    }

    /** The streaks run along circles around the camera: the cover changes far less along a circle than across the rings. */
    @Test
    void theCirrusRingsCircleThePlayer() {
        float fc = CloudMath.REAL_CIRRUS_COVER;
        double along = 0, across = 0;
        int n = 0, covered = 0;
        for (int i = 0; i < 60; i++) {
            float r = 1200 + i * 97;
            for (int j = 0; j < 90; j++) {
                double a = j * 2 * Math.PI / 90;
                float x = (float) (r * Math.cos(a)), z = (float) (r * Math.sin(a));
                float v = CloudMath.cirrusRings(MAP, x, z, fc);
                //one step of 6 blocks along the circle, and one out across it
                double b = a + 6.0 / r;
                along += Math.abs(CloudMath.cirrusRings(MAP, (float) (r * Math.cos(b)), (float) (r * Math.sin(b)), fc) - v);
                across += Math.abs(CloudMath.cirrusRings(MAP, (float) ((r + 6) * Math.cos(a)), (float) ((r + 6) * Math.sin(a)), fc) - v);
                if (v > 0) covered++;
                n++;
            }
        }
        assertTrue(covered > n / 20, "some cirrus: " + covered + " of " + n);
        assertTrue(along * 2 < across, "along the circles " + along + ", across " + across);
        //none overhead (the angle's lattice would pinch there), and no seam where the angle wraps
        assertEquals(0, CloudMath.cirrusRings(MAP, 100, 50, fc));
        float r = 2500;
        float before = CloudMath.cirrusRings(MAP, -r, -0.01f, fc), after = CloudMath.cirrusRings(MAP, -r, 0.01f, fc);
        assertEquals(before, after, 0.02f, "across the angle's wrap");
    }

    /** The watercolour push turns the round puffs' outlines ragged: more edge per cloud than without it. */
    @Test
    void theWatercolourOutlinesAreRagged() {
        CloudMath.Realistic r = new CloudMath.Realistic(0, CloudMath.ANIME_THICKNESS, 8192, 0, 0, CloudMath.REAL_COVER);
        int size = 240;
        boolean[] plain = new boolean[size * size], warped = new boolean[size * size];
        float h = 30;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                float x = i * 6, z = j * 6;
                float[] p = CloudMath.animeShape(MAP, r, x, z, h);
                plain[i * size + j] = p[1] > p[0] && h > p[0] && h < p[1];
                float[] m = CloudMath.animeWarped(MAP, r, x, z);
                float[] w = CloudMath.animeShape(MAP, r, m[0], m[1], h);
                warped[i * size + j] = w[1] > w[0] && h > w[0] && h < w[1];
            }
        }
        double ragPlain = edgeShare(plain, size), ragWarped = edgeShare(warped, size);
        assertTrue(ragWarped > ragPlain * 1.2, "edge per cloud: plain " + ragPlain + ", watercolour " + ragWarped);
    }

    /** Cells on a cloud's edge (a neighbour clear) over the cells covered. */
    private static double edgeShare(boolean[] in, int size) {
        int covered = 0, edge = 0;
        for (int i = 1; i < size - 1; i++) {
            for (int j = 1; j < size - 1; j++) {
                if (!in[i * size + j]) continue;
                covered++;
                if (!in[(i + 1) * size + j] || !in[(i - 1) * size + j] || !in[i * size + j + 1] || !in[i * size + j - 1]) edge++;
            }
        }
        assertTrue(covered > 200, "clouds found: " + covered);
        return edge / (double) covered;
    }
}
