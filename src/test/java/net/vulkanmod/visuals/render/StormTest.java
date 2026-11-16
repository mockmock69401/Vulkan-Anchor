package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 25: the sandstorm and the blizzard (StormMath, the map's dust flag, WeatherUniforms' storm slots). */
class StormTest {
    private static final float EPS = 1e-5f;

    @Test
    void strengthIsThunderTimesShareTimesSetting() {
        assertEquals(0, StormMath.strength(0, 1, 100), EPS);
        assertEquals(0, StormMath.strength(1, 0, 100), EPS);
        assertEquals(0, StormMath.strength(1, 1, 0), EPS);
        assertEquals(1, StormMath.strength(1, 1, 100), EPS);
        assertEquals(0.25f, StormMath.strength(0.5f, 0.5f, 100), EPS);
        assertEquals(2, StormMath.strength(1, 1, 999), EPS);
        assertEquals(0, StormMath.strength(Float.NaN, 1, 100), EPS);
    }

    @Test
    void noStormLeavesTheAirExactlyAsBefore() {
        assertEquals(1, StormMath.fog(0, 0), 0);
        assertEquals(1, StormMath.haze(0, 0), 0);
        assertArrayEquals(new float[]{1, 1, 1}, StormMath.tint(0, 0), 0);
        //a sandstorm warms the air (red over blue), a blizzard cools it (blue over red); both thicken it
        float[] sand = StormMath.tint(1, 0), snow = StormMath.tint(0, 1);
        assertTrue(sand[0] > sand[1] && sand[1] > sand[2]);
        assertTrue(snow[2] > snow[0]);
        assertTrue(StormMath.fog(1, 0) > 5 && StormMath.fog(0, 1) > 3);
        assertTrue(StormMath.haze(1, 0) > 1 && StormMath.haze(0, 1) > 1);
        //the tint is capped at full strength (200% thickens the air, not the colour)
        assertArrayEquals(sand, StormMath.tint(2, 0), EPS);
    }

