package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-21: the wind's vertex marks, its amplitudes and clock, and terrain.vsh's side of the contract. */
class FoliageWindTest {
    private static String terrainVsh() throws IOException {
        try (InputStream in = FoliageWindTest.class.getResourceAsStream("/assets/vulkanmod/shaders/basic/terrain/terrain.vsh")) {
            assertNotNull(in, "terrain.vsh");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int code(float alpha) {
        return (int) (alpha * 255);
    }

    @Test
    void plantVerticesAreMarkedByTheirHeightInThePlant() {
        //A lower plant: base still, the rest one block up
        assertEquals(FoliageMarker.ALPHA, FoliageMarker.swayAlpha(FoliageMarker.ALPHA, FoliageMarker.PLANT, 0f));
        assertEquals(FoliageMarker.SWAY_ALPHA, FoliageMarker.swayAlpha(FoliageMarker.ALPHA, FoliageMarker.PLANT, 1f));
        assertEquals(FoliageMarker.SWAY_ALPHA, FoliageMarker.swayAlpha(FoliageMarker.ALPHA, FoliageMarker.PLANT, 0.9375f));
        //A double plant's upper half starts where the lower one ends (the joint moves alike) and its top is two up
        assertEquals(FoliageMarker.SWAY_ALPHA, FoliageMarker.swayAlpha(FoliageMarker.ALPHA, FoliageMarker.PLANT_UPPER, 0f));
        assertEquals(FoliageMarker.SWAY_HIGH_ALPHA, FoliageMarker.swayAlpha(FoliageMarker.ALPHA, FoliageMarker.PLANT_UPPER, 1f));
        //Leaves: every full-alpha vertex; light sources and still blocks keep their mark
        assertEquals(FoliageMarker.LEAF_ALPHA, FoliageMarker.swayAlpha(1f, FoliageMarker.LEAVES, 0.5f));
        assertEquals(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.swayAlpha(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.PLANT, 1f));
        assertEquals(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.swayAlpha(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.LEAVES, 1f));
        assertEquals(1f, FoliageMarker.swayAlpha(1f, FoliageMarker.STILL, 1f));
        assertEquals(FoliageMarker.ALPHA, FoliageMarker.swayAlpha(FoliageMarker.ALPHA, FoliageMarker.STILL, 1f));
        //A shaded quad of a plant block (a flower pot's rim) does not sway
        assertEquals(1f, FoliageMarker.swayAlpha(1f, FoliageMarker.PLANT, 1f));
    }

    @Test
    void theShaderDecodesTheMarksAndHandsOnThePlantMark() throws IOException {
        String vsh = terrainVsh();
        int[][] pairs = {{code(FoliageMarker.ALPHA), 0}, {code(FoliageMarker.SWAY_ALPHA), 0}, {code(FoliageMarker.SWAY_HIGH_ALPHA), 0}, {code(FoliageMarker.LEAF_ALPHA), 0}};
        String[] names = {"WIND_PLANT_BASE", "WIND_PLANT_ONE", "WIND_PLANT_TWO", "WIND_LEAF"};
        for (int i = 0; i < names.length; i++) {
            Matcher m = Pattern.compile("const int " + names[i] + " = (\\d+);").matcher(vsh);
            assertTrue(m.find(), names[i]);
            assertEquals(pairs[i][0], Integer.parseInt(m.group(1)), names[i]);
        }
        assertTrue(vsh.contains("const float WIND_PLANT_ALPHA = 254.25 / 255.0;"));
        assertEquals(254.25f / 255f, FoliageMarker.ALPHA);
        assertTrue(vsh.contains("const float WIND_K = 6.2831853 / " + (int) FoliageWind.PERIOD + ".0;"));
        //The marks the post-processing reads (plant 254, light source 253) stay out of the wind's range
        assertTrue(code(FoliageMarker.EMISSIVE_ALPHA) > code(FoliageMarker.SWAY_ALPHA));
        //Every wave fits the period a whole number of times along x and z
        Matcher w = Pattern.compile("const vec2 WIND_(MAIN|CROSS|GUST|FLUTTER) = vec2\\((-?[0-9.]+), (-?[0-9.]+)\\);").matcher(vsh);
        int waves = 0;
        while (w.find()) {
            for (int g = 2; g <= 3; g++) {
                double v = Double.parseDouble(w.group(g));
                assertEquals(Math.rint(v), v, 0, w.group(1));
            }
            waves++;
        }
        assertEquals(4, waves);
        //The wind blows along the ripples' main flow
        float fx = RippleMath.FLOW[0][0], fz = RippleMath.FLOW[0][1];
        float len = (float) Math.hypot(fx, fz);
        assertTrue(vsh.contains(String.format("const vec2 WIND_DIR = vec2(%.6f, %.6f);", fx / len, fz / len)), "WIND_DIR");
    }

    @Test
    void theSwayFadesOutWithDistanceAsTheShaderDoes() throws IOException {
        String vsh = terrainVsh();
        assertTrue(vsh.contains(String.format("const vec2 WIND_LEAF_FADE = vec2(%.1f, %.1f);", FoliageWind.LEAF_FADE[0], FoliageWind.LEAF_FADE[1])), "leaf fade");
        assertTrue(vsh.contains(String.format("const vec2 WIND_PLANT_FADE = vec2(%.1f, %.1f);", FoliageWind.PLANT_FADE[0], FoliageWind.PLANT_FADE[1])), "plant fade");
        assertTrue(vsh.contains("WindParams.w * near") && vsh.contains("WindParams.z * plant * near"), "both sways are scaled by the fade");
        assertEquals(1f, FoliageWind.fade(0, FoliageWind.LEAF_FADE));
        assertEquals(1f, FoliageWind.fade(FoliageWind.LEAF_FADE[0], FoliageWind.LEAF_FADE));
        assertEquals(0.5f, FoliageWind.fade((FoliageWind.LEAF_FADE[0] + FoliageWind.LEAF_FADE[1]) / 2, FoliageWind.LEAF_FADE), 1e-6f);
        assertEquals(0f, FoliageWind.fade(FoliageWind.LEAF_FADE[1], FoliageWind.LEAF_FADE));
        assertEquals(0f, FoliageWind.fade(500, FoliageWind.PLANT_FADE));
        assertTrue(FoliageWind.PLANT_FADE[1] > FoliageWind.LEAF_FADE[1], "plants sway further and fade later");
    }

    @Test
    void amplitudesFollowTheSettingAndTheRain() {
        assertArrayEquals(new float[]{FoliageWind.PLANT_AMPLITUDE, FoliageWind.LEAF_AMPLITUDE}, FoliageWind.amplitudes(1f, 0f, true), 1e-6f);
        assertArrayEquals(new float[]{0, 0}, FoliageWind.amplitudes(1f, 1f, false));
        assertArrayEquals(new float[]{0, 0}, FoliageWind.amplitudes(0f, 1f, true));
        assertArrayEquals(new float[]{0, 0}, FoliageWind.amplitudes(Float.NaN, 0f, true));
        float rain = FoliageWind.amplitudes(1f, 1f, true)[0];
        assertEquals(FoliageWind.PLANT_AMPLITUDE * (1 + FoliageWind.RAIN_GAIN), rain, 1e-6f);
        assertEquals(rain, FoliageWind.amplitudes(1f, 5f, true)[0], 1e-6f);
    }

    @Test
    void phasesWrapIntoOneTurn() {
        assertEquals(0, FoliageWind.wrapAngle(0), 0);
        assertEquals(1, FoliageWind.wrapAngle(1 + 2 * Math.PI), 1e-12);
        assertEquals(2 * Math.PI - 1, FoliageWind.wrapAngle(-1), 1e-12);
        assertEquals(0, FoliageWind.wrapAngle(Double.NaN), 0);
    }

    @Test
    void theSettingIsOnTheWaterPage() {
        VisualsConfig c = new VisualsConfig();
        assertEquals(100, c.foliageWind);
        VisualsOptionLayout.Item item = null;
        for (VisualsOptionLayout.Page p : VisualsOptionLayout.PAGES) {
            for (VisualsOptionLayout.Block b : p.blocks()) {
                for (VisualsOptionLayout.Item i : b.items()) {
                    if (i.key().equals("foliage_wind")) item = i;
                }
            }
        }
        assertNotNull(item);
        VisualsOptionLayout.set(c, item, 40);
        assertEquals(40, VisualsOptionLayout.get(c, item));
    }
}
