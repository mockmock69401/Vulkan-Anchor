package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-09 (session 5, the user: auroras at night in the Overworld and always in the End, as other shader packs have; seen in the
 * water): aurora.glsl and its mirror AuroraMath, CelestialUniforms' rules, and where the composite and the reflection draw it.
 */
class AuroraTest {
    private static final String GLSL = ShaderFiles.read("post/aurora.glsl");
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp");
    private static final String REFLECTION = ShaderFiles.read("post/reflection.comp");
    private static final PostProcessSettings SETTINGS = PostProcessSettings.from(new VisualsConfig());

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static float[] dir(float azimuthDeg, float elevationDeg) {
        double a = Math.toRadians(azimuthDeg), e = Math.toRadians(elevationDeg);
        return new float[]{(float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e), (float) (-Math.cos(e) * Math.cos(a))};
    }

    private static float lum(float[] c) {
        return PostMath.luminance(c[0], c[1], c[2]);
    }

    /** The sky's mean luminance over a grid of directions above 5 degrees. */
    private static float meanSky(float[] params, int steps) {
        double sum = 0;
        int n = 0;
        for (int az = 0; az < 360; az += 7) {
            for (int el = 5; el <= 85; el += 5) {
                sum += lum(AuroraMath.aurora(dir(az, el), params, steps, 0.5f));
                n++;
            }
        }
        return (float) (sum / n);
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        int found = 0;
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(GLSL);
        while (m.find()) {
            Field f = AuroraMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 40, "constants found: " + found);
        for (Field f : AuroraMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class)) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(GLSL).find(), f.getName() + " missing in the shader");
        }
        assertTrue(GLSL.contains("0x7feb352du") && GLSL.contains("0x846ca68bu"));
        assertTrue(GLSL.contains("vec3 aurora(vec3 dir, vec4 params, vec3 tintLow, vec3 tintMid, vec3 tintTop, int steps, float jitter)"));
        assertFalse(Pattern.compile("(?m)^\\s*(layout|uniform)\\b").matcher(GLSL).find(), "no uniforms: any shader imports it");
        //The literature the model follows is named where it is used
        assertTrue(GLSL.contains("Baranoski") && GLSL.contains("Lawlor and Genetti 2011"));
        //Every drift is a multiple of 1/100 cell per second, so AURORA_TIME_WRAP seconds move each a whole number of noise periods
        for (float drift : new float[]{AuroraMath.AURORA_WARP_DRIFT, AuroraMath.AURORA_CREST_DRIFT, AuroraMath.AURORA_RAY_DRIFT}) {
            float cells = drift * AuroraMath.AURORA_TIME_WRAP / AuroraMath.AURORA_PERIOD;
            assertEquals(Math.round(cells), cells, 1e-3f, "drift " + drift);
        }
    }

    @Test
    void nothingWithoutStrengthOrBelowTheHorizon() {
        float[] on = {1, 100, 0, 0};
        assertArrayEquals(new float[3], AuroraMath.aurora(dir(0, 40), new float[]{0, 100, 0, 0}, 16, 0.5f), 0);
        assertArrayEquals(new float[3], AuroraMath.aurora(dir(0, -10), on, 16, 0.5f), 0);
        assertArrayEquals(new float[3], AuroraMath.aurora(dir(0, 0), on, 16, 0.5f), 0);
        assertArrayEquals(new float[3], AuroraMath.aurora(dir(0, 40), on, 0, 0.5f), 0);
        //Finite and non-negative everywhere above, at any time and jitter
        for (int az = 0; az < 360; az += 13) {
            for (int el = 1; el <= 90; el += 4) {
                for (float t : new float[]{0, 1234.5f, AuroraMath.AURORA_TIME_WRAP - 0.01f}) {
                    float[] c = AuroraMath.aurora(dir(az, el), new float[]{2, t, az % 2, 0}, 16, (az % 10) / 10f);
                    for (float v : c) assertTrue(v >= 0 && Float.isFinite(v), az + " " + el + " " + t);
                }
            }
        }
    }

    /** The sky is the sum of thin curtains: bright crests between dark sky (not a uniform glow), and stronger with the strength. */
    @Test
    void curtainsCoverPartOfTheSkyAndScaleWithTheStrength() {
        float[] p = {1, 300, 0, 0};
        int lit = 0, n = 0;
        float max = 0;
        for (int az = 0; az < 360; az += 3) {
            for (int el = 20; el <= 80; el += 3) {
                float l = lum(AuroraMath.aurora(dir(az, el), p, 16, 0.5f));
                max = Math.max(max, l);
                n++;
                if (l > 0.02f) lit++;
            }
        }
        float share = lit / (float) n;
        assertTrue(share > 0.1f && share < 0.85f, "lit share of the sky: " + share);
        assertTrue(max > 0.08f, "bright crests: " + max);
        float one = meanSky(p, 16), two = meanSky(new float[]{2, 300, 0, 0}, 16);
        assertEquals(2 * one, two, 1e-4f * Math.max(1, two));
        //The reflection's cheaper march sees about the same light on average
        float cheap = meanSky(new float[]{1, 300, 0, 0}, AuroraMath.AURORA_REFLECTION_STEPS);
        assertEquals(one, cheap, 0.35f * one);
    }

    /** Green from the lower border up, the top red-magenta, a violet fringe right at the border; the End violet throughout. */
    @Test
    void theColourFollowsTheHeight() {
        float[] border = AuroraMath.auroraColour(1.02f, 1, false), mid = AuroraMath.auroraColour(1.4f, 1, false), top = AuroraMath.auroraColour(2.6f, 1, false);
        assertTrue(mid[1] > mid[0] && mid[1] > mid[2], "green above the border");
        assertTrue(top[0] > top[1], "red at the top");
        assertTrue(border[2] > mid[2], "the violet fringe");
        assertArrayEquals(new float[3], AuroraMath.auroraColour(0.8f, 1, false), 1e-6f, "nothing below the border");
        float[] end = AuroraMath.auroraColour(1.4f, 1, true);
        assertTrue(end[2] > end[1] && end[0] > end[1], "the End's violet: " + java.util.Arrays.toString(end));
        //Toward the horizon it fades
        float[] p = {1, 300, 0, 0};
        float low = 0, high = 0;
        for (int az = 0; az < 360; az += 5) {
            low += lum(AuroraMath.aurora(dir(az, 2), p, 16, 0.5f));
            high += lum(AuroraMath.aurora(dir(az, 30), p, 16, 0.5f));
        }
        assertTrue(low < 0.5f * high, "faint at the horizon: " + low + " vs " + high);
    }

    /** The wrapped time repeats seamlessly: the sky at t and at t + AURORA_TIME_WRAP is the same. */
    @Test
    void theTimeWrapsWithoutASeam() {
        for (int az = 0; az < 360; az += 45) {
            float[] d = dir(az, 35);
            float[] a = AuroraMath.aurora(d, new float[]{1, 0.25f, 0, 0}, 16, 0.5f);
            float[] b = AuroraMath.aurora(d, new float[]{1, AuroraMath.AURORA_TIME_WRAP + 0.25f, 0, 0}, 16, 0.5f);
            assertArrayEquals(a, b, 2e-3f * Math.max(1, lum(a)));
        }
        //And it moves: a minute later the sky differs
        float diff = 0;
        for (int az = 0; az < 360; az += 15) {
            diff += Math.abs(lum(AuroraMath.aurora(dir(az, 35), new float[]{1, 100, 0, 0}, 16, 0.5f)) - lum(AuroraMath.aurora(dir(az, 35), new float[]{1, 160, 0, 0}, 16, 0.5f)));
        }
        assertTrue(diff > 0.01f, "drifts: " + diff);
    }

    /**
     * CelestialUniforms: Off / End only / Night and End; in the Overworld only at night (by the sun's height) and in clear weather (2026-11-10:
     * gone in rain or snow, fading with the rain level), fuller in cold biomes; always in the End; never in the Nether; nothing with the post-process off. The time wraps, the End flag
     * is z, the shape and moon phase go to the celestial slot.
     */
    @Test
    void theUniformsFollowTheModeTheNightTheWeatherAndTheClimate() {
        float midnight = -1, noon = 1, dusk = -0.1f;
        assertEquals(CelestialUniforms.AURORA_NIGHT_AND_END, new VisualsConfig().aurora, "auroras on by default");
        assertEquals(CelestialUniforms.SHAPE_SQUARE, new VisualsConfig().celestialShape, "square by default (as before)");
        float night = CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, midnight, 0, 0, 1);
        assertEquals(1, night, 1e-6f);
        assertEquals(0, CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, noon, 0, 0, 1), 0);
        float d = CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, dusk, 0, 0, 1);
        assertTrue(d > 0 && d < night, "fades in with the sun's height: " + d);
        assertEquals(0, CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, midnight, 1, 0, 1), 0, "rain or snow hides it");
        assertEquals(0, CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, midnight, CelestialUniforms.RAIN_FADE, 0, 1), 0);
        float turning = CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, midnight, 0.1f, 0, 1);
        assertTrue(turning > 0 && turning < night, "fades as the weather turns: " + turning);
        float t0 = 1, previous = 2;
        for (float r = 0; r <= 1.0001f; r += 0.05f) {
            float a = CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, midnight, r, 0, 1);
            assertTrue(a <= previous + 1e-6f, "falls monotonically with the rain level");
            previous = a;
        }
        assertEquals(0, CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, midnight, 1, 1, 1), 1e-6f, "a storm hides it");
        assertEquals(CelestialUniforms.WARM_SHARE, CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.SUN, midnight, 0, 0, 0), 1e-6f);
        assertEquals(1, CelestialUniforms.climate(0), 0, "snowy");
        assertEquals(0, CelestialUniforms.climate(0.8f), 0.2f, "plains: mostly warm");
        assertEquals(0, CelestialUniforms.climate(2), 0, "desert");
        assertEquals(1, CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.END, noon, 1, 1, 0), 1e-6f, "the End: always");
        assertEquals(0, CelestialUniforms.auroraStrength(SETTINGS, CelestialUniforms.Sky.NONE, midnight, 0, 0, 1), 0, "the Nether: never");
        PostProcessSettings endOnly = settings(c -> c.aurora = CelestialUniforms.AURORA_END_ONLY);
        assertEquals(0, CelestialUniforms.auroraStrength(endOnly, CelestialUniforms.Sky.SUN, midnight, 0, 0, 1), 0);
        assertEquals(1, CelestialUniforms.auroraStrength(endOnly, CelestialUniforms.Sky.END, midnight, 0, 0, 1), 1e-6f);
        PostProcessSettings off = settings(c -> c.aurora = CelestialUniforms.AURORA_OFF);
        assertEquals(0, CelestialUniforms.auroraStrength(off, CelestialUniforms.Sky.END, midnight, 0, 0, 1), 0);
        assertEquals(1.5f, CelestialUniforms.auroraStrength(settings(c -> c.auroraStrength = 150), CelestialUniforms.Sky.END, 0, 0, 0, 0), 1e-6f);
        assertEquals(0, CelestialUniforms.auroraStrength(settings(c -> c.enabled = false), CelestialUniforms.Sky.END, 0, 0, 0, 0), 0);
        //The climate follows over seconds, not at once
        float c1 = CelestialUniforms.follow(0, 1, 1);
        assertTrue(c1 > 0.1f && c1 < 0.4f, "after a second: " + c1);
        assertEquals(1, CelestialUniforms.follow(Float.NaN, 1, 0.016f), 0);
        //The slots
        CelestialUniforms.Values v = CelestialUniforms.compute(settings(c -> c.celestialShape = CelestialUniforms.SHAPE_ROUND), CelestialUniforms.Sky.END, 0, 0, 0, 0,
                11, AuroraMath.AURORA_TIME_WRAP * 3 + 12.5, 7, true);
        assertArrayEquals(new float[]{WaterMath.CELESTIAL_ROUND, 3, 1, 1}, v.celestial(), 0, "the round shape, the moon phase, the sun's and moon's size (100%)");
        //2026-11-11: the size settings, clamped to SIZE_MIN..SIZE_MAX
        CelestialUniforms.Values sized = CelestialUniforms.compute(settings(c -> {
            c.celestialShape = CelestialUniforms.SHAPE_ROUND;
            c.sunSize = 60;
            c.moonSize = 900;
        }), CelestialUniforms.Sky.SUN, 0.5f, 0, 0, 0, 0, 0, 0, false);
        assertEquals(0.6f, sized.celestial()[2], 1e-6f);
        assertEquals(CelestialUniforms.SIZE_MAX / 100f, sized.celestial()[3], 1e-6f);
        assertEquals(CelestialUniforms.SIZE_MIN / 100f, CelestialUniforms.compute(settings(c -> c.sunSize = 0), CelestialUniforms.Sky.SUN, 0.5f, 0, 0, 0, 0, 0, 0, false).celestial()[2], 1e-6f);
        assertEquals(1, v.aurora()[0], 1e-6f);
        assertEquals(12.5f, v.aurora()[1], 1e-3f, "wrapped");
        assertEquals(1, v.aurora()[2], 0, "the End");
        assertTrue(v.aurora()[3] >= 0 && v.aurora()[3] < 1);
        CelestialUniforms.Values day = CelestialUniforms.compute(SETTINGS, CelestialUniforms.Sky.SUN, noon, 0, 0, 1, 0, 100, 7, false);
        assertArrayEquals(new float[4], day.aurora(), 0, "nothing by day: the shaders evaluate nothing");
        assertArrayEquals(new float[4], CelestialUniforms.off().celestial(), 0);
    }

    /**
     * Where it is drawn: on sky pixels before the far clouds go over it (they hide it), in the End's and the no-atmosphere sky, through
     * Snell's window, and in the water's reflected sky with the cheaper march; never evaluated without a strength.
     */
    @Test
    void theCompositeAndTheReflectionDrawIt() {
        assertTrue(COPY.contains("#import <vulkanmod:visuals/post/aurora.glsl>") && REFLECTION.contains("#import <vulkanmod:visuals/post/aurora.glsl>"));
        assertTrue(COPY.contains("layout(location = 46) uniform vec4 celestial;") && COPY.contains("layout(location = 47) uniform vec4 auroraParams;"));
        assertTrue(REFLECTION.contains("layout(location = 27) uniform vec4 celestial;") && REFLECTION.contains("layout(location = 28) uniform vec4 auroraParams;"));
        int sky = COPY.indexOf("+ auroraSky(dir, p);"), clouds = COPY.indexOf("farCloudsOver(a, sky, dir, HAZE_MAX_DISTANCE, p)");
        assertTrue(sky > 0 && clouds > sky, "under the far clouds");
        assertTrue(COPY.contains("return vec4((scene + auroraSky(dir, p))*underwaterExposure(), 0.0);"), "the End's sky");
        assertTrue(COPY.contains("above += auroraSky(r, p);"), "Snell's window");
        assertTrue(COPY.contains("if (!(auroraParams.x > 0.0)) {\n        return vec3(0.0);"));
        int refl = REFLECTION.indexOf("sky += aurora(r, auroraParams, auroraTintLow.rgb, auroraTintMid.rgb, auroraTintTop.rgb, AURORA_REFLECTION_STEPS"), reflClouds = REFLECTION.indexOf("reflectedCloudMarch(world, r, interleavedGradientNoise(vec2(p)))");
        assertTrue(refl > 0 && reflClouds > refl, "in the water, under the reflected clouds");
        //The composite's mirror adds it to the sky: a night sky with the aurora on is brighter than with it off
        float[][] slots = new float[SceneCompositeMath.SLOTS][4];
        slots[SceneCompositeMath.AURORA] = new float[]{1, 300, 0, 0};
        for (int i = 0; i < 3; i++) slots[SceneCompositeMath.AURORA_TINTS + i] = new float[]{1, 1, 1, 0};
        float[] d = dir(0, 40);
        assertEquals(lum(AuroraMath.aurora(d, slots[SceneCompositeMath.AURORA], AuroraMath.AURORA_STEPS, 0)), lum(SceneCompositeMath.auroraSky(slots, d)), 1e-6f);
        slots[SceneCompositeMath.AURORA] = new float[4];
        assertArrayEquals(new float[3], SceneCompositeMath.auroraSky(slots, d), 0);
    }
}