    @Test
    void theWindTurnsAndGustsWithinBounds() {
        float lo = Float.MAX_VALUE, hi = 0;
        for (int i = 0; i < 4000; i++) {
            float[] w = StormMath.wind(i * 0.37);
            float s = (float) Math.hypot(w[0], w[1]);
            lo = Math.min(lo, s);
            hi = Math.max(hi, s);
            double a = Math.atan2(w[1], w[0]);
            assertTrue(Math.abs(a - StormMath.WIND_ANGLE) <= StormMath.WIND_TURN + 1e-4, "turns only so far");
        }
        assertTrue(lo >= StormMath.WIND_SPEED * (1 - StormMath.GUST) - 1e-3 && hi <= StormMath.WIND_SPEED * (1 + StormMath.GUST) + 1e-3);
        assertTrue(hi - lo > StormMath.WIND_SPEED * 0.3f, "it gusts");
        //continuous: no jump between frames
        float[] a = StormMath.wind(100), b = StormMath.wind(100.016);
        assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.2);
    }

    @Test
    void theDriftIntegratesTheWindAndWrapsWhereTheCellsHashModulo() {
        float[] d = StormMath.advance(new float[2], new float[]{10, -4}, 0.1, 1);
        assertEquals(1, d[0], EPS);
        assertEquals(WeatherMath.WEATHER_WRAP - 0.4f, d[1], 1e-3f);
        //scaled (a half-strength blizzard drifts at half the wind), a hitch is clamped, a pause or NaN adds nothing
        assertEquals(0.5f, StormMath.advance(new float[2], new float[]{10, 0}, 0.1, 0.5f)[0], EPS);
        assertEquals(2.5f, StormMath.advance(new float[2], new float[]{10, 0}, 3.0, 1)[0], EPS);
        assertArrayEquals(new float[]{7, 9}, StormMath.advance(new float[]{7, 9}, new float[]{10, 10}, Double.NaN, 1), EPS);
        float[] near = StormMath.advance(new float[]{WeatherMath.WEATHER_WRAP - 0.5f, 0}, new float[]{10, 0}, 0.1, 1);
        assertEquals(0.5f, near[0], 1e-3f);
    }

    @Test
    void desertColumnsCarryTheDustFlag() {
        int dust = WeatherMath.pack(70, WeatherMath.Y_MAX, false, false, false, true);
        int plain = WeatherMath.pack(70, WeatherMath.Y_MAX, false, false, false);
        assertEquals(WeatherMath.WEATHER_FLAG_DUST, dust & WeatherMath.WEATHER_FLAG_DUST);
        assertEquals(0, plain & WeatherMath.WEATHER_FLAG_DUST);
        //a dusty column still has no rain or snow, and keeps its height
        assertEquals(WeatherMath.NONE, WeatherMath.kind(dust, 70));
        assertEquals(70, WeatherMath.height(dust));
        //the flag sits above every other field
        assertEquals(dust & ~WeatherMath.WEATHER_FLAG_DUST, plain);
    }

    @Test
    void theMapReportsDustOnlyNearTheCamera() {
        PrecipitationMap map = new PrecipitationMap();
        PrecipitationMap.Columns desert = new PrecipitationMap.Columns() {
            public int height(int x, int z) {
                return 64;
            }

            public PrecipitationMap.Climate climate(int x, int y, int z) {
                return x > 100 ? PrecipitationMap.Climate.DUST : PrecipitationMap.Climate.NONE;
            }
        };
        for (int i = 0; i < 64; i++) map.update(desert, 0, 70, 0, PrecipitationMap.BUDGET);
        assertTrue(!map.hasDust(), "the desert is beyond the walk's reach");
        PrecipitationMap map2 = new PrecipitationMap();
        for (int i = 0; i < 64; i++) map2.update(desert, 110, 70, 0, PrecipitationMap.BUDGET);
        assertTrue(map2.hasDust());
        assertTrue(!map2.hasRain() && !map2.hasSnow());
    }

    @Test
    void theStormSlotsAreZeroWithoutAStormAndDriveTheSandWithIt() {
        assertArrayEquals(new float[16], WeatherUniforms.storm(WeatherUniforms.Storm.NONE, new float[3], 10), 0);
        float[] wind = {8, 3};
        WeatherUniforms.Storm s = new WeatherUniforms.Storm(1, 0, wind, new float[]{1, 2}, new float[]{3, 4}, true);
        float[] o = WeatherUniforms.storm(s, new float[3], 10);
        assertEquals(StormMath.SAND_SHARE, o[0], EPS);
        assertEquals(1, o[1], EPS);
        assertEquals(0, o[2], EPS);
        assertEquals(10, o[3], EPS);
        //the grains streak along the wind, level, while the camera stands still
        assertEquals(wind[0] * StormMath.SAND_EXPOSURE / 2, o[4], EPS);
        assertEquals(0, o[5], EPS);
        assertEquals(wind[1] * StormMath.SAND_EXPOSURE / 2, o[6], EPS);
        assertArrayEquals(new float[]{1, 2, 3, 4}, new float[]{o[8], o[9], o[10], o[11]}, EPS);
        //no desert column near: no grains, the veils still blow over the dust the map may hold farther out
        assertEquals(0, WeatherUniforms.storm(new WeatherUniforms.Storm(1, 0, wind, new float[2], new float[2], false), new float[3], 10)[0], EPS);
    }

    @Test
    void theSandIsFineAndSparse() {
        //2026-11-16 (the user: "모래 입자가 너무 굵고 많은 거 같아. 두 부분을 줄여줘"): thinner, fainter grains in fewer cells than the first cut
        String glsl = net.vulkanmod.visuals.vk.shader.ShaderFiles.read("post/weather.glsl");
        java.util.regex.Matcher r = java.util.regex.Pattern.compile("const float SAND_RADIUS = ([0-9.]+);").matcher(glsl);
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("const float SAND_ALPHA = ([0-9.]+);").matcher(glsl);
        assertTrue(r.find() && a.find());
        assertTrue(Float.parseFloat(r.group(1)) <= 0.003f);
        assertTrue(Float.parseFloat(a.group(1)) < 0.5f);
        assertTrue(StormMath.SAND_SHARE <= 0.4f);
        //a full sandstorm at the default tuning: grains in under half the cells
        float[] o = WeatherUniforms.storm(new WeatherUniforms.Storm(1, 0, new float[]{8, 3}, new float[2], new float[2], true), new float[3], 10);
        assertTrue(o[0] > 0 && o[0] < 0.5f);
    }

    @Test
    void aBlizzardThickensTheSnowAndBlowsItSideways() {
        Matrix4f inv = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000).invert();
        PostProcessSettings on = PostProcessSettings.from(new net.vulkanmod.visuals.config.VisualsConfig());
        WeatherUniforms.Values calm = WeatherUniforms.compute(on, 1, 1, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, false, true, null);
        WeatherUniforms.Values storm = WeatherUniforms.compute(on, 1, 1, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, false, true, null,
                new WeatherUniforms.Storm(0, 1, new float[]{10, 0}, new float[2], new float[2], false));
        assertTrue(storm.params()[1] >= calm.params()[1]);
        //the flakes' streak leans with the wind (x), and is longer than a calm flake's (which only falls)
        assertEquals(10 * StormMath.SNOW_STORM_EXPOSURE / 2, storm.snow()[0], 1e-5f);
        assertEquals(0, calm.snow()[0], EPS);
        assertEquals(0, storm.storm()[0], EPS);
        assertEquals(1, storm.storm()[2], EPS);
    }

    @Test
    void aStormHidesTheSunAndMuddiesTheWater() {
        assertEquals(0, StormMath.cover(0, 0), 0);
        assertEquals(1, StormMath.cover(2, 0), 0);
        assertEquals(1, StormMath.cover(0.6f, 0.6f), 0);
        assertEquals(0.5f, StormMath.cover(0.5f, 0), EPS);
        assertArrayEquals(new float[]{1, 1, 1}, StormMath.waterTint(0, 0), 0);
        float[] sand = StormMath.waterTint(1, 0), snow = StormMath.waterTint(0, 1);
        //murky brown with dust, grey and darker under a white-out
        assertTrue(sand[0] > sand[1] && sand[1] > sand[2] && sand[0] < 1);
        assertTrue(snow[0] < 1 && snow[2] < 1 && snow[2] > snow[0]);
        //water.glsl hides the discs (and their glints) once the overcast share times DISC_RAIN_HIDE reaches 1: a full storm's cover does
        assertTrue(StormMath.cover(1, 0) * WaterMath.DISC_RAIN_HIDE >= 1);
    }
}
