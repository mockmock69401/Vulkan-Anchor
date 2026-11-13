package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.config.VisualsPresets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Session 20: Caustics (caustics.glsl, CausticMath). */
class CausticTest {
    private static final Path GLSL = Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/caustics.glsl");
    private static final Path COPY = Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/scene_copy.comp");

    private static float constant(String s, String name) {
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+" + name + "\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(s);
        assertTrue(m.find(), name);
        return Float.parseFloat(m.group(1));
    }

    private static String vec(float x, float z) {
        return String.format(Locale.ROOT, "vec2(%.1f, %.1f)", x, z);
    }

    @Test
    void theShaderMirrorsTheConstants() throws IOException {
        String s = Files.readString(GLSL);
        assertEquals(CausticMath.WAVES, constant(s, "CAUSTIC_WAVES"));
        assertEquals(CausticMath.PERIOD, constant(s, "CAUSTIC_PERIOD"));
        assertEquals(CausticMath.TIME_PERIOD, constant(s, "CAUSTIC_TIME_PERIOD"));
        assertEquals(CausticMath.ETA, constant(s, "CAUSTIC_ETA"));
        assertEquals(CausticMath.SOFT, constant(s, "CAUSTIC_SOFT"));
        assertEquals(CausticMath.MAX, constant(s, "CAUSTIC_MAX"));
        assertEquals(CausticMath.DEPTH_IN, constant(s, "CAUSTIC_DEPTH_IN"));
        assertEquals(CausticMath.DEPTH_MAX, constant(s, "CAUSTIC_DEPTH_MAX"));
        assertEquals(CausticMath.FADE_NONE, constant(s, "CAUSTIC_FADE_NONE"));
        assertEquals(CausticMath.FADE_FULL, constant(s, "CAUSTIC_FADE_FULL"));
        assertEquals(CausticMath.SHORTEST, constant(s, "CAUSTIC_SHORTEST"));
        assertEquals(CausticMath.WATER_IOR, constant(s, "CAUSTIC_WATER_IOR"));
        assertEquals(CausticMath.BEHIND_MAX, constant(s, "CAUSTIC_BEHIND_MAX"));
        assertEquals(CausticMath.MEAN_COUNT, constant(s, "CAUSTIC_MEAN_COUNT"));
        assertEquals(CausticMath.MEAN_STEP, constant(s, "CAUSTIC_MEAN_STEP"));
        StringBuilder mean = new StringBuilder();
        for (float v : CausticMath.MEAN) mean.append(mean.length() > 0 ? ", " : "").append(v == 1f ? "1.0" : Float.toString(v));
        assertTrue(s.contains("float[CAUSTIC_MEAN_COUNT](" + mean + ")"), mean.toString());
        StringBuilder k = new StringBuilder();
        for (float[] w : CausticMath.K) k.append(k.length() > 0 ? ", " : "").append(vec(w[0], w[1]));
        assertTrue(s.replaceAll("\\s+", " ").contains("vec2[CAUSTIC_WAVES](" + k + ")"), k.toString());
        StringBuilder c = new StringBuilder(), a = new StringBuilder();
        for (int i = 0; i < CausticMath.WAVES; i++) {
            c.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.1f", CausticMath.CYCLES[i]));
            a.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.2f", CausticMath.SLOPE[i]));
        }
        assertTrue(s.contains("float[CAUSTIC_WAVES](" + c + ")"), c.toString());
        assertTrue(s.contains("float[CAUSTIC_WAVES](" + a + ")"), a.toString());
        String copy = Files.readString(COPY);
        assertTrue(copy.contains("layout(location = " + CausticMath.SLOT + ") uniform vec4 causticParams;"));
        assertTrue(copy.contains("#import <vulkanmod:visuals/post/caustics.glsl>"));
        assertTrue(copy.contains("if (causticParams.x > 0.0 && sunPart > 0.0 && !fromLod) {"));
    }

    @Test
    void theWavesRepeatOverThePeriods() {
        //whole numbers of cycles per period: the camera's and the clock's wraps never show a seam
        for (float[] k : CausticMath.K) {
            assertEquals(Math.rint(k[0]), k[0]);
            assertEquals(Math.rint(k[1]), k[1]);
        }
        for (float c : CausticMath.CYCLES) assertEquals(Math.rint(c), c);
        float a = CausticMath.gain(3.3f, 7.1f, 4, 10);
        assertEquals(a, CausticMath.gain(3.3f + CausticMath.PERIOD, 7.1f, 4, 10), 2e-3);
        assertEquals(a, CausticMath.gain(3.3f, 7.1f - CausticMath.PERIOD, 4, 10), 2e-3);
        assertEquals(a, CausticMath.gain(3.3f, 7.1f, 4, 10 + CausticMath.TIME_PERIOD), 2e-3);
    }

    @Test
    void theFloorGetsAWebOfLightAroundTheSunsOwn() {
        //at the surface nothing is focused yet
        assertEquals(1, CausticMath.gain(12.5f, 3.25f, 0, 5), 1e-5);
        //a few blocks down: bright curves and dimmer cells, the sun's own light on average
        double sum = 0, max = 0, min = 10;
        int n = 0;
        for (int i = 0; i < 200; i++) {
            for (int j = 0; j < 200; j++) {
                float g = CausticMath.gain(i * 0.2f, j * 0.2f, 5, 7);
                sum += g;
                max = Math.max(max, g);
                min = Math.min(min, g);
                n++;
            }
        }
        double mean = sum / n;
        assertTrue(mean > 0.88 && mean < 1.12, "mean " + mean);
        assertTrue(max > 2.0, "max " + max);
        assertTrue(min < 0.4, "min " + min);
        assertTrue(max <= CausticMath.MAX * (1 + CausticMath.SOFT) / CausticMath.mean(5) + 1e-4);
        assertEquals(1, CausticMath.mean(0), 1e-6);
        assertEquals(CausticMath.MEAN[6], CausticMath.mean(40), 1e-6);
    }

    @Test
    void theSetting() {
        assertArrayEquals(new float[4], CausticMath.params(0, 5, 1, 2));
        float[] p = CausticMath.params(150, 130, -1, 241);
        assertEquals(1.5f, p[0], 1e-6);
        assertEquals(10, p[1], 1e-4);
        assertEquals(239, p[2], 1e-4);
        assertEquals(1, p[3], 1e-4);
        assertEquals(100, new VisualsConfig().caustics);
        assertEquals(VisualsOptionLayout.Kind.PERCENT, VisualsOptionLayout.item("caustics").kind());
        assertFalse(VisualsPresets.VALUES.containsKey("caustics"), "a look, not a cost level");
    }
}
