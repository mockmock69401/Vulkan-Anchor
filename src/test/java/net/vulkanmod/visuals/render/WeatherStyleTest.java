package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-11 (the user: "the snow and rain particles' type (round, pixel, both), size, amount and colour"): the shader weather's types and
 * sizes (weather.glsl's style, WeatherMath's mirror) and the amounts per kind (WeatherUniforms).
 */
class WeatherStyleTest {
    private static final String GLSL = ShaderFiles.read("post/weather.glsl");
    private static final float[] WHITE = {1, 1, 1};
    private static final float[] CAMERA = {100, 70, 200, 70.6f};
    private static final float[] FRAC = {0.5f, 0.6f, 0.5f, 2 * (float) Math.tan(Math.toRadians(35)) / 1440};
    private static final float[] PARAMS = {1, 1, 1, 0.7f};
    private static final float[] RAIN = WeatherUniforms.motion(WeatherUniforms.RAIN_FALL, new float[3], WeatherMath.RAIN_PERIOD, 3.7, WeatherUniforms.EXPOSURE);
    private static final float[] SNOW = WeatherUniforms.motion(WeatherUniforms.SNOW_FALL, new float[3], WeatherMath.SNOW_PERIOD, 3.7, WeatherUniforms.SNOW_EXPOSURE);
    private static final WeatherMath.Light WHITE_LIGHT = (x, y, z, snow) -> WHITE;

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static WeatherMath.Map open(boolean snow) {
        return (tx, tz) -> WeatherMath.pack(40, WeatherMath.Y_MAX, true, snow);
    }

    private static float[] dir(float azimuthDeg, float elevationDeg) {
        double a = Math.toRadians(azimuthDeg), e = Math.toRadians(elevationDeg);
        return new float[]{(float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e), (float) (-Math.cos(e) * Math.cos(a))};
    }

    private static float meanAlpha(WeatherMath.Map map, float[] style) {
        double sum = 0;
        int n = 0;
        for (float az = 0; az < 360; az += 1.3f) {
            for (float el = -8; el <= 20; el += 2.9f) {
                sum += WeatherMath.pixel(dir(az, el), 1e30f, CAMERA, FRAC, PARAMS, RAIN, SNOW, style, map, WHITE_LIGHT)[3];
                n++;
            }
        }
        return (float) (sum / n);
    }

