package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09 (session 2b): the view from inside water (UnderwaterMath mirrors post/underwater.glsl; UnderwaterUniforms its slots). */
class UnderwaterTest {
    private static final String GLSL = ShaderFiles.read("post/underwater.glsl");
    private static final String COMP = ShaderFiles.read("post/scene_copy.comp");
    private static final long SERIAL = 31;
    private static final float NOON = 0, MIDNIGHT = 0.5f;
    private static final float[] WHITE = {1, 1, 1}, UP = {0, 1, 0};
    private static final float[] DAY = {3, 3, 3};

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static final PostProcessSettings DEFAULT = settings(c -> {
    });

    private static AtmosphereFrame frame(float timeOfDay, float rain, AtmosphereFrame.CameraFluid fluid) {
        return new AtmosphereFrame(SERIAL, new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), 50, 63, 0.05f, 0.2f, 0.6f,
                false, false, timeOfDay, rain, 0, null, new float[]{121 / 255f, 167 / 255f, 1}, 256, new float[]{1, 2, 3, 4, 5, 6, 7, 8}, null)
                .withCameraFluid(fluid);
    }

    /** A unit direction rising at `degrees` from straight up (toward +x). */
    private static float[] fromZenith(double degrees) {
        double a = Math.toRadians(degrees);
        return new float[]{(float) Math.sin(a), (float) Math.cos(a), 0};
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        int found = 0;
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(GLSL);
        while (m.find()) {
            Field f = UnderwaterMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 17, "constants found: " + found);
        for (Field f : UnderwaterMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class)) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(GLSL).find(), f.getName() + " missing in the shader");
        }
        for (String fn : new String[]{"float underwaterVisibility(float visibility)", "vec3 underwaterExtinction(float visibility)",
                "vec3 underwaterInscatter(float d, float y, float h0, vec3 light, float visibility)",
                "vec3 underwaterMedium(vec3 colour, float d, float y, float h0, vec3 light, float visibility)",
                "float underwaterSurfaceDistance(float surfaceUp, vec3 dir)", "bool underwaterThroughSurface(float surfaceUp, float t, float dirY, float dist, bool sky)",
                "vec3 underwaterRefract(vec3 d, vec3 n)", "float underwaterReflectance(vec3 d, vec3 n)",
                "vec3 underwaterWindow(vec3 d, vec3 n, vec3 above, vec3 light, float visibility)"}) {
            assertTrue(GLSL.contains(fn), fn);
        }
        //It builds on water.glsl: the body colour the water shows from above, the ripples, Schlick's term
        assertTrue(GLSL.contains("#import <vulkanmod:visuals/post/water.glsl>"));
        assertTrue(GLSL.contains("vec3(WATER_BODY_RED, WATER_BODY_GREEN, WATER_BODY_BLUE)"));
        assertTrue(GLSL.contains("WATER_F0 + (1.0 - WATER_F0)*pow(1.0 - c, FRESNEL_POWER)"));
        //The composite calls it with its slots and the ripples
        for (String call : new String[]{"#import <vulkanmod:visuals/post/underwater.glsl>", "layout(binding = 17) uniform sampler2D beforeTranslucent;",
                "rippleNormal(dir.xz*t, underwaterRippleA, underwaterRippleB, underwaterParams.z, underwaterParams.w, footprint)",
                "underwaterMedium(underwaterWindow(dir, n, above, light, visibility), t, dir.y, h0, light, visibility)",
                "skyColour(air, r) + sunGlow(air, r) + sunDisc(air, r)", "shown = underwaterView(p, size, dir, dist, false, shown);"}) {
            assertTrue(COMP.contains(call), call);
        }
        //The settings screen's range is the shader's
        assertEquals(UnderwaterMath.UNDERWATER_VISIBILITY_MIN * 100, PostProcessSettings.UNDERWATER_VISIBILITY_MIN, 1e-4f);
        assertEquals(UnderwaterMath.UNDERWATER_VISIBILITY_MAX * 100, PostProcessSettings.UNDERWATER_VISIBILITY_MAX, 1e-4f);
    }

    /** Beer-Lambert: nothing over no water, red first and blue last, the visibility setting dividing the extinction. */
    @Test
    void theWaterTakesRedFirstAndTheVisibilitySettingScalesIt() {
        float[] none = {0, 0, 0};
        assertArrayEquals(WHITE, UnderwaterMath.underwaterMedium(WHITE, 0, 0, 5, DAY, 1), 0f);
        float[] prev = WHITE;
        for (float d : new float[]{1, 4, 10, 30, 100, 400}) {
            float[] t = UnderwaterMath.underwaterMedium(WHITE, d, 0, 5, none, 1);
            assertTrue(t[0] < t[1] && t[1] < t[2], "red first, blue last at " + d);
            for (int i = 0; i < 3; i++) assertTrue(t[i] < prev[i], "falls with the distance");
            prev = t;
        }
        //Green and blue keep about 5% after about 75 and 100 blocks, red after 25 (the shader's comment)
        assertEquals(0.05f, UnderwaterMath.underwaterMedium(WHITE, 75f, 0, 0, none, 1)[1], 0.003f);
        assertEquals(0.05f, UnderwaterMath.underwaterMedium(WHITE, 100f, 0, 0, none, 1)[2], 0.003f);
        assertEquals(0.05f, UnderwaterMath.underwaterMedium(WHITE, 25f, 0, 0, none, 1)[0], 0.003f);
        //Twice the visibility: the same share twice as far; the range clamps, NaN is 1
        for (int i = 0; i < 3; i++) {
            assertEquals(UnderwaterMath.underwaterMedium(WHITE, 10, 0, 0, none, 1)[i], UnderwaterMath.underwaterMedium(WHITE, 20, 0, 0, none, 2)[i], 1e-6f);
        }
        assertEquals(UnderwaterMath.UNDERWATER_VISIBILITY_MIN, UnderwaterMath.underwaterVisibility(0), 0f);
        assertEquals(UnderwaterMath.UNDERWATER_VISIBILITY_MAX, UnderwaterMath.underwaterVisibility(10), 0f);
        assertEquals(1f, UnderwaterMath.underwaterVisibility(Float.NaN), 0f);
        //Sky pixels' longest path: finite, nothing of the colour left
        float[] far = UnderwaterMath.underwaterMedium(new float[]{50, 50, 50}, Float.POSITIVE_INFINITY, 0, 0, DAY, 1);
        for (float c : far) assertTrue(Float.isFinite(c) && c >= 0);
        assertArrayEquals(UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, 0, 0, DAY, 1), far, 1e-5f);
    }

    /**
     * The water's own colour: far away horizontally it is water.glsl's body colour (the colour deep water shows from above) in the light
     * at the camera's depth; looking down it is darker than looking up; deeper down every direction darkens; it scales with the light.
     */
    @Test
    void theWatersColourIsTheBodyColourDarkeningWithDepthAndDownward() {
        float[] body = {WaterMath.WATER_BODY_RED, WaterMath.WATER_BODY_GREEN, WaterMath.WATER_BODY_BLUE};
        float[] k = UnderwaterMath.underwaterDiffuse();
        for (float h0 : new float[]{0, 5, 11, 30}) {
            float[] level = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, 0, h0, DAY, 1);
            for (int i = 0; i < 3; i++) assertEquals(body[i] * DAY[i] * (float) Math.exp(-k[i] * h0), level[i], 1e-5f, "level view at depth " + h0);
            float[] down = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, -0.8f, h0, DAY, 1);
            //Up to the surface h0 blocks above (the path ends there)
            float[] up = UnderwaterMath.underwaterInscatter(Math.max(h0, 0.01f) / 0.8f, 0.8f, h0, DAY, 1);
            float[] upFar = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, 0.8f, h0, DAY, 1);
            for (int i = 0; i < 3; i++) {
                assertTrue(down[i] < level[i] && level[i] < upFar[i], "darker downward at " + h0);
                assertTrue(up[i] <= upFar[i] + 1e-6f);
            }
        }
        //U1 (about 9 blocks down) to U3 (about 22): the blue darkens to about two thirds, as the references' (Complementary 0.65, SEUS 0.7)
        float u1 = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, 0, 9, DAY, 1)[2];
        float u3 = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, 0, 22, DAY, 1)[2];
        assertEquals(0.68f, u3 / u1, 0.03f);
        //Proportional to the light (night is dark)
        float[] half = UnderwaterMath.underwaterInscatter(30, -0.3f, 4, new float[]{DAY[0] / 2, DAY[1] / 2, DAY[2] / 2}, 1);
        float[] full = UnderwaterMath.underwaterInscatter(30, -0.3f, 4, DAY, 1);
        for (int i = 0; i < 3; i++) assertEquals(full[i] / 2, half[i], 1e-6f);
        //Bluer far away: blue keeps most of its light, red is nearly gone
        float[] c = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, 0, 9, DAY, 1);
        assertTrue(c[2] > c[1] && c[1] > c[0]);
    }

    /**
     * Snell's window: straight up the surface lets nearly everything through (reflectance WATER_F0) and the sky is seen straight up; the
     * view bends away from the vertical so the horizon lies on the window's rim at the critical angle (about 48.6 degrees); the
     * reflectance rises with the angle to 1 there (no seam at the rim) and stays 1 beyond (total internal reflection).
     */
    @Test
    void snellsWindowSqueezesTheSkyAndMirrorsTheWaterBeyondIt() {
        assertEquals(WaterMath.WATER_F0, UnderwaterMath.underwaterReflectance(UP, UP), 1e-6f);
        assertArrayEquals(UP, UnderwaterMath.underwaterRefract(UP, UP), 1e-6f);
        double critical = Math.toDegrees(Math.asin(1 / UnderwaterMath.UNDERWATER_IOR));
        assertEquals(48.6, critical, 0.1);
        //40 degrees under water leaves at about 59 in the air; 48 at about 82 (near the horizon)
        assertEquals(59.0, Math.toDegrees(Math.acos(UnderwaterMath.underwaterRefract(fromZenith(40), UP)[1])), 0.5);
        assertTrue(Math.toDegrees(Math.acos(UnderwaterMath.underwaterRefract(fromZenith(48), UP)[1])) > 80);
        float last = 0;
        for (double a = 0; a < critical - 0.05; a += 0.5) {
            float f = UnderwaterMath.underwaterReflectance(fromZenith(a), UP);
            assertTrue(f >= last, "rises with the angle at " + a);
            float[] r = UnderwaterMath.underwaterRefract(fromZenith(a), UP);
            assertEquals(1f, UnderwaterMath.dot(r, r), 1e-4f);
            last = f;
        }
        assertTrue(UnderwaterMath.underwaterReflectance(fromZenith(critical - 0.05), UP) > 0.7f, "most of it reflected at the rim");
        for (double a : new double[]{critical + 0.05, 55, 70, 89}) {
            assertEquals(1f, UnderwaterMath.underwaterReflectance(fromZenith(a), UP), 0f, "total internal reflection at " + a);
            assertArrayEquals(new float[3], UnderwaterMath.underwaterRefract(fromZenith(a), UP), 0f);
        }
        //Through the window the sky comes in brighter by n^2 (less the reflected share); beyond it, the water below mirrored
        float[] sky = {0.5f, 0.7f, 1.0f};
        float[] zenith = UnderwaterMath.underwaterWindow(UP, UP, sky, DAY, 1);
        float[] mirroredDown = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, -1, 0, DAY, 1);
        for (int i = 0; i < 3; i++) {
            assertEquals(sky[i] * UnderwaterMath.UNDERWATER_WINDOW_GAIN * (1 - WaterMath.WATER_F0) + mirroredDown[i] * WaterMath.WATER_F0, zenith[i], 1e-5f);
        }
        float[] beyond = UnderwaterMath.underwaterWindow(fromZenith(60), UP, sky, DAY, 1);
        float[] mirrored = UnderwaterMath.underwaterInscatter(UnderwaterMath.UNDERWATER_LONGEST, -0.5f, 0, DAY, 1);
        assertArrayEquals(mirrored, beyond, 1e-5f);
        //The window is far brighter than the mirrored water around it
        assertTrue(zenith[2] > 3 * beyond[2]);
    }

    /** The ripples (water.glsl's world-anchored normals) bend the view by place, so the window and the sky in it wobble. */
    @Test
    void theRipplesWobbleTheWindow() {
        float[] offsets = {1, 2, 3, 4, 5, 6, 7, 8};
        float[] d = fromZenith(30);
        double spread = 0;
        float[] first = null;
        for (int i = 0; i < 16; i++) {
            float[] n = WaterMath.rippleNormal(0.37f * i, 0.11f * i, offsets, 1, 1, 0.002f);
            float[] r = UnderwaterMath.underwaterRefract(d, n);
            if (first == null) first = r;
            spread = Math.max(spread, Math.acos(Math.min(1, UnderwaterMath.dot(first, r))));
        }
        assertTrue(Math.toDegrees(spread) > 1, "the refracted view turns by place: " + Math.toDegrees(spread));
        assertTrue(Math.toDegrees(spread) < 20, "but stays a ripple: " + Math.toDegrees(spread));
        //Near the rim a ripple flips a view between the window and the mirror: the rim is wavy
        float[] nearRim = fromZenith(47.5);
        boolean in = false, out = false;
        for (int i = 0; i < 64; i++) {
            float[] n = WaterMath.rippleNormal(0.23f * i, 0.07f * i, offsets, 1, 1, 0.002f);
            if (UnderwaterMath.underwaterReflectance(nearRim, n) >= 1f) out = true;
            else in = true;
        }
        assertTrue(in && out, "the rim moves with the ripples");
    }

    /** The surface: where the view rises to it, and which pixels lie on or behind it (the water's face itself is there). */
    @Test
    void aPixelIsSeenThroughTheSurfaceWhenNothingStandsBeforeIt() {
        float[] up45 = fromZenith(45);
        float t = UnderwaterMath.underwaterSurfaceDistance(3, up45);
        assertEquals(3 / (float) Math.cos(Math.toRadians(45)), t, 1e-5f);
        assertEquals(-1f, UnderwaterMath.underwaterSurfaceDistance(0, up45), 0f, "no open surface above");
        assertEquals(-1f, UnderwaterMath.underwaterSurfaceDistance(3, new float[]{1, 0, 0}), 0f, "a level view never rises to it");
        assertEquals(-1f, UnderwaterMath.underwaterSurfaceDistance(3, new float[]{0.6f, -0.8f, 0}), 0f, "nor one looking down");
        float y = up45[1];
        assertTrue(UnderwaterMath.underwaterThroughSurface(3, t, y, t, false), "the water's own face");
        assertTrue(UnderwaterMath.underwaterThroughSurface(3, t, y, t * 0.99f, false), "within the depth's slack");
        assertTrue(UnderwaterMath.underwaterThroughSurface(3, t, y, 0, true), "the sky");
        assertTrue(UnderwaterMath.underwaterThroughSurface(3, t, y, t * 3, false), "land behind it");
        assertFalse(UnderwaterMath.underwaterThroughSurface(3, t, y, t * 0.9f, false), "kelp in front of it");
        assertFalse(UnderwaterMath.underwaterThroughSurface(0, -1, y, 100, false), "no open surface");
        //At grazing views the surface's distance runs away from pixel to pixel; its height does not: the water's face 3% short at 4% rise
        // (37 blocks out from 1.5 under the surface) still counts, a kelp tip half a block below the surface there does not
        float grazing = 0.04f, far = UnderwaterMath.underwaterSurfaceDistance(1.5f, new float[]{0, grazing, 1});
        assertTrue(UnderwaterMath.underwaterThroughSurface(1.5f, far, grazing, far * 0.97f, false));
        assertFalse(UnderwaterMath.underwaterThroughSurface(1.5f, far, grazing, (1.5f - 0.5f) / grazing, false));
    }

    /**
     * UnderwaterUniforms: zeros out of water; in water the open surface, the settings, the ripples and the air's sky (W4's SKY mode as if
     * in the open air while the composite's own atmosphere is the environmental fog); the daylight by the time of day, rain and a
     * capped column's sky light.
     */
    @Test
    void theUniformsCarryTheSurfaceTheDaylightAndTheAirsSky() {
        UnderwaterUniforms.Values dry = UnderwaterUniforms.compute(DEFAULT, frame(NOON, 0, AtmosphereFrame.CameraFluid.NONE), SERIAL, true, 4, 1, true);
        assertEquals(0f, dry.surfaceUp(), 0f);
        assertArrayEquals(new float[3], dry.light(), 0f);
        assertEquals(0f, dry.visibility(), 0f, "off: the composite shows the view as before");
        //Fog strength 0 leaves vanilla's water: no view of our own, vanilla's fog kept (every setting at 0 is vanilla's image)
        AtmosphereFrame wetNoon = frame(NOON, 0, AtmosphereFrame.CameraFluid.WATER);
        assertEquals(0f, UnderwaterUniforms.compute(settings(c -> c.fogStrength = 0), wetNoon, SERIAL, true, 4, 1, true).visibility(), 0f);
        assertTrue(UnderwaterUniforms.drawn(settings(c -> c.fogStrength = 1)));
        assertFalse(UnderwaterUniforms.drawn(settings(c -> c.fogStrength = 0)));
        assertFalse(UnderwaterUniforms.drawn(settings(c -> c.enabled = false)));
        AtmosphereFrame noon = frame(NOON, 0, AtmosphereFrame.CameraFluid.WATER);
        assertTrue(noon.fogCoversAllRendering(), "the composite's own atmosphere under water is the environmental fog");
        UnderwaterUniforms.Values wet = UnderwaterUniforms.compute(settings(c -> {
            c.underwaterVisibility = 150;
            c.waterRippleDensity = 50;
        }), noon, SERIAL, true, 6.3f, 0.2f, true);
        assertEquals(6.3f, wet.surfaceUp(), 0f);
        assertEquals(1.5f, wet.visibility(), 1e-6f);
        assertEquals(0.5f, wet.density(), 1e-6f);
        assertEquals(1f, wet.ripples(), 1e-6f);
        assertTrue(wet.beforeValid());
        assertArrayEquals(new float[]{1, 2, 3, 4, 5, 6, 7, 8}, wet.offsets(), 0f);
        assertEquals(AtmosphereMath.ATMOSPHERE_SKY, AtmosphereMath.Atmosphere.of(wet.air()).mode(), 0f);
        assertArrayEquals(AtmosphereUniforms.block(DEFAULT, frame(NOON, 0, AtmosphereFrame.CameraFluid.NONE), true), wet.air(), 1e-6f);
        //Noon's light is about DAY_LIGHT, white-ish; midnight's under a tenth (moonlight 50%); full rain dims it
        float noonL = PostMath.luminance(wet.light()[0], wet.light()[1], wet.light()[2]);
        assertEquals(UnderwaterUniforms.DAY_LIGHT, noonL, 0.1f);
        float[] mid = UnderwaterUniforms.compute(DEFAULT, frame(MIDNIGHT, 0, AtmosphereFrame.CameraFluid.WATER), SERIAL, true, 6.3f, 1, true).light();
        assertTrue(PostMath.luminance(mid[0], mid[1], mid[2]) < 0.1f * noonL);
        assertEquals(UnderwaterUniforms.NIGHT_LIGHT * 0.5f, PostMath.luminance(mid[0], mid[1], mid[2]), 1e-4f);
        float[] rain = UnderwaterUniforms.compute(DEFAULT, frame(NOON, 1, AtmosphereFrame.CameraFluid.WATER), SERIAL, true, 6.3f, 1, true).light();
        assertEquals(noonL * (1 - UnderwaterUniforms.RAIN_DIM), PostMath.luminance(rain[0], rain[1], rain[2]), 0.02f);
        //A capped column (no open surface): no window, the light by the camera's sky light
        UnderwaterUniforms.Values cave = UnderwaterUniforms.compute(DEFAULT, noon, SERIAL, true, 0, 0.25f, false);
        assertEquals(0f, cave.surfaceUp(), 0f);
        for (int i = 0; i < 3; i++) assertEquals(wet.light()[i] * 0.25f, cave.light()[i], 1e-5f);
        //Without sky light no sky for the window (the composite then shows vanilla's pixel)
        assertEquals(AtmosphereMath.ATMOSPHERE_NONE, AtmosphereMath.Atmosphere.of(UnderwaterUniforms.compute(DEFAULT, noon, SERIAL, false, 3, 1, true).air()).mode(), 0f);
        //Another frame's: off
        assertEquals(0f, UnderwaterUniforms.compute(DEFAULT, noon, SERIAL + 1, true, 4, 1, true).surfaceUp(), 0f);
        //Vanilla's fog is pushed out of the way while the composite draws the water, and kept as it is otherwise
        assertEquals(96 * 64f, UnderwaterFog.end(96, true, true), 1e-3f);
        assertEquals(96f, UnderwaterFog.end(96, true, false), 0f);
        assertEquals(96f, UnderwaterFog.end(96, false, true), 0f);
    }
}
