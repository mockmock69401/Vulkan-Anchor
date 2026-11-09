package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29: raindrops' rings on the water (rainripple.glsl) and rain and snow left unreflected (water.glsl's overdraw test). */
class RainRippleTest {
    private static final String GLSL = ShaderFiles.read("post/rainripple.glsl");
    private static final String COMP = ShaderFiles.read("post/reflection.comp");

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(GLSL);
        int found = 0;
        while (m.find()) {
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) RainRippleMath.class.getField(m.group(2)).get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertEquals(10, found);
        for (Field f : RainRippleMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != float.class) continue;
            assertTrue(Pattern.compile("const\\s+float\\s+" + f.getName() + "\\b").matcher(GLSL).find(), f.getName());
        }
        assertFalse(Pattern.compile("(?m)^\\s*(layout|uniform)\\b").matcher(GLSL).find(), "no uniforms or bindings");
        int water = COMP.indexOf("visuals/post/water.glsl>"), rain = COMP.indexOf("visuals/post/rainripple.glsl>");
        assertTrue(water > 0 && rain > water, "after water.glsl (its hash)");
        assertTrue(COMP.contains("layout(location = " + ReflectionUniforms.RAIN + ") uniform vec4 rainParams;"));
        double cells = RippleMath.RIPPLE_PERIOD / RainRippleMath.RAIN_RIPPLE_CELL;
        assertEquals(Math.rint(cells), cells, 1e-3, "the cell divides the ripple period");
    }

    /** The largest slope over a patch of water at time t. */
    private static float peak(float rain, float t, float footprint) {
        float max = 0;
        for (int i = 0; i < 80; i++) {
            for (int j = 0; j < 80; j++) {
                float[] s = RainRippleMath.slope(i * 0.05f, j * 0.05f, t, rain, footprint);
                max = Math.max(max, (float) Math.hypot(s[0], s[1]));
            }
        }
        return max;
    }

    @Test
    void ringsOnlyInTheRainAndFadingFarAway() {
        assertEquals(0f, peak(0, 3.3f, 0.01f), "no rain, no rings");
        float near = peak(1, 3.3f, 0.002f);
        assertTrue(near > 0.05f && near < 0.6f, "visible, not a storm sea: " + near);
        assertTrue(peak(1, 3.3f, 0.02f) < near * 0.5f, "a pixel wider than the crest smooths them out");
        assertEquals(0f, peak(1, 3.3f, 1f), "far away: none");
        assertEquals(RainRippleMath.RAIN_RIPPLE_SHINE, RainRippleMath.shine(new float[]{5, 0}), 1e-6f, "a crest shines at most this much");
        assertEquals(0f, RainRippleMath.shine(new float[]{0, 0}));
        //World anchored: the same water a ripple period away rings alike
        float[] a = RainRippleMath.slope(1.37f, 2.11f, 5f, 1, 0.01f);
        float[] b = RainRippleMath.slope(1.37f + RippleMath.RIPPLE_PERIOD, 2.11f, 5f, 1, 0.01f);
        assertEquals(a[0], b[0], 1e-3f);
        assertEquals(a[1], b[1], 1e-3f);
        //2026-11-10 (rebuilt from the references): a ring is a packet of several thin crests (the slope changes sign more than twice
        // along a radius through it), small (none past the radius and the envelope), and gone at the end of its cycle
        int signs = 0;
        float prev = 0;
        for (float r = 0.001f; r < 0.25f; r += 0.0005f) {
            float v = RainRippleMath.packet(r, 0.4f);
            if (v != 0 && prev != 0 && Math.signum(v) != Math.signum(prev)) signs++;
            if (Math.abs(v) > 1e-4f) prev = v;
        }
        assertTrue(signs >= 4, "crests in one ring: " + signs);
        assertEquals(0, RainRippleMath.packet(RainRippleMath.RAIN_RIPPLE_RADIUS + 3 * RainRippleMath.RAIN_RIPPLE_WIDTH, 0.99f), 1e-3);
        assertEquals(0, RainRippleMath.packet(0.05f, 1f), 1e-6);
        //Many drops: over a block of water in full rain, about 30 land a second
        int drops = 0;
        for (int layer = 0; layer < 2; layer++) {
            for (int i = 0; i < 40; i++) {
                for (int j = 0; j < 40; j++) {
                    if (RainRippleMath.drop(i, j, layer, 1, 0) != null) drops++;
                }
            }
        }
        float perBlockSecond = drops / (40 * 40 * RainRippleMath.RAIN_RIPPLE_CELL * RainRippleMath.RAIN_RIPPLE_CELL) / RainRippleMath.RAIN_RIPPLE_CYCLE;
        assertTrue(perBlockSecond > 20 && perBlockSecond < 50, "drops per block per second: " + perBlockSecond);
        //They move: another moment, another pattern
        float[] c = RainRippleMath.slope(1.37f, 2.11f, 5.4f, 1, 0.01f);
        assertTrue(Math.abs(a[0] - c[0]) + Math.abs(a[1] - c[1]) > 0 || Math.abs(a[0]) + Math.abs(a[1]) == 0);
    }

    @Test
    void whatIsDrawnOverTheWaterAfterwardsIsNotReflected() {
        float[] water = {0.2f, 0.4f, 0.6f};
        assertFalse(WaterMath.overdrawnAfterTranslucent(water, water));
        assertFalse(WaterMath.overdrawnAfterTranslucent(water, new float[]{0.2f + 1f / 255, 0.4f, 0.6f}), "8-bit rounding is not a particle");
        assertTrue(WaterMath.overdrawnAfterTranslucent(water, new float[]{0.6f, 0.65f, 0.7f}), "a raindrop over it");
        String copy = ShaderFiles.read("post/scene_copy.comp");
        assertTrue(copy.contains("bool overdrawn = overdrawnAfterTranslucent(texelFetch(colourAfterTranslucent, p, 0).rgb, colour.rgb);"));
    }
}
