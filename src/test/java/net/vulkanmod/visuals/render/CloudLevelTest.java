package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ExperimentalFeatures;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-22 (the user: the clouds keep to one height and look flat from high up; spread them lower and at different heights, the range
 * on the Experimental page): the realistic and anime clouds' levels (clouds.glsl cloudLevelAt, CloudMath.levelAt, CloudLevelMath).
 */
class CloudLevelTest {
    private static final byte[] MAP = CloudMath.withNoise(new byte[CloudMath.MAP * CloudMath.MAP * 4]);

    private static float constant(String text, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(?:float|int)\\s+" + name + "\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
        assertTrue(m.find(), name + " in clouds.glsl");
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theShaderMatchesTheMirror() throws Exception {
        String clouds = ShaderFiles.read("post/clouds.glsl");
        assertEquals(CloudLevelMath.CLOUD_LEVEL_CELL, constant(clouds, "CLOUD_LEVEL_CELL"));
        assertEquals(CloudLevelMath.CLOUD_LEVEL_EDGE, constant(clouds, "CLOUD_LEVEL_EDGE"));
        assertEquals(CloudLevelMath.CLOUD_LEVEL_SEED, (int) constant(clouds, "CLOUD_LEVEL_SEED"));
        assertTrue(ShaderFiles.read("post/scene_copy.comp").contains("layout(location = " + CloudLevelMath.SLOT + ") uniform vec4 cloudLevel;"));
        assertTrue(ShaderFiles.read("post/reflection.comp").contains("layout(location = " + CloudLevelMath.REFLECTION_SLOT + ") uniform vec4 cloudLevel;"));
        //the realistic light samples share their sample's level; the anime puffs stand on the level at their centre
        assertTrue(clouds.contains("od += realDensityOn(p + lightDir*(0.5*(reach + prev)), 0, lv, keep)*(reach - prev);"));
        assertEquals(CloudLevelMath.CLOUD_LEVEL_JOIN, constant(clouds, "CLOUD_LEVEL_JOIN"));
        assertEquals(CloudLevelMath.CLOUD_LEVEL_SPLIT, constant(clouds, "CLOUD_LEVEL_SPLIT"));
        assertTrue(clouds.contains("lv = cloudLevelAt(p*cell);"));
    }

    @Test
    void theSettingsMakeTheSlot() {
        assertArrayEquals(new float[4], CloudLevelMath.params(96, 64, 0, 1), "spread 0: off, every cloud on the usual base");
        assertArrayEquals(new float[4], CloudLevelMath.params(0, 0, 100, 1), "no range: off");
        float[] p = CloudLevelMath.params(96, 64, 50, 1);
        assertEquals(-48, p[0], 1e-4f);
        assertEquals(32, p[1], 1e-4f);
        assertEquals(1, p[3]);
        float[] wide = CloudLevelMath.params(1000, 1000, 100, 1);
        assertEquals(-CloudLevelMath.RANGE_MAX, wide[0]);
        assertEquals(CloudLevelMath.RANGE_MAX, wide[1]);
        //the lattice's cell scales with the size and divides vanilla's 3072-block period (the offset wraps there)
        for (float size = 0.5f; size <= 2; size += 0.25f) {
            float cell = CloudLevelMath.params(96, 64, 100, size)[2];
            assertEquals(CloudLevelMath.CLOUD_LEVEL_CELL * size, cell, CloudLevelMath.CLOUD_LEVEL_CELL * size * 0.1f, "size " + size);
            double cells = CloudMath.PERIOD / cell;
            assertEquals(Math.round(cells), cells, 1e-3, "whole cells at size " + size);
        }
        //the config's defaults turn them on while the realistic or anime clouds are drawn, never otherwise
        VisualsConfig c = new VisualsConfig();
        c.enabled = true;
        assertEquals(1, CloudLevelMath.params(c, true)[3]);
        assertArrayEquals(new float[4], CloudLevelMath.params(c, false));
        c.enabled = false;
        assertArrayEquals(new float[4], CloudLevelMath.params(c, true));
        //Experimental > Clouds: the three settings, the heights in blocks
        long n = ExperimentalFeatures.ENTRIES.stream().filter(e -> e.block().equals(ExperimentalFeatures.CLOUDS)).count();
        assertEquals(3, n);
    }

    private static CloudMath.Realistic layer(float base, float thickness, float[] level) {
        return new CloudMath.Realistic(base, thickness, 4096, 0, 0, CloudMath.REAL_COVER).withLevel(level);
    }

    /** The level field stays in the range, uses all of it, lies flat over most of each cell and changes smoothly across cell edges. */
    @Test
    void theLevelsFillTheRangeOnFlatCells() {
        float[] level = CloudLevelMath.params(96, 64, 100, 1);
        CloudMath.Realistic r = layer(100, CloudMath.ANIME_THICKNESS, level);
        float cell = level[2], min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        int flat = 0, all = 0;
        for (int i = 0; i < 40; i++) {
            for (int j = 0; j < 40; j++) {
                float x = i * cell * 0.25f + 3, z = j * cell * 0.25f + 7;
                float v = CloudMath.levelAt(r, x, z);
                assertTrue(v >= level[0] - 1e-3f && v <= level[1] + 1e-3f, "in the range: " + v);
                min = Math.min(min, v);
                max = Math.max(max, v);
                //a step of 2 blocks moves it at most range x 2 / (2 x EDGE x cell) x 1.5 (the smoothstep's steepest slope)
                float step = Math.abs(CloudMath.levelAt(r, x + 2, z) - v);
                assertTrue(step <= (level[1] - level[0]) * 2 / (2 * CloudLevelMath.CLOUD_LEVEL_EDGE * cell) * 1.5f + 1e-3f, "smooth: " + step);
                if (step < 1e-4f) flat++;
                all++;
            }
        }
        assertTrue(min < level[0] + 25 && max > level[1] - 25, "uses the range: " + min + " .. " + max);
        assertTrue(flat > all * 0.3f, "flat over most of a cell: " + flat + " of " + all);
        //off: every level 0
        assertEquals(0, CloudMath.levelAt(layer(100, 140, new float[4]), 123, 456));
    }

    /** With the levels the anime clouds' bases spread over many heights; without them they all stand on the usual base. */
    @Test
    void theAnimeCloudsStandAtManyHeights() {
        float[] level = CloudLevelMath.params(96, 64, 100, 1);
        CloudMath.Realistic flat = layer(0, CloudMath.ANIME_THICKNESS, new float[4]), levels = layer(0, CloudMath.ANIME_THICKNESS, level);
        float[] bottomsFlat = bottoms(flat), bottoms = bottoms(levels);
        assertTrue(bottomsFlat.length > 50 && bottoms.length > 50, "clouds found: " + bottomsFlat.length + ", " + bottoms.length);
        assertTrue(bottomsFlat[bottomsFlat.length - 1] < 40 && bottomsFlat[0] >= 0, "without levels on the base: " + bottomsFlat[0] + " .. " + bottomsFlat[bottomsFlat.length - 1]);
        assertTrue(bottoms[0] < -60, "some low: " + bottoms[0]);
        assertTrue(bottoms[bottoms.length - 1] > 40, "some high: " + bottoms[bottoms.length - 1]);
        //Two puffs on different levels side by side stay two clouds: no column is much taller than one cloud can be (where two puffs meet in
        // a cell's blend the levels differ a little; one interval per column would join a low and a high cloud into a wall of range + one)
        float tallest = 0, cell = CloudMath.animeCell(CloudMath.ANIME_PUFF_CELL, 1);
        float one = Math.min(((CloudMath.ANIME_PUFF_HIGH + CloudMath.ANIME_PUFF_TALL) * CloudMath.ANIME_PUFF_MAX + 0.75f * CloudMath.ANIME_PUFF_BLEND) * cell, CloudMath.ANIME_THICKNESS);
        for (int i = 0; i < 120; i++) {
            for (int j = 0; j < 120; j++) {
                for (float h = level[0]; h < level[1] + CloudMath.ANIME_THICKNESS; h += 8) {
                    float[] c = CloudMath.animeShape(MAP, levels, i * 13.7f, j * 11.3f, h);
                    if (c[1] > c[0] && h >= c[0] && h <= c[1]) tallest = Math.max(tallest, c[1] - c[0]);
                }
            }
        }
        assertTrue(tallest <= one + 0.25f * (level[1] - level[0]), "tallest column " + tallest + ", one cloud " + one);
    }

    /** The bottoms (sorted) of the columns on a grid where a cloud stands. */
    private static float[] bottoms(CloudMath.Realistic r) {
        float[] out = new float[120 * 120];
        int n = 0;
        for (int i = 0; i < 120; i++) {
            for (int j = 0; j < 120; j++) {
                float[] c = CloudMath.animeShape(MAP, r, i * 13.7f, j * 11.3f);
                if (c[1] > c[0]) out[n++] = c[0];
            }
        }
        out = Arrays.copyOf(out, n);
        Arrays.sort(out);
        return out;
    }

    /**
     * 2026-11-24 (the user: "some of the realistic clouds are drawn diagonally or vertically"): near a border between two far-apart levels
     * a cloud stands flat on one of them, never on a blend leaning between them, and the borders keep most of the clouds.
     */
    @Test
    void theRealisticCloudsDoNotLeanAcrossABorder() {
        float[] level = CloudLevelMath.params(CloudLevelMath.LOW_DEFAULT, CloudLevelMath.HIGH_DEFAULT, 100, 1);
        CloudMath.Realistic levels = layer(200, CloudMath.REAL_THICKNESS, level), flat = layer(200, CloudMath.REAL_THICKNESS, new float[4]);
        int covered = 0, coveredFlat = 0, leaning = 0;
        for (int i = 0; i < 200; i++) {
            for (int j = 0; j < 200; j++) {
                float x = i * 11.3f, z = j * 10.7f;
                float[] q = CloudMath.levelSquare(levels, x, z); //the layer's offset is 0
                boolean hit = false, hitFlat = false;
                for (float y = 200 + level[0]; y < 200 + CloudMath.REAL_THICKNESS + level[1]; y += 12) {
                    float[] pick = CloudMath.realPick(MAP, levels, x, y, z, 1);
                    if (pick[0] > 0) {
                        hit = true;
                        float near = Float.MAX_VALUE;
                        for (int k = 0; k < 4; k++) {
                            if (q[4 + k] > 0) near = Math.min(near, Math.abs(pick[1] - q[k]));
                        }
                        if (near > CloudLevelMath.CLOUD_LEVEL_SPLIT) leaning++;
                    }
                    if (y > 200 && y < 200 + CloudMath.REAL_THICKNESS && CloudMath.realDensity(MAP, flat, x, y, z, 1) > 0) hitFlat = true;
                }
                if (hit) covered++;
                if (hitFlat) coveredFlat++;
            }
        }
        assertEquals(0, leaning, "clouds on a level far from every corner's (leaning across a border)");
        assertTrue(coveredFlat > 2000, "clouds without levels: " + coveredFlat);
        assertTrue(covered > coveredFlat * 0.8, "the borders keep most clouds: " + covered + " of " + coveredFlat);
    }

    /** The realistic cumulus reach below the usual base with the levels, and not without them; the march's span takes the range in. */
    @Test
    void theRealisticCloudsGoLower() {
        float[] level = CloudLevelMath.params(96, 64, 100, 1);
        CloudMath.Realistic flat = layer(200, CloudMath.REAL_THICKNESS, new float[4]), levels = layer(200, CloudMath.REAL_THICKNESS, level);
        int below = 0, belowFlat = 0;
        for (int i = 0; i < 160; i++) {
            for (int j = 0; j < 160; j++) {
                float x = i * 9.1f, z = j * 8.3f;
                for (float y = 200 - 90; y < 200; y += 10) {
                    if (CloudMath.realDensity(MAP, levels, x, y, z, 1) > 0) below++;
                    if (CloudMath.realDensity(MAP, flat, x, y, z, 1) > 0) belowFlat++;
                }
            }
        }
        assertEquals(0, belowFlat, "without levels nothing under the base");
        assertTrue(below > 100, "with levels some under it: " + below);
        //looking straight up from under the lowest level: the span starts at the lowest level's base and ends at the highest's top
        float[] span = CloudMath.realInterval(levels, new float[]{0, 0, 0}, new float[]{0, 1, 0}, 1e5f);
        assertEquals(200 + level[0], span[0], 1e-2f);
        assertEquals(200 + CloudMath.REAL_THICKNESS + level[1], span[1], 1e-2f);
    }
}
