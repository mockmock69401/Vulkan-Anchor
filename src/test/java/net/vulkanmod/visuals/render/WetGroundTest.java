package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.config.VisualsPresets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Session 20: rain-wet ground (wetground.glsl, WetGroundMath). */
class WetGroundTest {
    private static final Path POST = Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post");

    private static float constant(String s, String name) {
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+" + name + "\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(s);
        assertTrue(m.find(), name);
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theShaderMirrorsTheConstants() throws IOException {
        String s = Files.readString(POST.resolve("wetground.glsl"));
        assertEquals(WetGroundMath.MARK_BASE, constant(s, "WET_MARK_BASE"));
        assertEquals(WetGroundMath.MARK_TOP, constant(s, "WET_MARK_TOP"));
        assertEquals(WetGroundMath.MARK_BOTTOM, constant(s, "WET_MARK_BOTTOM"));
        assertEquals(WetGroundMath.SHARE_MIN, constant(s, "WET_SHARE_MIN"));
        assertEquals(WetGroundMath.SKY_LOW, constant(s, "WET_SKY_LOW"));
        assertEquals(WetGroundMath.SKY_HIGH, constant(s, "WET_SKY_HIGH"));
        assertEquals(WetGroundMath.DARKEN, constant(s, "WET_DARKEN"));
        assertEquals(WetGroundMath.PUDDLE_DARKEN, constant(s, "WET_PUDDLE_DARKEN"));
        assertEquals(WetGroundMath.SATURATION, constant(s, "WET_SATURATION"));
        assertEquals(WetGroundMath.FILM, constant(s, "WET_FILM"));
        assertEquals(WetGroundMath.SIDE_FILM, constant(s, "WET_SIDE_FILM"));
        assertEquals(WetGroundMath.PUDDLE_CELL, constant(s, "WET_PUDDLE_CELL"));
        assertEquals(WetGroundMath.PUDDLE_PERIOD, constant(s, "WET_PUDDLE_PERIOD"));
        assertEquals(WetGroundMath.PUDDLE_COVER, constant(s, "WET_PUDDLE_COVER"));
        assertEquals(WetGroundMath.PUDDLE_EDGE, constant(s, "WET_PUDDLE_EDGE"));
        assertEquals(WetGroundMath.PUDDLE_UP, constant(s, "WET_PUDDLE_UP"));
        assertEquals(WetGroundMath.PUDDLE_DETAIL, constant(s, "WET_PUDDLE_DETAIL"));
        assertEquals(WetGroundMath.MARCH_SHARE, constant(s, "WET_MARCH_SHARE"));
        assertEquals(WetGroundMath.REFLECT_MIN, constant(s, "WET_REFLECT_MIN"));
        assertEquals(WetGroundMath.DEBUG_WET_RED, constant(s, "DEBUG_WET_RED"));
        assertEquals(WetGroundMath.DEBUG_WET_GREEN, constant(s, "DEBUG_WET_GREEN"));
        assertEquals(WetGroundMath.DEBUG_WET_BLUE, constant(s, "DEBUG_WET_BLUE"));
        assertTrue(s.contains("uint h = uint(m.x)*73856093u ^ uint(m.y)*19349663u;"));
        assertTrue(s.contains("h = (h ^ (h >> 13u))*1274126177u;"));
        String copy = Files.readString(POST.resolve("scene_copy.comp"));
        assertTrue(copy.contains("layout(location = " + WetGroundMath.SLOT + ") uniform vec4 wetParams;"));
        assertTrue(copy.contains("#import <vulkanmod:visuals/post/wetground.glsl>"));
        String reflection = Files.readString(POST.resolve("reflection.comp"));
        assertTrue(reflection.contains("#import <vulkanmod:visuals/post/wetground.glsl>"));
        //the wet marks are read before blockreflect.glsl's classes, which would take them for metal
        assertTrue(reflection.indexOf("if (isWetMark(mark)) {") < reflection.indexOf("if (blockReflectClass(mark) > 0) {"));
    }

    @Test
    void theMarksLieBetweenTheMetalsAndPbrs() {
        assertTrue(WetGroundMath.MARK_TOP < -4.0f, "below blockreflect.glsl's METAL_MARK");
        assertTrue(WetGroundMath.MARK_BOTTOM > -7.5f, "above pbr.glsl's PBR_MARK_LIMIT");
        for (float share = 0.01f; share <= 1f; share += 0.01f) {
            float mark = WetGroundMath.mark(share);
            assertTrue(WetGroundMath.isWetMark(mark));
            //half floats keep 1/256 between 4 and 8
            float half = Math.round(mark * 256f) / 256f;
            assertEquals(share, WetGroundMath.shareOf(half), 1f / 256f);
        }
        assertEquals(0, WetGroundMath.mark(0.001f));
        assertFalse(WetGroundMath.isWetMark(-4f));
        assertFalse(WetGroundMath.isWetMark(-8f));
        assertFalse(WetGroundMath.isWetMark(-2f));
    }

    @Test
    void wetUnderTheOpenSkyOnly() {
        assertEquals(1, WetGroundMath.wetAt(1, 15, 1), 1e-6);
        assertEquals(0, WetGroundMath.wetAt(1, 12, 1), 1e-6);
        assertEquals(0, WetGroundMath.wetAt(0, 15, 1), 1e-6);
        assertEquals(1, WetGroundMath.wetAt(1, 15, 2), 1e-6);
        assertEquals(0.5f, WetGroundMath.wetAt(0.5f, 15, 1), 1e-6);
    }

    @Test
    void puddlesSpreadWithTheRainAndShrinkAsItDries() {
        double prev = -1;
        for (float wet : new float[]{0.1f, 0.4f, 0.7f, 1f}) {
            double cover = 0;
            int n = 0;
            for (int i = 0; i < 240; i++) {
                for (int j = 0; j < 240; j++) {
                    cover += WetGroundMath.puddle(i + 0.5f, j * 0.97f + 0.25f, 1, wet, 1);
                    n++;
                }
            }
            cover /= n;
            assertTrue(cover >= prev, "wet " + wet + " cover " + cover);
            prev = cover;
            if (wet == 1f) assertTrue(cover > 0.08 && cover < 0.45, "cover at full rain " + cover);
        }
        //none on an upright face, none while dry
        assertEquals(0, WetGroundMath.puddle(3.3f, 7.1f, 0, 1, 1));
        assertEquals(0, WetGroundMath.puddle(3.3f, 7.1f, 1, 0, 1));
    }

    @Test
    void theFieldRepeatsOverThePeriod() {
        float p = WetGroundMath.PUDDLE_PERIOD;
        for (float x = 0.3f; x < 20; x += 1.7f) {
            float a = WetGroundMath.puddleField(x, 2 * x + 0.1f);
            assertEquals(a, WetGroundMath.puddleField(x + p, 2 * x + 0.1f), 1e-3);
            assertEquals(a, WetGroundMath.puddleField(x, 2 * x + 0.1f - p), 1e-3);
            assertTrue(a >= 0 && a <= 1);
        }
        assertEquals(17.5, WetGroundMath.wrap(-222.5), 1e-9);
    }

    @Test
    void darkerAndMoreMirroredUnderAPuddle() {
        assertEquals(1, WetGroundMath.darkening(0, 1), 1e-6);
        assertTrue(WetGroundMath.darkening(1, 1) < WetGroundMath.darkening(1, 0));
        assertEquals(1, WetGroundMath.share(1, 1, 1), 1e-6);
        assertEquals(WetGroundMath.FILM, WetGroundMath.share(1, 0, 1), 1e-6);
        assertEquals(WetGroundMath.FILM * WetGroundMath.SIDE_FILM, WetGroundMath.share(1, 0, 0), 1e-6);
        assertTrue(WetGroundMath.FILM < WetGroundMath.MARCH_SHARE, "a film mirrors the sky alone");
    }

    @Test
    void theSetting() {
        assertArrayEquals(new float[4], WetGroundMath.params(0, 5, 1));
        float[] p = WetGroundMath.params(150, -1, 241);
        assertEquals(1.5f, p[0], 1e-6);
        assertEquals(239, p[2], 1e-4);
        assertEquals(1, p[3], 1e-4);
        assertEquals(100, new VisualsConfig().wetGround);
        assertEquals(VisualsOptionLayout.Kind.PERCENT, VisualsOptionLayout.item("wet_ground").kind());
        assertEquals(0, VisualsPresets.value("wet_ground", VisualsPresets.LOW));
        assertEquals(50, VisualsPresets.value("wet_ground", VisualsPresets.MEDIUM));
        assertEquals(100, VisualsPresets.value("wet_ground", VisualsPresets.HIGH));
    }
}