    private static Matrix4f inv() {
        return new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 512).invert();
    }

    private static WeatherUniforms.Values rainy(PostProcessSettings s) {
        return WeatherUniforms.compute(s, 1, 0, false, inv(), inv(), new Matrix4f(), false, 1440, 0, 70, 0, null, 0, true, true, null);
    }

    @Test
    void theDefaultsAreTheRoundTunedLookAndEachKindHasItsOwnAmount() {
        WeatherUniforms.Values d = rainy(PostProcessSettings.from(new VisualsConfig()));
        assertArrayEquals(new float[]{WeatherMath.WEATHER_TYPE_ROUND, WeatherMath.WEATHER_TYPE_ROUND, 1, 1}, d.style(), 0);
        //The model with the default style is the model before the setting (the overload without it)
        for (float az = 0; az < 360; az += 7) {
            float[] v = dir(az, 3);
            assertArrayEquals(WeatherMath.pixel(v, 1e30f, CAMERA, FRAC, PARAMS, RAIN, SNOW, open(true), WHITE_LIGHT),
                    WeatherMath.pixel(v, 1e30f, CAMERA, FRAC, PARAMS, RAIN, SNOW, d.style(), open(true), WHITE_LIGHT), 0);
        }
        WeatherUniforms.Values styled = rainy(settings(c -> {
            c.snowType = 1;
            c.rainType = 2;
            c.snowSize = 150;
            c.rainSize = 50;
        }));
        assertArrayEquals(new float[]{2, 1, 0.5f, 1.5f}, styled.style(), 1e-6f);
        //The amounts: each kind's share on its own
        WeatherUniforms.Values more = rainy(settings(c -> c.rainAmount = 200));
        WeatherUniforms.Values none = rainy(settings(c -> c.snowAmount = 0));
        assertEquals(Math.min(1, 2 * d.params()[0]), more.params()[0], 1e-6f);
        assertEquals(d.params()[1], more.params()[1], 0);
        assertEquals(0, none.params()[1], 0);
        assertEquals(d.params()[0], none.params()[0], 0);
        assertTrue(GLSL.contains("void weatherDrop(inout vec4 acc, vec3 dir, float sceneDist, ivec3 cell, int layer, int drop, bool isSnow, vec3 origin, vec4 camera, vec4 frac,"));
    }

    @Test
    void theBrightnessScalesEachKindsColourApartFromIt() {
        WeatherUniforms.Values d = rainy(PostProcessSettings.from(new VisualsConfig()));
        assertArrayEquals(ShaderColours.DEFAULT.tint(ShaderColours.Colour.RAIN), d.rainTint(), 0, "100: the colour exactly");
        WeatherUniforms.Values b = rainy(settings(c -> {
            c.snowBrightness = 50;
            c.rainBrightness = 180;
            c.colourSnow = 0xFF8080;
        }));
        float[] snowColour = ShaderColours.of(new VisualsConfig() {{
            colourSnow = 0xFF8080;
        }}.colours()).tint(ShaderColours.Colour.SNOW);
        for (int i = 0; i < 3; i++) {
            assertEquals(snowColour[i] * 0.5f, b.snowTint()[i], 1e-6f);
            assertEquals(d.rainTint()[i] * 1.8f, b.rainTint()[i], 1e-6f);
        }
    }

    @Test
    void aPixelSnowflakeDrawsItsShapeTexelByTexel() {
        float[] a = {0.3f, 0.1f, -2.0f};
        float half = WeatherMath.SNOW_PIXEL_HALF;
        float aa = a[0] * a[0] + a[1] * a[1] + a[2] * a[2];
        float inv = (float) (1 / Math.sqrt(aa));
        float[] f = {a[0] * inv, a[1] * inv, a[2] * inv};
        float hl = (float) Math.hypot(f[2], f[0]);
        float[] right = {f[2] / hl, 0, -f[0] / hl};
        float[] up = {f[1] * right[2] - f[2] * right[1], f[2] * right[0] - f[0] * right[2], f[0] * right[1] - f[1] * right[0]};
        for (int s = 0; s < WeatherMath.SNOW_PIXEL_SHAPES; s++) {
            int shape = WeatherMath.snowShape(s);
            int seen = 0;
            for (int row = 0; row < WeatherMath.PIXEL_GRID; row++) {
                for (int col = 0; col < WeatherMath.PIXEL_GRID; col++) {
                    //the texel's centre on the sprite (row 0 at the top)
                    float u = ((col + 0.5f) / WeatherMath.PIXEL_GRID * 2 - 1) * half;
                    float v = (1 - (row + 0.5f) / WeatherMath.PIXEL_GRID * 2) * half;
                    float[] p = {a[0] + right[0] * u + up[0] * v, a[1] + right[1] * u + up[1] * v, a[2] + right[2] * u + up[2] * v};
                    float l = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
                    float[] d = {p[0] / l, p[1] / l, p[2] / l};
                    float[] c = WeatherMath.sprite(d, a, new float[]{half, half}, 0, shape, FRAC[3]);
                    if (c[0] > 0.5f) seen |= 1 << (row * WeatherMath.PIXEL_GRID + col);
                    assertEquals(c[0] > 0.5f ? 1 : 0, c[0], 1e-4f, "crisp at 2 blocks");
                }
            }
            assertEquals(shape, seen, "shape " + s);
            //Outside the sprite nothing
            float[] beside = {a[0] + right[0] * 2 * half, a[1], a[2] + right[2] * 2 * half};
            float bl = (float) Math.sqrt(beside[0] * beside[0] + beside[1] * beside[1] + beside[2] * beside[2]);
            assertEquals(0, WeatherMath.sprite(new float[]{beside[0] / bl, beside[1] / bl, beside[2] / bl}, a, new float[]{half, half}, 0, shape, FRAC[3])[0], 0);
        }
    }

    @Test
    void farPixelSpritesFadeToTheirMeanAndTinyOnesWidenInsteadOfFlickering() {
        float half = WeatherMath.SNOW_PIXEL_HALF;
        int shape = WeatherMath.SNOW_PIXEL_SHAPE_2;
        float mean = Integer.bitCount(shape) / 25f;
        //Far enough that a texel is under half a pixel, near enough that the sprite is bigger than the pixel: the mean everywhere on it
        float t = 2 * half / WeatherMath.PIXEL_GRID / FRAC[3] * 1.2f;
        float[] a = {0, 0, -t};
        assertEquals(mean, WeatherMath.sprite(new float[]{0, 0, -1}, a, new float[]{half, half}, 0, shape, FRAC[3])[0], 1e-5f);
        //A raindrop thinner than the pixel is widened to it and fainter by as much: its sum across the pixel stays
        float halfWidth = WeatherMath.RAIN_PIXEL_HALF_WIDTH, halfLength = WeatherMath.RAIN_PIXEL_HALF_LENGTH;
        float far = 30;
        float foot = 0.5f * FRAC[3] * far;
        assertTrue(foot > halfWidth);
        float[] c = WeatherMath.sprite(new float[]{0, 0, -1}, new float[]{0, 0, -far}, new float[]{halfWidth, halfLength}, 0, -1, FRAC[3]);
        assertEquals(halfWidth / foot, c[0], 1e-4f);
        assertEquals(far, c[1], 1e-3f);
    }

    @Test
    void pixelRaindropsStandUprightAndTheCamerasMotionDoesNotDrawThemOut() {
        float[] pixelRain = {WeatherMath.WEATHER_TYPE_PIXEL, WeatherMath.WEATHER_TYPE_ROUND, 1, 1};
        float[] flying = WeatherUniforms.motion(WeatherUniforms.RAIN_FALL, new float[]{20, 0, 0}, WeatherMath.RAIN_PERIOD, 3.7, WeatherUniforms.EXPOSURE);
        float[] noSnow = {1, 0, 1, 0.7f};
        int differing = 0;
        for (float az = 0; az < 360; az += 2.1f) {
            for (float el = -6; el <= 10; el += 1.7f) {
                float[] v = dir(az, el);
                float[] still = WeatherMath.pixel(v, 1e30f, CAMERA, FRAC, noSnow, RAIN, SNOW, pixelRain, open(false), WHITE_LIGHT);
                float[] moving = WeatherMath.pixel(v, 1e30f, CAMERA, FRAC, noSnow, flying, SNOW, pixelRain, open(false), WHITE_LIGHT);
                assertArrayEquals(still, moving, 1e-6f);
                float[] round = WeatherMath.pixel(v, 1e30f, CAMERA, FRAC, noSnow, flying, SNOW, new float[]{0, 0, 1, 1}, open(false), WHITE_LIGHT);
                if (Math.abs(round[3] - moving[3]) > 1e-4f) differing++;
            }
        }
        assertTrue(differing > 20, "the round streaks do turn with the motion: " + differing);
    }

    @Test
    void bothMixesHalfOfTheDropsOfEachLook() {
        int pixel = 0;
        for (int b = 0; b < 256; b++) {
            float v = b / 255f;
            if (WeatherMath.isPixel(WeatherMath.WEATHER_TYPE_BOTH, v)) pixel++;
            assertTrue(WeatherMath.isPixel(WeatherMath.WEATHER_TYPE_PIXEL, v));
            assertFalse(WeatherMath.isPixel(WeatherMath.WEATHER_TYPE_ROUND, v));
        }
        assertEquals(128, pixel, 8);
        //Each look covers the sky: the mixed snow lies between the two
        WeatherMath.Map snow = open(true);
        float round = meanAlpha(snow, new float[]{0, WeatherMath.WEATHER_TYPE_ROUND, 1, 1});
        float px = meanAlpha(snow, new float[]{0, WeatherMath.WEATHER_TYPE_PIXEL, 1, 1});
        float both = meanAlpha(snow, new float[]{0, WeatherMath.WEATHER_TYPE_BOTH, 1, 1});
        assertTrue(round > 0 && px > 0, round + " / " + px);
        assertTrue(both > Math.min(round, px) * 0.8f && both < Math.max(round, px) * 1.2f, round + " / " + px + " / " + both);
    }

    @Test
    void theSizeScalesTheDropsAndASpriteStaysInItsCell() {
        WeatherMath.Map snow = open(true);
        for (int type : new int[]{WeatherMath.WEATHER_TYPE_ROUND, WeatherMath.WEATHER_TYPE_PIXEL}) {
            float small = meanAlpha(snow, new float[]{0, type, 1, 0.5f});
            float normal = meanAlpha(snow, new float[]{0, type, 1, 1});
            float big = meanAlpha(snow, new float[]{0, type, 1, 2});
            assertTrue(small < normal && normal < big, "type " + type + ": " + small + " / " + normal + " / " + big);
        }
        //The biggest pixel flake in the near layer stays inside the cell's margin, less the sway, even turned
        float scale = net.vulkanmod.visuals.config.ParticleEffect.SIZE_MAX / 100f;
        float limit = (1 * WeatherMath.WEATHER_MARGIN - WeatherMath.SNOW_SWAY) * 0.70710678f;
        assertTrue(WeatherMath.SNOW_PIXEL_HALF * scale * (1 + WeatherMath.WEATHER_SIZE_SPREAD) > limit, "the clamp is needed at the top of the range");
        assertTrue(GLSL.contains("spriteHalf = isSnow ? min(spriteHalf, vec2(max(size*WEATHER_MARGIN - sway, 0.0)*0.70710678))"));
    }
}
