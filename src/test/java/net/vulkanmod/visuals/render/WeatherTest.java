package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-09 (session 6, the user: vanilla's rain and snow look thick and too vanilla; shader rain and snow with an on/off setting):
 * weather.glsl and its mirror WeatherMath, WeatherUniforms' rules, and where the overlay is drawn and blended.
 */
class WeatherTest {
    private static final String GLSL = ShaderFiles.read("post/weather.glsl");
    private static final String PASS = ShaderFiles.read("post/weather.comp");
    private static final String FINAL = ShaderFiles.read("post/post_final.frag");
    private static final String BLIT = ShaderFiles.read("post/post_blit.frag");
    private static final float[] WHITE = {1, 1, 1};
    /** A camera at block (100, 70, 200) + (0.5, 0.6, 0.5), a 1440-pixel-high 70-degree view. */
    private static final float[] CAMERA = {100, 70, 200, 70.6f};
    private static final float[] FRAC = {0.5f, 0.6f, 0.5f, 2 * (float) Math.tan(Math.toRadians(35)) / 1440};
    private static final float[] PARAMS = {1, 1, 1, 0};
    private static final float[] RAIN = WeatherUniforms.motion(WeatherUniforms.RAIN_FALL, new float[3], WeatherMath.RAIN_PERIOD, 3.7, WeatherUniforms.EXPOSURE);
    private static final float[] SNOW = WeatherUniforms.motion(WeatherUniforms.SNOW_FALL, new float[3], WeatherMath.SNOW_PERIOD, 3.7, WeatherUniforms.SNOW_EXPOSURE);

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static float[] dir(float azimuthDeg, float elevationDeg) {
        double a = Math.toRadians(azimuthDeg), e = Math.toRadians(elevationDeg);
        return new float[]{(float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e), (float) (-Math.cos(e) * Math.cos(a))};
    }

    /** A map of one climate everywhere with the height from a function of the world column. */
    private interface Height {
        int at(int x, int z);
    }

    private static WeatherMath.Map map(Height height, boolean snowLow) {
        return (tx, tz) -> {
            //the texel's world column nearest the camera (the map holds the 128 around it)
            int x = (int) CAMERA[0] - 64 + Math.floorMod(tx - ((int) CAMERA[0] - 64), 128);
            int z = (int) CAMERA[2] - 64 + Math.floorMod(tz - ((int) CAMERA[2] - 64), 128);
            return WeatherMath.pack(height.at(x, z), WeatherMath.Y_MAX, true, snowLow);
        };
    }

    /** The mean coverage over a fan of directions (azimuth every 5 degrees, the given elevations), each to sceneDist. */
    private static float meanAlpha(WeatherMath.Map map, float[] elevations, float sceneDist, WeatherMath.Light light) {
        double sum = 0;
        int n = 0;
        for (int az = 0; az < 360; az += 5) {
            for (float el : elevations) {
                for (float j = 0; j < 1; j += 0.25f) {
                    sum += WeatherMath.pixel(dir(az + j, el + 0.37f * j), sceneDist, CAMERA, FRAC, PARAMS, RAIN, SNOW, map, light)[3];
                    n++;
                }
            }
        }
        return (float) (sum / n);
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        int found = 0;
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(GLSL);
        while (m.find()) {
            Field f = WeatherMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 30, "constants found: " + found);
        for (Field f : WeatherMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class)) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(GLSL).find(), f.getName() + " missing in the shader");
        }
        assertTrue(GLSL.contains("0x7feb352du") && GLSL.contains("0x846ca68bu"));
        assertFalse(Pattern.compile("(?m)^\\s*(layout|uniform)\\b").matcher(GLSL).find(), "no uniforms: the pass declares them");
        //The literature the model follows is named where it is used
        assertTrue(GLSL.contains("Tatarchuk 2006") && GLSL.contains("Garg and") && GLSL.contains("Nayar 2006") && GLSL.contains("Amanatides and Woo 1987"));
        //The map's size divides the hash's period (the camera's block arrives modulo it) and is a power of two
        assertEquals(0, WeatherMath.WEATHER_WRAP % WeatherMath.WEATHER_MAP_SIZE);
        assertEquals(WeatherMath.WEATHER_MAP_SIZE - 1, WeatherMath.WEATHER_MAP_MASK);
        assertEquals(0, WeatherMath.WEATHER_MAP_SIZE & WeatherMath.WEATHER_MAP_MASK);
        //The far layer stays well inside the map around the camera (cells reach one cell past it)
        assertTrue(WeatherMath.WEATHER_LAYER_END_2 + 4 < WeatherMath.WEATHER_MAP_SIZE / 2f);
        //The layers' cells (1, 2, 4 blocks wide) can be walked out to their ends within the step budget by a level diagonal ray and one 30
        // degrees up (the cells are a period of rain times as tall)
        for (int layer = 0; layer < WeatherMath.WEATHER_LAYERS; layer++) {
            float start = layer == 0 ? WeatherMath.WEATHER_NEAR : WeatherMath.layerEnd(layer - 1);
            float len = WeatherMath.layerEnd(layer) - start, size = 1 << layer;
            float level = len / size * (float) Math.sqrt(2) + 3;
            float up = len * (float) Math.cos(Math.toRadians(30)) / size * (float) Math.sqrt(2) + len * 0.5f / (size * WeatherMath.RAIN_PERIOD) + 3;
            assertTrue(level <= WeatherMath.WEATHER_MAX_STEPS && up <= WeatherMath.WEATHER_MAX_STEPS, "layer " + layer + ": " + level + ", " + up);
        }
        //The fall offset wraps at a whole number of every layer's cells (a power of two at least the far layer's cell count)
        assertEquals(0, WeatherMath.WEATHER_FALL_WRAP & (WeatherMath.WEATHER_FALL_WRAP - 1));
        assertTrue(WeatherMath.WEATHER_FALL_WRAP >= 1 << (WeatherMath.WEATHER_LAYERS - 1));
    }

    @Test
    void thePassDefinesTheCallbacksBeforeTheModelAndTheFinalDrawsBlendItOnlyWhenDrawn() {
        int map = PASS.indexOf("uint weatherMapAt(ivec2 texel)"), light = PASS.indexOf("vec3 weatherLight(vec3 rel, bool snow)");
        int model = PASS.indexOf("#import <vulkanmod:visuals/post/weather.glsl>");
        assertTrue(map > 0 && light > 0 && model > map && model > light);
        assertTrue(PASS.contains("weatherPixel(dir, sceneDist, weatherCamera, weatherFrac, weatherParams, rainMotion, snowMotion)"));
        //The bindings VkPostProcess binds
        for (String[] b : new String[][]{{"0", "weatherOut"}, {"1", "mainDepth"}, {"2", "lodDepth"}, {"3", "precipitationMap"}, {"6", "lightmapTex"}}) {
            assertTrue(Pattern.compile("binding = " + b[0] + "\\b[^;]*\\b" + b[1] + ";").matcher(PASS).find(), b[1]);
        }
        assertTrue(PASS.contains("binding = 4, std430") && PASS.contains("binding = 5, std430"));
        for (String[] slot : new String[][]{{"0", "invVanillaProj"}, {"3", "depthParams"}, {"4", "lightCamera"}, {"8", "weatherCamera"},
                {"10", "weatherParams"}, {"12", "snowMotion"}, {"14", "snowTint"}}) {
            assertTrue(PASS.contains("layout(location = " + slot[0] + ") uniform vec4 " + slot[1]) || PASS.contains("layout(location = " + slot[0] + ") uniform mat4 " + slot[1]), slot[1]);
        }
        assertEquals(WeatherUniforms.CAMERA, 8);
        assertEquals(WeatherUniforms.SNOW_TINT, 14);
        //The final draw (no anti-aliasing) and the blit (after TAA and SMAA) blend the overlay only when told
        assertTrue(FINAL.contains("if (screenParams.w > 0.5)") && FINAL.contains("colour.rgb = colour.rgb*(1.0 - w.a) + w.rgb;"));
        assertTrue(BLIT.contains("if (sharpen.y > 0.5)") && BLIT.contains("c = c*(1.0 - w.a) + w.rgb;"));
        //post_ldr (the image TAA reads) does not see the overlay: postfinal.glsl's finalColour is untouched by it
        assertFalse(ShaderFiles.read("post/postfinal.glsl").contains("weather"));
    }

    @Test
    void theKindFollowsVanillasRuleAtTheCamerasHeightOrTheColumns() {
        int camY = 70;
        //Not known yet, or a biome without precipitation (desert): nothing
        assertEquals(WeatherMath.NONE, WeatherMath.kind(0, camY));
        assertEquals(WeatherMath.NONE, WeatherMath.kind(WeatherMath.pack(64, WeatherMath.Y_MAX, false, false), camY));
        //Up to y 80 the biome's own temperature decides; vanilla takes max(camera y - 10, height)
        assertEquals(WeatherMath.SNOW, WeatherMath.kind(WeatherMath.pack(64, 200, true, true), camY));
        assertEquals(WeatherMath.RAIN, WeatherMath.kind(WeatherMath.pack(64, 200, true, false), camY));
        //Above it the snow line: a warm biome's camera at y 300 sees snow over a low column, rain at y 200
        int texel = WeatherMath.pack(64, 250, true, false);
        assertEquals(WeatherMath.SNOW, WeatherMath.kind(texel, 300));
        assertEquals(WeatherMath.RAIN, WeatherMath.kind(texel, 200));
        assertEquals(WeatherMath.SNOW, WeatherMath.kind(WeatherMath.pack(260, 250, true, false), camY), "a column above the snow line");
        //Packing keeps every field
        int p = WeatherMath.pack(-61, 1234, true, true);
        assertEquals(-61, WeatherMath.height(p));
        assertNotEquals(0, p & WeatherMath.WEATHER_FLAG_KNOWN);
    }

    @Test
    void theSnowLineIsTheLowestSnowyHeightAboveY80() {
        for (int line : new int[]{81, 82, 150, 300, 1999, WeatherMath.Y_MAX}) {
            int want = line;
            assertEquals(want, PrecipitationMap.snowLine(y -> y >= want), "line " + line);
        }
        assertEquals(WeatherMath.Y_MAX, PrecipitationMap.snowLine(y -> false));
    }

    @Test
    void rainFallsOnlyAboveTheMotionBlockingHeightAndInFrontOfTheScene() {
        WeatherMath.Light white = (x, y, z, snow) -> WHITE;
        float[] around = {-10, -3, 0, 4, 12, 30};
        //Open ground 2 blocks below the eye: rain everywhere around
        float open = meanAlpha(map((x, z) -> 68, false), around, 1e30f, white);
        assertTrue(open > 0.001f, "open " + open);
        //A roof 3 blocks above the eye over everything: no drop below it (rays up end at the roof: the scene)
        WeatherMath.Map roofed = map((x, z) -> 74, false);
        double sum = 0;
        for (int az = 0; az < 360; az += 5) {
            for (float el : around) {
                float[] d = dir(az, el);
                float roof = d[1] > 0 ? (74 - CAMERA[3]) / d[1] : 1e30f;
                sum += WeatherMath.pixel(d, roof, CAMERA, FRAC, PARAMS, RAIN, SNOW, roofed, white)[3];
            }
        }
        assertEquals(0, sum, 1e-6, "under a roof");
        //A cave mouth: rock over the columns west of x 100, open east of it; every drawn drop is in an open column, above its height
        List<float[]> drops = new ArrayList<>();
        WeatherMath.Map mouth = map((x, z) -> x < 100 ? 90 : 68, false);
        WeatherMath.Light record = (x, y, z, snow) -> {
            drops.add(new float[]{x, y, z});
            return WHITE;
        };
        meanAlpha(mouth, around, 1e30f, record);
        assertFalse(drops.isEmpty());
        for (float[] d : drops) {
            int column = (int) CAMERA[0] + (int) Math.floor(FRAC[0] + d[0]);
            //The streak may reach a little past its anchor's column (within the cell's margin)
            assertTrue(column >= 99, "drop in column " + column);
            assertTrue(CAMERA[3] + d[1] >= 68 - 0.5f, "drop below the ground");
        }
        //Nothing behind the scene: with a wall 1.5 blocks away every drawn drop is in front of it (within the depth fade and a streak)
        List<float[]> front = new ArrayList<>();
        meanAlpha(map((x, z) -> 68, false), new float[]{-3, 0, 4}, 1.5f, (x, y, z, snow) -> {
            front.add(new float[]{x, y, z});
            return WHITE;
        });
        for (float[] d : front) assertTrue(Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) < 1.5 + 0.6, "behind the wall");
        float wall = meanAlpha(map((x, z) -> 68, false), new float[]{-3, 0, 4}, 1.5f, white);
        float far = meanAlpha(map((x, z) -> 68, false), new float[]{-3, 0, 4}, 1e30f, white);
        assertTrue(wall < 0.5f * far, wall + " vs " + far);
    }

    @Test
    void snowAndRainFallWhereTheirColumnsSayAndTheLayersReachOut() {
        List<float[]> drops = new ArrayList<>();
        //snow over the 64 columns east of the camera (x 100..163), rain over the others
        WeatherMath.Map half = (tx, tz) -> WeatherMath.pack(68, WeatherMath.Y_MAX, true, Math.floorMod(tx - 100, 128) < 64);
        WeatherMath.Light record = (x, y, z, snow) -> {
            int column = Math.floorMod((int) CAMERA[0] + (int) Math.floor(FRAC[0] + x) - 100, 128);
            drops.add(new float[]{x, y, z, snow ? 1 : 0, column < 64 ? 1 : 0, (float) Math.sqrt(x * x + y * y + z * z)});
            return WHITE;
        };
        meanAlpha(half, new float[]{-5, 0, 3, 10}, 1e30f, record);
        int snow = 0, rain = 0, mismatched = 0, far = 0;
        for (float[] d : drops) {
            if (d[3] > 0) snow++;
            else rain++;
            //a flake's sway or a streak may cross into the next column by the margin; count the clear mismatches only
            if (d[3] != d[4] && Math.abs(Math.floorMod((int) CAMERA[0] + (int) Math.floor(FRAC[0] + d[0]) - 100, 128)) > 1 && Math.abs(Math.floorMod((int) CAMERA[0] + (int) Math.floor(FRAC[0] + d[0]) - 100, 128) - 64) > 1) mismatched++;
            if (d[5] > WeatherMath.WEATHER_LAYER_END_1) far++;
        }
        assertTrue(snow > 5 && rain > 5, snow + " / " + rain);
        assertEquals(0, mismatched);
        assertTrue(far > 3, "drops in the far layer: " + far);
    }

    @Test
    void streaksStretchWithTheFallAndTurnWithTheCameraWithinTheirCell() {
        //Still rain: a vertical streak, u T / 2 long
        assertEquals(-WeatherUniforms.RAIN_FALL * WeatherUniforms.EXPOSURE / 2, RAIN[1], 1e-6f);
        assertEquals(0, RAIN[0], 0);
        //Flying east at 20 blocks per second: the drops move west relative to the camera
        float[] flying = WeatherUniforms.motion(WeatherUniforms.RAIN_FALL, new float[]{20, 0, 0}, WeatherMath.RAIN_PERIOD, 0, WeatherUniforms.EXPOSURE);
        assertTrue(flying[0] < 0);
        for (int layer = 0; layer < WeatherMath.WEATHER_LAYERS; layer++) {
            float cell = 1 << layer, cellHeight = WeatherMath.RAIN_PERIOD * cell;
            float[] h = WeatherMath.halfStreak(flying, cell, cellHeight, 0);
            assertTrue(Math.hypot(h[0], h[2]) <= cell * WeatherMath.WEATHER_MARGIN + 1e-6, "inside the cell's margin");
            assertTrue(Math.abs(h[1]) <= cellHeight * WeatherMath.WEATHER_MARGIN + 1e-6);
            //the direction is kept
            assertEquals(flying[0] / flying[1], h[0] / h[1], 1e-4);
        }
        //A fast fall (falling camera upward relative motion) stays within the period
        float[] dive = WeatherUniforms.motion(WeatherUniforms.RAIN_FALL, new float[]{0, -100, 0}, WeatherMath.RAIN_PERIOD, 0, WeatherUniforms.EXPOSURE);
        assertTrue(dive[1] > 0);
        assertEquals(WeatherMath.RAIN_PERIOD * WeatherMath.WEATHER_MARGIN, WeatherMath.halfStreak(dive, 1, WeatherMath.RAIN_PERIOD, 0)[1], 1e-5);
        //Still rain fits the near cells unclamped (the streak keeps its length)
        assertEquals(RAIN[1], WeatherMath.halfStreak(RAIN, 1, WeatherMath.RAIN_PERIOD, 0)[1], 1e-6);
        //The closest approach of a ray and a segment
        float[] hit = WeatherMath.raySegment(new float[]{1, 0, 0}, new float[]{5, -1, 0.3f}, new float[]{5, 1, 0.3f});
        assertEquals(0.3f, hit[0], 1e-5f);
        assertEquals(5, hit[1], 1e-5f);
        assertEquals(0.5f, hit[2], 1e-5f);
    }

    @Test
    void farStreaksThinOutInsteadOfFlickering() {
        //A streak narrower than the pixel spreads over the pixel's width with its coverage scaled down: the sum across stays alpha x width
        float pixel = FRAC[3];
        for (float t : new float[]{1, 5, 20, 32}) {
            float footprint = pixel * t;
            float w = (float) Math.sqrt(WeatherMath.RAIN_WIDTH * WeatherMath.RAIN_WIDTH + footprint * footprint);
            double sum = 0;
            int n = 2000;
            for (int i = -n; i <= n; i++) {
                float d = Math.abs(i) * w / (n / 2f);
                sum += WeatherMath.coverage(d, WeatherMath.RAIN_WIDTH, t, pixel, 1) * (w / (n / 2f));
            }
            //the smoothstep profile's integral over -w..w is w
            assertEquals(WeatherMath.RAIN_WIDTH, sum, WeatherMath.RAIN_WIDTH * 0.01, "t " + t);
        }
        assertTrue(WeatherMath.coverage(0, WeatherMath.RAIN_WIDTH, 30, pixel, 1) < 0.5f);
    }

    @Test
    void theFallOffsetWrapsAtTheFarCellsHeight() {
        double wrap = WeatherMath.RAIN_PERIOD * WeatherMath.WEATHER_FALL_WRAP;
        assertEquals(WeatherUniforms.wrap(3.7 * WeatherUniforms.RAIN_FALL, wrap), RAIN[3], 1e-5);
        assertEquals(WeatherUniforms.wrap(3.7 * WeatherUniforms.SNOW_FALL, WeatherMath.SNOW_PERIOD * WeatherMath.WEATHER_FALL_WRAP), SNOW[3], 1e-5);
        assertTrue(RAIN[3] >= 0 && RAIN[3] < wrap);
        assertEquals(0, WeatherUniforms.wrap(Double.NaN, 1), 0);
        //A frame offset by one wrap draws the same drops (the walk's cells move by whole cells in every layer)
        WeatherMath.Map map = map((x, z) -> 68, false);
        WeatherMath.Light white = (x, y, z, snow) -> WHITE;
        float[] shifted = RAIN.clone();
        shifted[3] -= (float) wrap;
        for (int az = 0; az < 360; az += 30) {
            float[] d = dir(az, 5);
            assertArrayEquals(WeatherMath.pixel(d, 1e30f, CAMERA, FRAC, PARAMS, RAIN, SNOW, map, white),
                    WeatherMath.pixel(d, 1e30f, CAMERA, FRAC, PARAMS, shifted, SNOW, map, white), 1e-3f);
        }
    }

    @Test
    void theStrengthFollowsRainAndThunderAndTheSettings() {
        Matrix4f inv = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 512).invert();
        PostProcessSettings on = PostProcessSettings.from(new VisualsConfig());
        assertTrue(on.shaderWeather(), "on by default");
        assertFalse(WeatherUniforms.compute(on, 0, 0, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true).on(), "clear");
        assertFalse(WeatherUniforms.compute(settings(c -> c.shaderWeather = false), 1, 0, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true).on());
        assertFalse(WeatherUniforms.compute(settings(c -> c.enabled = false), 1, 0, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true).on());
        assertFalse(WeatherUniforms.compute(on, 1, 0, true, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true).on(), "in water");
        WeatherUniforms.Values light = WeatherUniforms.compute(on, 0.3f, 0, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true);
        WeatherUniforms.Values full = WeatherUniforms.compute(on, 1, 0, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true);
        WeatherUniforms.Values storm = WeatherUniforms.compute(on, 1, 1, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true);
        WeatherUniforms.Values dense = WeatherUniforms.compute(settings(c -> c.weatherDensity = 200), 1, 0, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true);
        assertTrue(light.params()[0] < full.params()[0] && full.params()[0] < storm.params()[0], "more drops with more rain and thunder");
        assertTrue(light.params()[2] < full.params()[2]);
        assertEquals(Math.min(1, 2 * full.params()[0]), dense.params()[0], 1e-6);
        assertTrue(storm.params()[0] <= 1 && storm.params()[1] <= 1);
        //A kind the map around the camera does not hold is not walked
        WeatherUniforms.Values rainOnly = WeatherUniforms.compute(on, 1, 0, false, inv, inv, new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, false);
        assertEquals(0, rainOnly.params()[1], 0);
        assertEquals(full.params()[0], rainOnly.params()[0], 0);
        //The pixel's angle from the projection: 2 tan(35 degrees) / 1440
        assertEquals(2 * Math.tan(Math.toRadians(35)) / 1440, full.frac()[3], 1e-7);
        //The camera's block modulo the hash period, its y exact
        WeatherUniforms.Values far = WeatherUniforms.compute(on, 1, 0, false, inv, inv, new Matrix4f(), false, 1440, -4097.25, 64.5, 9000.75, null, 0, true, true);
        assertEquals(Math.floorMod(-4098, 4096), far.camera()[0], 0);
        assertEquals(Math.floorMod(9000, 4096), far.camera()[2], 0);
        assertEquals(64.5f, far.camera()[3], 0);
        assertEquals(0.75f, far.frac()[0], 1e-6f);
    }

    @Test
    void theCamerasVelocityIsFollowedAndATeleportForgetsIt() {
        float[] v = new float[3];
        for (int i = 0; i < 60; i++) v = WeatherUniforms.followVelocity(v, 20 / 60.0, 0, 0, 1 / 60.0);
        assertEquals(20, v[0], 0.5);
        assertArrayEquals(v, WeatherUniforms.followVelocity(v, 0, 0, 0, 0), "paused: kept");
        assertArrayEquals(new float[3], WeatherUniforms.followVelocity(v, 300, 0, 0, 1 / 60.0), "teleport");
    }

    @Test
    void vanillaIsLeftOutOnlyWhileTheShaderCanDrawAndASkippedFrameRestoresIt() {
        ShaderWeather.set(true);
        FrameFlags.resetForSkippedPost();
        assertFalse(ShaderWeather.replacesVanilla());
        assertTrue(WeatherUniforms.wanted(PostProcessSettings.from(new VisualsConfig())));
        assertFalse(WeatherUniforms.wanted(settings(c -> c.shaderWeather = false)));
        assertFalse(WeatherUniforms.wanted(null));
    }

    /** A pixel on the ground at world (x, y, z) seen from CAMERA: its offset, direction and distance. */
    private static float[][] ground(float x, float y, float z) {
        float[] rel = {x - (CAMERA[0] + FRAC[0]), y - CAMERA[3], z - (CAMERA[2] + FRAC[2])};
        float d = (float) Math.sqrt(rel[0] * rel[0] + rel[1] * rel[1] + rel[2] * rel[2]);
        return new float[][]{rel, {rel[0] / d, rel[1] / d, rel[2] / d}, {d}};
    }

    /** The mean splash coverage over a 6x6 block patch of ground at height y (16 samples a block) at time seconds. */
    private static float meanSplash(WeatherMath.Map map, float y, double seconds) {
        float[] splash = {(float) WeatherUniforms.wrap(seconds, (double) WeatherMath.SPLASH_PERIOD * WeatherMath.SPLASH_WRAP), 0, 0, 0};
        double sum = 0;
        int n = 0;
        for (float x = 102; x < 108; x += 1 / 16f) {
            for (float z = 196; z < 202; z += 1 / 16f) {
                float[][] g = ground(x, y, z);
                sum += WeatherMath.splash(g[0], g[1], g[2][0], CAMERA, FRAC, PARAMS, splash, map, (a, b, c, snow) -> WHITE)[3];
                n++;
            }
        }
        return (float) (sum / n);
    }

    @Test
    void splashesHitOnlyTheTopsOfRainedOnColumnsAndAreShortLived() {
        //2026-11-10 (the user: the rain bouncing off the grass still looks vanilla): on open grass (top at 68, the first free block)
        WeatherMath.Map grass = map((x, z) -> 68, false);
        float open = 0;
        for (int i = 0; i < 8; i++) open += meanSplash(grass, 68, 3.1 + i * 0.07) / 8;
        assertTrue(open > 1e-4f && open < 0.05f, "subtle splashes on open grass: " + open);
        //Water's surface (8/9 of a block) lies below its column's height: no splash there (the 2a ripples stay)
        assertEquals(0, meanSplash(grass, 67 + 8 / 9f, 3.1), 0);
        //Under a roof (the column's height is the roof's) and where it snows: none
        assertEquals(0, meanSplash(map((x, z) -> 74, false), 68, 3.1), 0);
        assertEquals(0, meanSplash(map((x, z) -> 68, true), 68, 3.1), 0);
        //No rain share (clear, or the setting's density 0): none
        float[][] g = ground(104.3f, 68, 199.2f);
        assertEquals(0, WeatherMath.splash(g[0], g[1], g[2][0], CAMERA, FRAC, new float[]{0, 0, 1, 0}, new float[4], grass, (a, b, c, snow) -> WHITE)[3], 0);
        //A spot is lit only for a short part of the period: over many instants, at most about SPLASH_LIFE / SPLASH_PERIOD of the time
        int lit = 0, n = 400;
        for (int i = 0; i < n; i++) {
            float[] splash = {(float) (i * WeatherMath.SPLASH_PERIOD * 0.37), 0, 0, 0};
            for (float dx = 0; dx < 0.5f; dx += 0.01f) {
                if (WeatherMath.splash(ground(104.25f + dx, 68, 199.25f)[0], ground(104.25f + dx, 68, 199.25f)[1], ground(104.25f + dx, 68, 199.25f)[2][0],
                        CAMERA, FRAC, PARAMS, splash, grass, (a, b, c, snow) -> WHITE)[3] > 0) {
                    lit++;
                    break;
                }
            }
        }
        assertTrue(lit > 0 && lit <= n * (WeatherMath.SPLASH_LIFE / WeatherMath.SPLASH_PERIOD) * 1.3, "lit " + lit + " of " + n);
        //The wrapped time repeats exactly: the splashes are the same one wrap later
        double wrap = (double) WeatherMath.SPLASH_PERIOD * WeatherMath.SPLASH_WRAP;
        assertEquals(meanSplash(grass, 68, 3.1), meanSplash(grass, 68, 3.1 + wrap), 1e-6);
        assertEquals(0, WeatherMath.SPLASH_WRAP % WeatherMath.SPLASH_EVENTS);
        //A splash stays inside its cell (the ring and the thrown flecks), so one cell's lookup finds all of it
        assertTrue(0.25f * WeatherMath.SPLASH_CELL >= WeatherMath.SPLASH_RADIUS * WeatherMath.SPLASH_THROW + WeatherMath.SPLASH_FLECK);
    }

    @Test
    void vanillasRainParticlesAreLeftOutOnlyWhileTheShaderDrawsTheWeather() throws Exception {
        java.lang.reflect.Method m = Class.forName("net.vulkanmod.mixin.visuals.LevelRendererWeatherMixin", false, getClass().getClassLoader())
                .getDeclaredMethod("visuals$rainSplash", net.minecraft.client.multiplayer.ClientLevel.class, net.minecraft.core.particles.ParticleOptions.class,
                        double.class, double.class, double.class, double.class, double.class, double.class);
        org.spongepowered.asm.mixin.injection.Redirect r = m.getAnnotation(org.spongepowered.asm.mixin.injection.Redirect.class);
        assertNotNull(r);
        assertEquals("tickRain", r.method()[0]);
        assertTrue(PASS.contains("layout(location = 15) uniform vec4 splashParams;") && PASS.contains("weatherSplash(mat3(invView)*view, dir, sceneDist"));
        assertEquals(15, WeatherUniforms.SPLASH);
    }
}
