package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Session 20: hanging lanterns, chains and plants swing (FoliageMarker.HANGING, terrain.vsh hangOffset, FoliageWind.hang). */
class HangingSwayTest {
    private static final Path VSH = Path.of("src/main/resources/assets/vulkanmod/shaders/basic/terrain/terrain.vsh");

    /** As terrain.vsh reads a vertex alpha: int(a * 255 + 0.5). */
    private static int mark(float alpha) {
        return (int) (alpha * 255 + 0.5f);
    }

    @Test
    void theMarkCarriesTheOldMarkAndTheDepth() {
        float[] old = {1.0f, FoliageMarker.ALPHA, FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.METAL_ALPHA};
        for (int kind = 0; kind < old.length; kind++) {
            for (int steps = 0; steps < FoliageMarker.HANG_LEVELS; steps++) {
                int m = mark(FoliageMarker.hangAlpha(old[kind], steps * FoliageMarker.HANG_STEP));
                int h = m - FoliageMarker.HANG_BASE;
                assertEquals(kind, h / FoliageMarker.HANG_LEVELS);
                assertEquals(steps, h % FoliageMarker.HANG_LEVELS);
            }
        }
        //deeper than the levels: the deepest; a mark that is none of the four stays itself
        assertEquals(FoliageMarker.HANG_BASE + FoliageMarker.HANG_LEVELS - 1, mark(FoliageMarker.hangAlpha(1.0f, 40)));
        assertEquals(FoliageMarker.POLISHED_ALPHA, FoliageMarker.hangAlpha(FoliageMarker.POLISHED_ALPHA, 1));
    }

    @Test
    void theRangeMissesEveryOtherMark() {
        int lo = FoliageMarker.HANG_BASE, hi = FoliageMarker.HANG_BASE + 4 * FoliageMarker.HANG_LEVELS - 1;
        assertTrue(hi < mark(FoliageMarker.POLISHED_ALPHA), "below the lowest other mark (245)");
        for (float a : new float[]{FoliageMarker.ALPHA, FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.ORE_ALPHA, FoliageMarker.EMISSIVE_FLUID_ALPHA,
                FoliageMarker.WATER_ALPHA, FoliageMarker.METAL_ALPHA, FoliageMarker.POLISHED_ALPHA, FoliageMarker.SWAY_ALPHA,
                FoliageMarker.SWAY_HIGH_ALPHA, FoliageMarker.LEAF_ALPHA, 1.0f}) {
            int m = mark(a);
            assertFalse(m >= lo && m <= hi, "mark " + m);
        }
    }

    @Test
    void aVertexHangsByTheBlocksAboveAndItsHeight() {
        //a lantern under two chains: its top (y 1) hangs 2 blocks below the anchor, its foot (y 1/16) almost 3
        int top = mark(FoliageMarker.swayAlpha(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.HANGING, 1.0f, 2)) - FoliageMarker.HANG_BASE;
        int foot = mark(FoliageMarker.swayAlpha(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.HANGING, 1 / 16f, 2)) - FoliageMarker.HANG_BASE;
        assertEquals(2 * FoliageMarker.HANG_LEVELS + 8, top);
        assertEquals(2 * FoliageMarker.HANG_LEVELS + 12, foot);
        //the other kinds are as before
        assertEquals(FoliageMarker.SWAY_ALPHA, FoliageMarker.swayAlpha(FoliageMarker.ALPHA, FoliageMarker.PLANT, 0.5f, 3));
        assertEquals(FoliageMarker.LEAF_ALPHA, FoliageMarker.swayAlpha(1.0f, FoliageMarker.LEAVES, 0.5f));
    }

    @Test
    void theSwingFollowsTheSettingAndTheRain() {
        assertEquals(0, FoliageWind.hangAmplitude(0, 1));
        assertEquals(0, FoliageWind.hangAmplitude(Float.NaN, 0));
        assertEquals(FoliageWind.HANG_AMPLITUDE, FoliageWind.hangAmplitude(1, 0), 1e-6);
        assertEquals(FoliageWind.HANG_AMPLITUDE * 2 * (1 + FoliageWind.RAIN_GAIN), FoliageWind.hangAmplitude(2, 1), 1e-6);
        assertEquals(100, new VisualsConfig().hangingSway);
        assertEquals(VisualsOptionLayout.Kind.PERCENT, VisualsOptionLayout.item("hanging_sway").kind());
    }

    @Test
    void theShaderMirrorsTheMarks() throws IOException {
        String s = Files.readString(VSH);
        assertTrue(s.contains("const int WIND_HANG_BASE = " + FoliageMarker.HANG_BASE + ";"));
        assertTrue(s.contains("const int WIND_HANG_LEVELS = " + FoliageMarker.HANG_LEVELS + ";"));
        assertTrue(s.contains("const int WIND_HANG_KINDS = 4;"));
        assertTrue(s.contains("const float WIND_HANG_STEP = " + FoliageMarker.HANG_STEP + ";"));
        assertTrue(s.contains("float[4](1.0, 254.25 / 255.0, 253.25 / 255.0, 246.25 / 255.0)"));
        assertEquals(1.0f, FoliageMarker.HANG_MARKS[0]);
        assertEquals(FoliageMarker.ALPHA, FoliageMarker.HANG_MARKS[1]);
        assertEquals(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.HANG_MARKS[2]);
        assertEquals(FoliageMarker.METAL_ALPHA, FoliageMarker.HANG_MARKS[3]);
    }
}
