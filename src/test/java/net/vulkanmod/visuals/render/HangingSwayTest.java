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
    void theClockWrapsWithoutAJump() {
        //each swing makes whole cycles in the period, so the swing at the period's end is the one at its start
        for (int x = -3; x <= 3; x++) {
            float[] c = FoliageWind.hangColumn(x, 7);
            assertEquals(Math.round(c[0]), c[0]);
            assertEquals(Math.round(c[1]), c[1]);
            float[] end = FoliageWind.hangSwing(c, 1f - 1e-6f), start = FoliageWind.hangSwing(c, 0);
            assertEquals(start[0], end[0], 1e-3);
            assertEquals(start[1], end[1], 1e-3);
        }
        assertEquals(0.5, FoliageWind.wrapTime(120.5), 1e-9);
        assertEquals(119.5, FoliageWind.wrapTime(-0.5), 1e-9);
        //a step of a frame moves a swing by little anywhere in the period
        float[] c = FoliageWind.hangColumn(11, -4);
        float step = (float) (1 / 60.0 / FoliageWind.HANG_TIME_PERIOD);
        for (float t = 0; t < 1; t += 0.013f) {
            float[] a = FoliageWind.hangSwing(c, t), b = FoliageWind.hangSwing(c, t + step);
            assertTrue(Math.abs(a[0] - b[0]) < 0.06 && Math.abs(a[1] - b[1]) < 0.06, "t " + t);
        }
    }

    @Test
    void neighboursSwingApartAndTheCameraWrapLeavesThem() {
        java.util.Set<Float> cycles = new java.util.HashSet<>();
        java.util.Set<Float> sizes = new java.util.HashSet<>();
        for (int x = 0; x < 13; x++) {
            float[] c = FoliageWind.hangColumn(x, -2);
            assertTrue(c[0] >= FoliageWind.HANG_CYCLES && c[0] < FoliageWind.HANG_CYCLES + FoliageWind.HANG_CYCLES_SPAN);
            assertTrue(c[1] >= FoliageWind.HANG_CROSS_CYCLES && c[1] < FoliageWind.HANG_CROSS_CYCLES + FoliageWind.HANG_CROSS_SPAN);
            assertTrue(c[5] >= FoliageWind.HANG_SIZE && c[5] <= FoliageWind.HANG_SIZE + FoliageWind.HANG_SIZE_SPAN);
            cycles.add(c[0]);
            sizes.add(Math.round(c[5] * 20) / 20f);
            assertArrayEquals(c, FoliageWind.hangColumn(x + FoliageWind.HANG_WRAP, -2 - 2 * FoliageWind.HANG_WRAP));
        }
        assertTrue(cycles.size() >= 8, "a row of 13 has paces " + cycles);
        assertTrue(sizes.size() >= 5, "and sizes " + sizes);
        //the periods cover 2 .. 3 s a swing
        assertEquals(3.0, FoliageWind.HANG_TIME_PERIOD / FoliageWind.HANG_CYCLES, 1e-9);
        assertEquals(2.0, FoliageWind.HANG_TIME_PERIOD / (FoliageWind.HANG_CYCLES + FoliageWind.HANG_CYCLES_SPAN - 1), 1e-9);
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
        //the follow-up's columns
        assertTrue(s.contains("const int WIND_HANG_WRAP = " + FoliageWind.HANG_WRAP + ", WIND_HANG_REACH = 480;"));
        assertEquals(0, 480 % FoliageWind.HANG_WRAP);
        assertTrue(s.contains("const float WIND_HANG_CYCLES = " + FoliageWind.HANG_CYCLES + ".0, WIND_HANG_CYCLES_SPAN = " + FoliageWind.HANG_CYCLES_SPAN
                + ".0, WIND_HANG_CROSS_CYCLES = " + FoliageWind.HANG_CROSS_CYCLES + ".0, WIND_HANG_CROSS_SPAN = " + FoliageWind.HANG_CROSS_SPAN + ".0;"));
        assertTrue(s.contains("const float WIND_HANG_SIZE = " + FoliageWind.HANG_SIZE + ", WIND_HANG_SIZE_SPAN = " + FoliageWind.HANG_SIZE_SPAN + ";"));
        assertTrue(s.contains("uint state = column.x * 73856093u ^ column.y * 19349663u;"));
        assertTrue(s.contains("state = state * 747796405u + 2891336453u;"));
        assertTrue(s.contains("uint w = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;"));
        assertTrue(s.contains("float own = sin(6.2831853 * (fract(cycles * WindHang.y) + phase));"));
        assertEquals(FoliageWind.PERIOD, FoliageWind.HANG_WRAP, 1e-9);
    }
}
