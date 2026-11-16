package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-16: no wet or drying ground where it does not rain (WetClimateMap; wetclimate.glsl's constants; the composite's gate). */
class WetClimateTest {
    private static final String GLSL = ShaderFiles.read("post/wetclimate.glsl");

    private static float glsl(String name) {
        Matcher m = Pattern.compile("const\s+(?:float|int)\s+" + name + "\s*=\s*([-0-9.]+)\s*;").matcher(GLSL);
        assertTrue(m.find(), name);
        return Float.parseFloat(m.group(1));
    }

    /** A world: a desert (no rain) for x < 0, snow above y 120, rain elsewhere; ground at y 64; chunks loaded within 200 blocks. */
    private static final WetClimateMap.Columns WORLD = new WetClimateMap.Columns() {
        @Override
        public int height(int x, int z) {
            return Math.abs(x) > 200 || Math.abs(z) > 200 ? WetClimateMap.NO_HEIGHT : (z >= 100 ? 130 : 64);
        }

        @Override
        public boolean rains(int x, int y, int z) {
            return x >= 0 && y < 120;
        }
    };

    private static WetClimateMap filled(int x, int z) {
        WetClimateMap map = new WetClimateMap();
        map.update(WORLD, x, z, WetClimateMap.SIZE * WetClimateMap.SIZE);
        return map;
    }

    @Test
    void theShaderMirrorsTheConstants() {
        assertEquals(WetClimateMap.SIZE, glsl("WET_CLIMATE_SIZE"));
        assertEquals(WetClimateMap.MASK, glsl("WET_CLIMATE_MASK"));
        assertEquals(WetClimateMap.SHIFT, glsl("WET_CLIMATE_SHIFT"));
        assertEquals(WetClimateMap.CELL, glsl("WET_CLIMATE_CELL"));
        assertEquals(WetClimateMap.WRAP, glsl("WET_CLIMATE_WRAP"));
        assertEquals(WetClimateMap.REACH, glsl("WET_CLIMATE_REACH"));
        assertEquals(1 << WetClimateMap.SHIFT, WetClimateMap.SIZE);
        assertEquals(4 * WetClimateMap.SIZE * WetClimateMap.CELL, WetClimateMap.WRAP, "the wrap is the tag's four map wraps");
        String copy = ShaderFiles.read("post/scene_copy.comp");
        assertTrue(copy.contains("layout(binding = " + WetClimateMap.BINDING + ") uniform usampler2D wetClimateMap;"));
        assertTrue(copy.contains("layout(location = " + WetClimateMap.SLOT + ") uniform vec4 wetClimate;"));
        assertTrue(copy.contains("#import <vulkanmod:visuals/post/wetclimate.glsl>"));
        //Both the wet ground and PBR's rain take the wetness where it rains on their own ground
        assertTrue(copy.contains("float wetness = pbrParams.x > 0.0 && !fromLod ? pbrParams.x*wetClimateAt(world) : 0.0;"));
        assertTrue(copy.contains("pbrWetAt(wetness, sky)"));
        assertTrue(copy.contains("wetGround = wetGroundAt(wetness, sky, wetParams.x);"));
        assertFalse(copy.contains("pbrWetAt(pbrParams.x"));
        assertFalse(copy.contains("wetGroundAt(pbrParams.x"));
    }

    @Test
    void theDesertAndTheSnowStayDry() {
        WetClimateMap map = filled(10, 10);
        float[] p = WetClimateMap.params(true, 10.5, 10.5, true);
        assertEquals(1f, WetClimateMap.at(p, map.texels(), 20, 0), "the plain east of x 0 gets wet");
        assertEquals(0f, WetClimateMap.at(p, map.texels(), -40, 0), "the desert west of it does not");
        assertEquals(0f, WetClimateMap.at(p, map.texels(), 20, 120), "nor the snowy heights (it snows there)");
        assertEquals(0f, WetClimateMap.at(p, map.texels(), 230, 0), "nor a cell whose chunk is not loaded");
        assertEquals(1f, WetClimateMap.at(p, map.texels(), 300, 0), "past the map: the rain at the camera");
        assertEquals(0f, WetClimateMap.at(WetClimateMap.params(true, 10.5, 10.5, false), map.texels(), 300, 0));
        assertTrue(map.anyRain());
        assertArrayEquals(new float[4], WetClimateMap.params(false, 10.5, 10.5, true));
        assertEquals(1f, WetClimateMap.at(new float[4], map.texels(), -40, 0), "no map: rain everywhere, as before");
    }

    @Test
    void aDesertWithNoRainAroundKeepsTheGroundDry() {
        WetClimateMap.Columns desert = new WetClimateMap.Columns() {
            public int height(int x, int z) {
                return 64;
            }

            public boolean rains(int x, int y, int z) {
                return false;
            }
        };
        WetClimateMap map = new WetClimateMap();
        map.update(desert, -5000, 3000, WetClimateMap.SIZE * WetClimateMap.SIZE);
        assertFalse(map.anyRain(), "the frame's wetness then does not rise at all");
    }

    @Test
    void farFromTheOriginAndAfterTheCameraMovedTheCellsStayRight() {
        //Far out and negative: the wrap and the tag
        for (int[] at : new int[][]{{-1_000_003, 777_777}, {123_456, -98_765}, {0, 0}}) {
            WetClimateMap.Columns half = new WetClimateMap.Columns() {
                public int height(int x, int z) {
                    return 64;
                }

                public boolean rains(int x, int y, int z) {
                    return x >= at[0];
                }
            };
            WetClimateMap map = new WetClimateMap();
            map.update(half, at[0], at[1], WetClimateMap.SIZE * WetClimateMap.SIZE);
            //The camera walks 300 blocks east: the cells behind it are replaced, those ahead looked up
            int x = at[0] + 300;
            map.update(half, x, at[1], WetClimateMap.SIZE * WetClimateMap.SIZE);
            float[] p = WetClimateMap.params(true, x + 0.5, at[1] + 0.5, true);
            assertEquals(1f, WetClimateMap.at(p, map.texels(), 100, 50));
            assertEquals(1f, WetClimateMap.at(p, map.texels(), -250, -50), "300 blocks west of the camera minus 250 still rains");
            //A cell left from before the walk (west of at[0]) is out of the map now; no stale cell answers for it
            p = WetClimateMap.params(true, at[0] + 0.5, at[1] + 0.5, true);
            assertEquals(0f, WetClimateMap.at(p, map.texels(), -100, 0));
        }
    }

    @Test
    void newCellsComeNearestFirstWithinTheBudget() {
        WetClimateMap map = new WetClimateMap();
        assertTrue(map.update(WORLD, 10, 10, 9));
        float[] p = WetClimateMap.params(true, 10.5, 10.5, true);
        assertEquals(1f, WetClimateMap.at(p, map.texels(), 4, 4), "the 3 x 3 cells around the camera first");
        assertEquals(0f, WetClimateMap.at(p, map.texels(), 40, 0), "the rest later");
        assertFalse(map.update(WORLD, 10, 10, 0));
    }

    @Test
    void theTextureHoldsTheTag() {
        assertEquals(WetClimateMap.KNOWN | WetClimateMap.RAINS | (9 << WetClimateMap.TAG_SHIFT), WetClimateMap.pack(128, 256, true));
        assertEquals(WetClimateMap.KNOWN | (15 << WetClimateMap.TAG_SHIFT), WetClimateMap.pack(-1, -1, false));
        assertEquals(-1, WetClimateMap.cellOf(-1));
        assertEquals(0, WetClimateMap.cellOf(3));
    }
}
