package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.PostOn;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-16 (session 25 follow-up): what the Experimental page's settings change: the storm's particles (StormMath.Tuning through
 * WeatherUniforms) and the footprints' depth and duration (FootprintMath, footprint.glsl). */
class ExperimentalTuningTest {
    private static final float EPS = 1e-5f;

    @Test
    void theStormTuningScalesTheGrainsAndTheBlizzardsFlakes() {
        assertEquals(StormMath.Tuning.DEFAULT, StormMath.tuning(100, 100, 100, 100));
        StormMath.Tuning t = StormMath.tuning(10, 999, 50, -5);
        assertEquals(StormMath.TUNE_SIZE_MIN / 100f, t.grainSize(), EPS);
        assertEquals(StormMath.TUNE_AMOUNT_MAX / 100f, t.grainAmount(), EPS);
        assertEquals(0.5f, t.flakeSize(), EPS);
        assertEquals(0, t.flakeAmount(), EPS);
        float[] wind = {8, 3};
        //the default tuning: the grains' size factor 1, their share as before
        float[] o = WeatherUniforms.storm(new WeatherUniforms.Storm(1, 0, wind, new float[2], new float[2], true), new float[3], 10);
        assertEquals(1, o[7], EPS);
        float base = o[0];
        float[] half = WeatherUniforms.storm(new WeatherUniforms.Storm(1, 0, wind, new float[2], new float[2], true,
                StormMath.tuning(50, 50, 100, 100)), new float[3], 10);
        assertEquals(0.5f, half[7], EPS);
        assertEquals(base * 0.5f, half[0], EPS);
        float[] none = WeatherUniforms.storm(new WeatherUniforms.Storm(1, 0, wind, new float[2], new float[2], true,
                StormMath.tuning(100, 0, 100, 100)), new float[3], 10);
        assertEquals(0, none[0], EPS);
        //the dust veils do not follow the grain amount
        assertEquals(o[1], none[1], EPS);

        Matrix4f inv = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000).invert();
        PostProcessSettings on = PostProcessSettings.from(PostOn.defaults());
        WeatherUniforms.Values calm = WeatherUniforms.compute(on, 0.3f, 1, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, false, true, null);
        WeatherUniforms.Values def = blizzard(on, inv, StormMath.Tuning.DEFAULT);
        WeatherUniforms.Values noExtra = blizzard(on, inv, StormMath.tuning(100, 100, 100, 0));
        WeatherUniforms.Values big = blizzard(on, inv, StormMath.tuning(100, 100, 200, 100));
        //amount 0: the snowfall's own flakes only; the default adds SNOW_EXTRA more
        assertEquals(calm.params()[1], noExtra.params()[1], EPS);
        assertTrue(def.params()[1] > calm.params()[1] || def.params()[1] == 1);
        //the size scales the snow's size in a full blizzard, and nothing without one
        assertEquals(calm.style()[3] * 2, big.style()[3], EPS);
        assertEquals(calm.style()[3], def.style()[3], EPS);
    }

    private static WeatherUniforms.Values blizzard(PostProcessSettings on, Matrix4f inv, StormMath.Tuning tuning) {
        return WeatherUniforms.compute(on, 0.3f, 1, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, false, true, null,
                new WeatherUniforms.Storm(0, 1, new float[]{10, 0}, new float[2], new float[2], false, tuning));
    }

    @Test
    void theFootprintDepthAndDurationReachTheShaderAndTheRing() {
        //the shader unpacks what params packs
        String glsl = ShaderFiles.read("post/footprint.glsl");
        assertTrue(glsl.contains("const float FOOT_PACK = " + FootprintMath.PACK + ".0;"));
        float[] p = FootprintMath.params(100, 10, 64, 250, 300);
        int w = (int) p[3];
        assertEquals(p[3], w, 0);
        assertEquals(300, w / FootprintMath.PACK);
        assertEquals(250, w % FootprintMath.PACK);
        //the old call: the defaults; out of range kept in it
        assertEquals(100 + FootprintMath.PACK * 100, FootprintMath.params(100, 10, 64)[3], 0);
        float[] q = FootprintMath.params(100, 10, 64, 9999, 1);
        assertEquals(FootprintMath.DEPTH_MAX + FootprintMath.PACK * FootprintMath.DURATION_MIN, q[3], 0);
        //the biggest packed value stays exact in a float
        float max = FootprintMath.DEPTH_MAX + (float) FootprintMath.PACK * FootprintMath.DURATION_MAX;
        assertEquals((int) max, FootprintMath.DEPTH_MAX + FootprintMath.PACK * FootprintMath.DURATION_MAX);
        //the longest snow print stays within the time steps' wrap
        assertTrue(FootprintMath.fadeSeconds(false, FootprintMath.DURATION_MAX) < FootprintMath.TIME_WRAP * FootprintMath.TIME_STEP / 2);
        assertEquals(FootprintMath.SAND_FADE * 2, FootprintMath.fadeSeconds(true, 200), EPS);
        //the ring keeps a print for its scaled time
        FootprintMath.Ring ring = new FootprintMath.Ring(8);
        FootprintMath.Print snow = new FootprintMath.Print(0, 0, 64, 0, 0, false);
        ring.add(snow);
        assertNull(ring.expire(FootprintMath.SNOW_FADE * 3 - 0.1, 300));
        assertEquals(snow, ring.expire(FootprintMath.SNOW_FADE * 3, 300));
        //and holds enough prints for the longest snow time at a walk's pace (six a second)
        assertTrue(FootprintMath.RING >= 6 * FootprintMath.fadeSeconds(false, FootprintMath.DURATION_MAX));
        assertArrayEquals(new float[]{1, FootprintMath.timeStep(10), 64}, new float[]{p[0], p[1], p[2]}, EPS);
    }
}
