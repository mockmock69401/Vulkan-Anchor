package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.AtmosphereMath.Atmosphere;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W6: water colour, reflectivity, ripples, the discs, the reflection's uniforms (WaterMath, RippleMath and ReflectionUniforms mirror the shaders). */
class WaterTest {
    private static final long SERIAL = 21;
    private static final PostProcessSettings SETTINGS = PostProcessSettings.from(new VisualsConfig());
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f, true);
    private static final float NOON = 0, MIDNIGHT = 0.5f;
    /** The sun at height 0.17 in the west (S2's sunset, about 10 degrees up). */
    private static final float SUNSET = (float) (Math.acos(0.17) / (2 * Math.PI));
    private static final String GLSL = ShaderFiles.read("post/water.glsl");
    private static final String COMP = ShaderFiles.read("post/reflection.comp");

    private static PostProcessSettings settings(Consumer<VisualsConfig> change) {
        VisualsConfig c = new VisualsConfig();
        change.accept(c);
        return PostProcessSettings.from(c);
    }

    private static AtmosphereFrame frame(float timeOfDay, float rain, float[] sky, float[] ripple) {
        return new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(PROJ), 70, 63,
                0.6f, 0.7f, 0.9f, false, false, timeOfDay, rain, 0, null, sky, 256, ripple, null);
    }

    private static Atmosphere atmosphere(float timeOfDay, float rain, boolean hasSky) {
        return Atmosphere.of(AtmosphereUniforms.block(SETTINGS, frame(timeOfDay, rain, hasSky ? new float[]{0.47f, 0.65f, 1} : null, null), hasSky));
    }

    private static float[] dir(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new float[]{(float) (x / l), (float) (y / l), (float) (z / l)};
    }

    private static float lum(float[] c) {
        return PostMath.luminance(c[0], c[1], c[2]);
    }

    private static boolean finite(float[] c) {
        for (float v : c) if (!Float.isFinite(v) || v < 0) return false;
        return true;
    }

    // ---- 1. reflectivity ----

    @Test
    void reflectivityIsLowestLookingDownFullAtGrazingAndMonotonicAndIceGlassReflectLess() {
        float water = WaterMath.reflectionMark(3.5f), glass = WaterMath.reflectionMark(-1f);
        assertTrue(water > 0 && glass < 0, "signs follow boundary 3.2");
        assertEquals(0, WaterMath.reflectionMark(0), 0);
        assertEquals(0, WaterMath.reflectionMark(Float.NaN), 0);
        float prevWater = -1, prevGlass = -1;
        for (int i = 1000; i >= 0; i--) {
            float cos = i / 1000f;
            float w = WaterMath.reflectivity(water, cos), g = WaterMath.reflectivity(glass, cos);
            assertTrue(w >= prevWater && g >= prevGlass, "monotonic toward grazing at cos " + cos);
            assertTrue(g < w, "ice/glass below water at cos " + cos);
            assertTrue(w >= WaterMath.reflectivity(water, 1) && g >= WaterMath.reflectivity(glass, 1), "lowest looking straight down");
            prevWater = w;
            prevGlass = g;
        }
        assertEquals(WaterMath.WATER_F0, WaterMath.reflectivity(water, 1), 1e-6f);
        assertEquals(1, WaterMath.reflectivity(water, 0), 1e-6f, "full at grazing");
        assertTrue(WaterMath.reflectivity(water, 0.05f) > 0.75f, "approaches full near grazing");
        assertEquals(0, WaterMath.reflectivity(0, 0.3f), 0);
        assertTrue(Float.isFinite(WaterMath.reflectivity(water, Float.NaN)) && Float.isFinite(WaterMath.reflectivity(Float.NaN, 0.5f)));
    }

    // ---- 2. water colour ----

    @Test
    void theTintsTransmittanceIsInZeroOneAndNeverIncreasesWithLength() {
        float[] prev = {1, 1, 1};
        for (float len = 0; len < 2000; len += 0.37f) {
            float[] t = WaterMath.waterTransmittance(len);
            for (int c = 0; c < 3; c++) {
                assertTrue(t[c] >= 0 && t[c] <= 1, "in [0, 1] at " + len);
                assertTrue(t[c] <= prev[c], "not increasing at " + len);
            }
            assertTrue(t[0] <= t[1] && t[1] <= t[2], "red goes first");
            prev = t;
        }
        for (float bad : new float[]{Float.NaN, -3, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] t = WaterMath.waterTransmittance(bad);
            for (float v : t) assertTrue(v >= 0 && v <= 1, "" + bad);
        }
        //Length 0 (or the water depth setting 0) gives the pixel back; deep water tends to the body colour times the light
        float[] behind = {0.4f, 0.3f, 0.2f};
        assertArrayEquals(behind, WaterMath.waterColour(behind, 0, new float[]{1, 1, 1}), 0);
        float[] deep = WaterMath.waterColour(behind, 1024, new float[]{1, 1, 1});
        assertArrayEquals(new float[]{WaterMath.WATER_BODY_RED, WaterMath.WATER_BODY_GREEN, WaterMath.WATER_BODY_BLUE}, deep, 1e-4f);
        assertTrue(deep[2] > deep[1] && deep[1] > deep[0], "deep water is blue");
        //Shallow (2 blocks): the floor still shows (S14)
        float[] shallow = WaterMath.waterTransmittance(2);
        assertTrue(shallow[2] > 0.8f && shallow[0] > 0.5f, "a shallow floor is seen");
        //2026-10-28: the terrain draws water at WaterAlpha of its texture's opacity: vanilla's with the visuals off or water depth 0
        assertEquals(1f, WaterMath.surfaceAlpha(false, 1), 0);
        assertEquals(1f, WaterMath.surfaceAlpha(true, 0), 0);
        assertEquals(1f, WaterMath.surfaceAlpha(true, Float.NaN), 0);
        assertEquals(WaterMath.WATER_SURFACE_ALPHA, WaterMath.surfaceAlpha(true, 1), 1e-6f);
        assertEquals(WaterMath.WATER_SURFACE_ALPHA, WaterMath.surfaceAlpha(true, 2), 1e-6f);
        assertTrue(WaterMath.surfaceAlpha(true, 0.5f) < 1 && WaterMath.surfaceAlpha(true, 0.5f) > WaterMath.WATER_SURFACE_ALPHA);
        assertEquals(247, LightingMath.alphaCode(FoliageMarker.WATER_ALPHA));
        //2026-10-28: a floor 3 blocks down seen 25 degrees below the horizon (about 7 blocks of water) still shows, as in Complementary
        float[] lake = WaterMath.waterTransmittance(3 / (float) Math.sin(Math.toRadians(25)));
        assertTrue(lake[0] > 0.45f && lake[1] > 0.75f && lake[2] > 0.8f, java.util.Arrays.toString(lake));
        //ambient: 1 by day, lower at night and in full rain, monotonic
        assertEquals(1, WaterMath.ambient(1), 0);
        assertEquals(WaterMath.WATER_NIGHT_AMBIENT, WaterMath.ambient(0), 0);
        for (float d = 0; d < 1; d += 0.05f) assertTrue(WaterMath.ambient(d + 0.05f) >= WaterMath.ambient(d));
        assertEquals(WaterMath.WATER_NIGHT_AMBIENT, WaterMath.ambient(Float.NaN), 0);
    }

    // ---- 3. ripples ----

    @Test
    void rippleNormalsAreUnitLengthAndWorldAnchored() {
        double[] phase = {3.7, 120.2, 239.9, 0.01, 77, 5};
        double[][] cameras = {{0, 0}, {12.5, -3.25}, {30000.37, -29999.81}, {-1e6 + 0.3, 2e6 + 0.7}, {239.99, 480.01}};
        for (double wx = -40; wx < 40; wx += 3.3) {
            for (double wz = -25; wz < 25; wz += 2.9) {
                float[] first = null;
                for (double[] cam : cameras) {
                    float[] o = RippleMath.offsets(cam[0], cam[1], phase);
                    //The world point (cam - cam mod PERIOD) + w: the same point up to whole periods, near every camera
                    float relX = (float) (wx - (cam[0] - Math.floor(cam[0] / RippleMath.PERIOD) * RippleMath.PERIOD));
                    float relZ = (float) (wz - (cam[1] - Math.floor(cam[1] / RippleMath.PERIOD) * RippleMath.PERIOD));
                    float[] n = WaterMath.rippleNormal(relX, relZ, o, 1, 1, 0.01f);
                    assertEquals(1, WaterMath.length(n), 1e-5f, "unit length");
                    assertTrue(n[1] > 0.9f, "a ripple tilts gently");
                    if (first == null) first = n;
                    else assertArrayEquals(first, n, 2e-3f, "world anchored at " + wx + ", " + wz);
                }
            }
        }
    }

    @Test
    void theSameWorldPointGivesTheSameNormalFromCamerasFarApart() {
        double[] phase = {10, 20, 30, 40, 50, 60};
        //One world point seen from cameras up to 60 blocks away from it, at world coordinates around 30000
        double px = 30012.25, pz = -29987.5;
        float[] first = null;
        for (double[] cam : new double[][]{{30000.1, -30000.2}, {30050.7, -29950.3}, {29960.0, -29990.0}, {30012.0, -29987.0}}) {
            float[] n = WaterMath.rippleNormal((float) (px - cam[0]), (float) (pz - cam[1]), RippleMath.offsets(cam[0], cam[1], phase), 1, 1, 0.01f);
            if (first == null) first = n;
            else assertArrayEquals(first, n, 2e-3f);
        }
        //And the pattern moves with the flow (the phase), not with the camera
        float[] moved = WaterMath.rippleNormal((float) (px - 30000.1), (float) (pz + 30000.2),
                RippleMath.offsets(30000.1, -30000.2, new double[]{10.5, 20, 30, 40, 50, 60}), 1, 1, 0.01f);
        assertFalse(java.util.Arrays.equals(first, moved), "a phase step moves the ripples");
    }

    @Test
    void ripplesFadeToFlatWhereTheyWouldSpanFewPixelsAndStrengthZeroIsAMirror() {
        float[] o = RippleMath.offsets(5.5, 7.25, new double[]{1, 2, 3, 4, 5, 6});
        float[] up = {0, 1, 0};
        for (float x = -30; x < 30; x += 1.7f) {
            //strength 0: a flat mirror
            assertArrayEquals(up, WaterMath.rippleNormal(x, x * 0.7f, o, 0, 1, 0.01f), 0);
            //Every octave's cell across its crests under RIPPLE_FADE_NONE pixels: flat (far water, or grazing views: footprint = bpp / cos)
            float footprint = WaterMath.rippleCellAcross(0, 1) / RippleMath.RIPPLE_FADE_NONE;
            assertArrayEquals(up, WaterMath.rippleNormal(x, x * 0.7f, o, 1, 1, footprint), 0);
            //The glints turn back into mirrored discs once the longest crests (the first octave's along them) span too few pixels
            float longest = RippleMath.RIPPLE_CELL_0 * RippleMath.RIPPLE_STRETCH_0 / RippleMath.RIPPLE_FADE_NONE;
            assertEquals(0, WaterMath.rippleAmount(1, 1, longest), 0);
            assertTrue(WaterMath.rippleAmount(1, 1, footprint) > 0);
        }
        //The fade grows with the pixels per cell, and the smaller octaves fade first
        float prev = -1;
        for (float f = 10; f > 0.001f; f *= 0.8f) {
            float v = WaterMath.rippleFade(RippleMath.RIPPLE_CELL_0, f);
            assertTrue(v >= prev);
            assertTrue(WaterMath.rippleFade(RippleMath.RIPPLE_CELL_3, f) <= v);
            prev = v;
        }
        assertEquals(1, WaterMath.rippleFade(RippleMath.RIPPLE_CELL_3, 0.02f), 0, "near water ripples fully");
        //A grazing view: the same distance's footprint over cos 0.03 flattens what is visible looking down
        float bpp = 0.1f;
        assertTrue(WaterMath.rippleAmount(1, 1, bpp) > 0.9f);
        assertEquals(0, WaterMath.rippleAmount(1, 1, bpp / 0.03f), 1e-6f);
        //Periodic: one period away on x, on z or both is the same normal (the offsets wrap there), at every density: the turned lattices
        // have whole numbers of cells per period
        for (float d : new float[]{0.25f, 0.6f, 1, 1.37f, 2}) {
            for (float x = -10; x < 10; x += 0.9f) {
                float[] n = WaterMath.rippleNormal(x, 3, o, 1, d, 0.01f);
                assertArrayEquals(n, WaterMath.rippleNormal(x + RippleMath.RIPPLE_PERIOD, 3, o, 1, d, 0.01f), 2e-3f, "x at density " + d);
                assertArrayEquals(n, WaterMath.rippleNormal(x, 3 - RippleMath.RIPPLE_PERIOD, o, 1, d, 0.01f), 2e-3f, "z at density " + d);
                assertArrayEquals(n, WaterMath.rippleNormal(x - RippleMath.RIPPLE_PERIOD, 3 + RippleMath.RIPPLE_PERIOD, o, 1, d, 0.01f), 2e-3f,
                        "x and z at density " + d);
            }
        }
    }

    // ---- 4. the fallback without sky light ----

    @Test
    void withNoSkyLightTheFallbackHasNoNaNOrInfinity() {
        List<Atmosphere> atmospheres = List.of(atmosphere(NOON, 0, false), atmosphere(MIDNIGHT, 1, false), atmosphere(NOON, 0, true),
                atmosphere(SUNSET, 0, true), atmosphere(MIDNIGHT, 0.5f, true), Atmosphere.of(new float[28]));
        float[][] dirs = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, dir(1, 0.0001, 0), dir(-1, 0.02, 0.3), dir(0, 0.3, -1), {0, 0, 1}};
        float[] own = {0.02f, 0.05f, 0.08f};
        for (Atmosphere a : atmospheres) {
            for (float[] r : dirs) {
                for (float access : new float[]{0, 0.5f, 1, Float.NaN}) {
                    for (float vis : new float[]{0, 1, Float.NaN}) {
                        for (float dist : new float[]{0, 30, 5000, Float.NaN, Float.POSITIVE_INFINITY}) {
                            for (float open : new float[]{0, 0.5f, 1, Float.NaN}) {
                                float[] c = WaterMath.missedRay(a, dir(0.2, -0.5, 1), dist, 1, r, 0.6f, vis, open, own, access);
                                assertTrue(finite(c), "finite and non-negative");
                            }
                        }
                    }
                }
                //No sky access (a cave, no sky light): the reflection is the water's own colour
                assertArrayEquals(own, WaterMath.missedRay(a, dir(0.2, -0.5, 1), 30, 1, r, 0.6f, 1, 1, own, 0), 1e-7f);
            }
        }
    }

    // ---- the discs (boundary 6: the composite draws the sun disc; W4's shafts need it above SHAFT_THRESHOLD) ----

    @Test
    void theSunDiscIsASquareAtTheDiscLuminanceThatTheShaftsSee() {
        for (float time : new float[]{NOON, SUNSET, 0.1f}) {
            Atmosphere a = atmosphere(time, 0, true);
            float[] sun = a.sun();
            float[] centre = WaterMath.sunDisc(a, sun, WaterMath.SQUARE);
            assertEquals(PostMath.SUN_DISC_LUMINANCE, lum(centre), 0.5f, "luminance at time " + time);
            assertTrue(lum(centre) > AtmosphereMath.SHAFT_THRESHOLD * 10);
            //A square: the corner direction (both axes at 0.95 h) is inside, 1.1 h on one axis is outside
            float[] axis = WaterMath.scale(new float[]{-sun[0] * sun[2], -sun[1] * sun[2], 1 - sun[2] * sun[2]}, 1);
            axis = WaterMath.scale(axis, 1 / WaterMath.length(axis));
            float[] side = WaterMath.cross(axis, sun);
            float h = WaterMath.SUN_HALF_SIZE;
            float[] corner = WaterMath.add(sun, WaterMath.add(WaterMath.scale(axis, 0.95f * h), WaterMath.scale(side, 0.95f * h)));
            float[] out = WaterMath.add(sun, WaterMath.scale(side, 1.1f * h));
            assertEquals(1, WaterMath.discCover(WaterMath.scale(corner, 1 / WaterMath.length(corner)), sun, h), 1e-4f);
            assertEquals(0, WaterMath.discCover(WaterMath.scale(out, 1 / WaterMath.length(out)), sun, h), 0);
            assertArrayEquals(new float[3], WaterMath.sunDisc(a, new float[]{-sun[0], -sun[1], -sun[2]}, WaterMath.SQUARE), 0, "none behind");
            assertArrayEquals(new float[3], WaterMath.discGlint(a, new float[]{-sun[0], -sun[1], -sun[2]}, 0, 1, WaterMath.SQUARE), 0, "no moon by day");
        }
        //Rain hides both, no sky has neither, the moon shows at night
        assertTrue(lum(WaterMath.sunDisc(atmosphere(NOON, 1, true), new float[]{0, 1, 0}, WaterMath.SQUARE)) < 0.5f, "full rain");
        assertArrayEquals(new float[3], WaterMath.sunDisc(atmosphere(NOON, 0, false), new float[]{0, 1, 0}, WaterMath.SQUARE), 0);
        Atmosphere night = atmosphere(MIDNIGHT, 0, true);
        float[] moon = {-night.sun()[0], -night.sun()[1], -night.sun()[2]};
        assertTrue(lum(WaterMath.discGlint(night, moon, 0, 0, WaterMath.SQUARE)) > 0.3f, "the moon at night, unshadowed by the sun's maps");
        assertArrayEquals(new float[3], WaterMath.sunDisc(night, night.sun(), WaterMath.SQUARE), 0, "no sun at night");
    }

    /** A unit direction off centre by (along the rotation axis, along the path) in the disc's plane. */
    private static float[] offDisc(float[] centre, float u, float v) {
        float[] axis = {-centre[0] * centre[2], -centre[1] * centre[2], 1 - centre[2] * centre[2]};
        axis = WaterMath.scale(axis, 1 / WaterMath.length(axis));
        float[] side = WaterMath.cross(axis, centre);
        float[] d = WaterMath.add(centre, WaterMath.add(WaterMath.scale(axis, u), WaterMath.scale(side, v)));
        return WaterMath.scale(d, 1 / WaterMath.length(d));
    }

    private static float[] round(int phase) {
        return new float[]{WaterMath.CELESTIAL_ROUND, phase, 0, 0};
    }

    /**
     * 2026-11-09 (session 5, the user: "an option to make the sun and moon round"): the round sun is a circle of the square's half size
     * (the square's corners are outside, the same radius along the axis and the diagonal is the same), limb-darkened around a mean of 1 so
     * its middle is brighter than its rim and both stay above the shafts' threshold; the square slot draws exactly the old square.
     */
    @Test
    void theRoundSunIsACircleWithADarkenedRim() {
        Atmosphere a = atmosphere(SUNSET, 0, true);
        float[] sun = a.sun();
        float h = WaterMath.SUN_ROUND_RADIUS;
        float[] cel = round(0);
        assertEquals(0, WaterMath.sunShape(offDisc(sun, 0.9f * h, 0.9f * h), sun, cel), 0, "the square's corner is outside");
        assertTrue(WaterMath.sunShape(offDisc(sun, 0.9f * h, 0.9f * h), sun, WaterMath.SQUARE) > 0.99f, "inside the square");
        float axis = WaterMath.sunShape(offDisc(sun, 0.7f * h, 0), sun, cel);
        float diag = WaterMath.sunShape(offDisc(sun, 0.7f * h / (float) Math.sqrt(2), 0.7f * h / (float) Math.sqrt(2)), sun, cel);
        assertEquals(axis, diag, 1e-3f, "round");
        float centre = WaterMath.sunShape(sun, sun, cel), rim = WaterMath.sunShape(offDisc(sun, 0.97f * h, 0), sun, cel);
        assertEquals(1 / (1 - WaterMath.SUN_LIMB_DARKENING / 3), centre, 1e-4f, "the limb-darkened middle");
        assertTrue(rim < 0.7f * centre && rim > 0, "a darker rim: " + rim);
        assertTrue(lum(WaterMath.sunDisc(a, offDisc(sun, 0.97f * h, 0), cel)) > AtmosphereMath.SHAFT_THRESHOLD * 2, "the shafts see the rim");
        //The same disc mirrored on calm water
        assertEquals(lum(WaterMath.sunDisc(a, sun, cel)), lum(WaterMath.discGlint(a, sun, 0, 1, cel)), 1e-4f);
        assertEquals(0, lum(WaterMath.discGlint(a, offDisc(sun, 0.9f * h, 0.9f * h), 0, 1, cel)), 0);
        //Square: the shape slot changes nothing
        float[] d = offDisc(sun, 0.5f * h, -0.3f * h);
        assertEquals(WaterMath.discCover(d, sun, WaterMath.SUN_HALF_SIZE), WaterMath.sunShape(d, sun, WaterMath.SQUARE), 0);
    }

    /**
     * Session 5: the round moon keeps vanilla's phases. Full (0) lights the whole disc, new (4) only the earthshine; the quarters light one
     * half, opposite halves waning (2) and waxing (6); its rippled glint shrinks with the lit share. The composite draws it in the sky only
     * when round, and then drops vanilla's square moon around it (vanillaMoonKept); the square keeps everything as before.
     */
    @Test
    void theRoundMoonKeepsItsPhases() {
        Atmosphere night = atmosphere(MIDNIGHT, 0, true);
        float[] moon = {-night.sun()[0], -night.sun()[1], -night.sun()[2]};
        float r = WaterMath.MOON_ROUND_RADIUS;
        //Vanilla's phase art grows and shrinks along the rotation axis (world z): left -z, right +z here
        float[] left = offDisc(moon, -0.5f * r, 0), right = offDisc(moon, 0.5f * r, 0);
        float max = 1, min = WaterMath.MOON_EARTHSHINE * 1.01f;
        assertTrue(WaterMath.moonShape(left, moon, round(0)) > 0.6f && WaterMath.moonShape(right, moon, round(0)) > 0.6f, "full");
        assertTrue(WaterMath.moonShape(left, moon, round(4)) <= min && WaterMath.moonShape(right, moon, round(4)) <= min, "new: earthshine only");
        float l2 = WaterMath.moonShape(left, moon, round(2)), r2 = WaterMath.moonShape(right, moon, round(2));
        float l6 = WaterMath.moonShape(left, moon, round(6)), r6 = WaterMath.moonShape(right, moon, round(6));
        assertTrue((l2 > 0.5f) != (r2 > 0.5f), "a quarter lights one half: " + l2 + " " + r2);
        assertTrue((l2 > 0.5f) == (r6 > 0.5f) && (r2 > 0.5f) == (l6 > 0.5f), "waning and waxing light opposite halves");
        assertTrue(r2 > 0.5f && l6 > 0.5f, "as vanilla's art: the last quarter lit toward +z, the first toward -z");
        assertTrue(WaterMath.moonShape(offDisc(moon, 0.9f * r, 0.9f * r), moon, round(0)) == 0, "round: the corner is outside");
        assertTrue(WaterMath.moonShape(moon, moon, round(0)) <= max);
        assertEquals(1, WaterMath.moonLit(round(0)), 1e-6f);
        assertEquals(0, WaterMath.moonLit(round(4)), 1e-6f);
        assertEquals(0.5f, WaterMath.moonLit(round(2)), 1e-6f);
        assertEquals(1, WaterMath.moonLit(WaterMath.SQUARE), 0);
        assertTrue(lum(WaterMath.discGlint(night, moon, 1, 1, round(4))) < 1e-6f, "no glint from a new moon");
        //The sky: only the round moon is drawn by W6, and vanilla's square moon is dropped around it
        assertArrayEquals(new float[3], WaterMath.moonDisc(night, moon, WaterMath.SQUARE), 0);
        assertTrue(lum(WaterMath.moonDisc(night, moon, round(0))) > 0.2f);
        assertEquals(1, WaterMath.vanillaMoonKept(night, moon, WaterMath.SQUARE), 0);
        assertEquals(0, WaterMath.vanillaMoonKept(night, moon, round(0)), 0);
        assertEquals(1, WaterMath.vanillaMoonKept(night, offDisc(moon, 0, 2 * WaterMath.VANILLA_MOON_MASK), round(0)), 0, "stars away from the moon stay");
        assertTrue(WaterMath.VANILLA_MOON_MASK > r, "the mask covers the round moon");
        //Never negative or NaN over the disc, any phase
        for (int phase = 0; phase < 8; phase++) {
            for (float u = -1.2f; u <= 1.2f; u += 0.1f) {
                for (float v = -1.2f; v <= 1.2f; v += 0.1f) {
                    float m = WaterMath.moonShape(offDisc(moon, u * r, v * r), moon, round(phase));
                    assertTrue(m >= 0 && m <= 1 && Float.isFinite(m), phase + " " + u + " " + v + ": " + m);
                }
            }
        }
    }

    @Test
    void rippledWaterTurnsTheMirroredDiscIntoARoundGlint() {
        Atmosphere a = atmosphere(SUNSET, 0, true);
        float[] sun = a.sun();
        float[] axis = {0, 0, 1};
        float h = WaterMath.SUN_HALF_SIZE;
        //On calm water the square's corner shines; on rippled water only the round lobe near the centre does
        float[] corner = WaterMath.add(sun, WaterMath.add(WaterMath.scale(axis, 0.9f * h), WaterMath.scale(WaterMath.cross(axis, sun), 0.9f * h)));
        corner = WaterMath.scale(corner, 1 / WaterMath.length(corner));
        assertTrue(lum(WaterMath.discGlint(a, corner, 0, 1, WaterMath.SQUARE)) > 10);
        assertTrue(lum(WaterMath.discGlint(a, corner, 1, 1, WaterMath.SQUARE)) < 0.5f);
        float centre = lum(WaterMath.discGlint(a, sun, 1, 1, WaterMath.SQUARE));
        assertEquals(PostMath.SUN_DISC_LUMINANCE * WaterMath.GLINT_PEAK, centre, 0.5f);
        //Round: equal at the same angle along the axis and the diagonal
        float t = 0.02f;
        float[] alongAxis = WaterMath.add(sun, WaterMath.scale(axis, t));
        float[] diagonal = WaterMath.add(sun, WaterMath.scale(WaterMath.add(axis, WaterMath.cross(axis, sun)), t / (float) Math.sqrt(2)));
        assertEquals(lum(WaterMath.discGlint(a, WaterMath.scale(alongAxis, 1 / WaterMath.length(alongAxis)), 1, 1, WaterMath.SQUARE)),
                lum(WaterMath.discGlint(a, WaterMath.scale(diagonal, 1 / WaterMath.length(diagonal)), 1, 1, WaterMath.SQUARE)), 0.05f);
        //The sun visibility at the water (an island's shadow) takes the glint
        float[] shadowed = WaterMath.reflectedSky(a, sun, 1, 0, 1, WaterMath.SQUARE);
        float[] plain = WaterMath.add(AtmosphereMath.skyColour(a, sun), AtmosphereMath.sunGlow(a, sun));
        assertArrayEquals(plain, shadowed, 1e-5f);
    }

    // ---- B6, B7: what counts as a hit ----

    @Test
    void reflectivePixelsAreNeverHitsAndHitsStandClearAboveTheSurface() {
        for (float mark : new float[]{1, 0.5f, -0.5f, -1, 0.002f, -0.002f}) {
            assertFalse(WaterMath.reflectAcceptsHit(mark, 10, 5), "reflective " + mark);
        }
        assertTrue(WaterMath.reflectAcceptsHit(0, 1, 5));
        assertFalse(WaterMath.reflectAcceptsHit(0, 0.05f, 5), "on the surface");
        assertFalse(WaterMath.reflectAcceptsHit(0, -2, 5), "below the surface");
        //Far away a pixel row spans many blocks: the clearance grows with the distance
        assertFalse(WaterMath.reflectAcceptsHit(0, 1, 2000));
        assertTrue(WaterMath.reflectAcceptsHit(0, 20, 2000));
        //Broken cover fades, full cover counts
        assertEquals(0, WaterMath.reflectCoverWeight(0.2f), 0);
        assertEquals(1, WaterMath.reflectCoverWeight(1), 0);
        assertTrue(WaterMath.reflectCoverWeight(0.6f) > 0 && WaterMath.reflectCoverWeight(0.6f) < 1);
        //The shader applies both rules to the hit and the cover to its four neighbours
        assertTrue(COMP.contains("reflectAcceptsHit(h.a, above, dist)"), "hits are judged by their alpha and height");
        assertTrue(COMP.contains("reflectCoverWeight(covered/5.0)"));
    }

    /** Review 1, Important: a far coarse step must not take a near silhouette the screen path crosses for a hit (B6). */
    @Test
    void aNearSilhouetteCrossedByAFarStepIsRejectedAndAContinuousFarSurfaceIsHit() {
        //A ray toward the horizon: one coarse step goes from 400 to 2000 blocks deep while its pixel shows a hill at 300 blocks
        float stepZ = -1600;
        assertFalse(WaterMath.marchBehind(-2000, -300, WaterMath.marchThickness(-300, stepZ)), "silhouette, coarse");
        //After the refinement the bracket left spans a few blocks of ray depth (a 1600-block step bisected 5 times, near the edge): a ray
        // 30 blocks behind the hill is off it, a refined point 4 blocks from far terrain is on it
        float bracket = stepZ / 32;
        assertFalse(Math.abs(-330 + 300) < WaterMath.refinedThickness(-300, bracket * 0.1f), "silhouette, refined");
        assertTrue(WaterMath.marchBehind(-2010, -2000, WaterMath.marchThickness(-2000, stepZ)), "far surface, coarse");
        assertTrue(Math.abs(-2004 + 2000) < WaterMath.refinedThickness(-2000, bracket), "far surface, refined");
        //Near steps keep a block's thickness; the step's part never exceeds REFLECT_STEP_CAP of the depth
        assertTrue(WaterMath.marchBehind(-10.4f, -10, WaterMath.marchThickness(-10, -0.2f)));
        for (float z = -1; z > -5000; z *= 1.5f) {
            float base = Math.max(WaterMath.REFLECT_THICK_MIN, WaterMath.REFLECT_THICK_SHARE * -z);
            assertTrue(WaterMath.marchThickness(z, -1e6f) <= base + WaterMath.REFLECT_STEP_CAP * -z + 1e-3f);
            assertTrue(WaterMath.refinedThickness(z, -1e6f) <= base + WaterMath.REFLECT_STEP_CAP * -z + 1e-3f);
        }
        //The shader uses these: the coarse test, the precise positions after it, the tight thickness on the refined hit
        assertTrue(COMP.contains("marchBehind(rayZ, scene.z, marchThickness(scene.z, rayZ - prevZ))"));
        assertTrue(COMP.contains("hitPosition(qh, size, hit)") && COMP.contains("refinedThickness(hit.z, zh - zl)") && COMP.contains("abs(zh - hit.z) < thick"));
        assertTrue(COMP.contains("return scenePosition(q, size, view);"), "the hit takes the ray origin's precise LOD read-back");
    }

    @Test
    void theCloudReflectionTakesMoreSamplesOverLongerSpans() {
        int prev = 0;
        for (float len = 0; len < 3000; len += 3) {
            int n = WaterMath.reflectCloudSamples(len);
            assertTrue(n >= WaterMath.REFLECT_CLOUD_MIN_SAMPLES && n <= WaterMath.REFLECT_CLOUD_MAX_SAMPLES && n >= prev);
            prev = n;
        }
        assertEquals(WaterMath.REFLECT_CLOUD_MAX_SAMPLES, WaterMath.reflectCloudSamples(500));
        assertTrue(COMP.contains("reflectedCloudMarch(world, r, interleavedGradientNoise(vec2(p)))"), "a screen-fixed jitter");
    }

    @Test
    void groundBeyondTheWaterHidesTheSunsGlowAndGlintInTheReflection() {
        //2026-11-09 (the user: at sunrise the sun's reflection showed through icebergs and hills)
        Atmosphere a = atmosphere(SUNSET, 0, true);
        float[] sun = a.sun();
        float[] open = WaterMath.reflectedSky(a, sun, 1, 1, 1, WaterMath.SQUARE), shut = WaterMath.reflectedSky(a, sun, 1, 1, 0, WaterMath.SQUARE);
        float[] skyOnly = AtmosphereMath.skyColour(a, sun);
        assertArrayEquals(skyOnly, shut, 1e-5f, "blocked: the sky colour, no glow, no glint");
        assertTrue(lum(open) > lum(shut) + 5, "open: the glint");
        float[] half = WaterMath.reflectedSky(a, sun, 1, 1, 0.5f, WaterMath.SQUARE);
        assertEquals(lum(skyOnly) + 0.5f * (lum(open) - lum(skyOnly)), lum(half), 1e-3f, "linear in the open share");
        //The march's verdict is geometry: it takes the moon's glint too (the sun's shadow maps do not)
        Atmosphere night = atmosphere(MIDNIGHT, 0, true);
        float[] moon = {-night.sun()[0], -night.sun()[1], -night.sun()[2]};
        assertArrayEquals(AtmosphereMath.skyColour(night, moon), WaterMath.reflectedSky(night, moon, 1, 1, 0, WaterMath.SQUARE), 1e-6f);
        assertTrue(lum(WaterMath.reflectedSky(night, moon, 1, 0, 1, WaterMath.SQUARE)) > lum(AtmosphereMath.skyColour(night, moon)) + 0.1f);
        //A step blocks only behind ground beyond the reflecting point (view z: more negative is farther), above its plane, not water/ice
        float dist = 50;
        float clear = WaterMath.reflectHitClearance(dist);
        assertTrue(WaterMath.reflectBlocksSky(0, 2, dist, -110, -100, -50), "behind a hill 100 blocks out, 2 blocks up");
        assertTrue(WaterMath.reflectBlocksSky(-1.5f, 2, dist, -110, -100, -50), "a polished block's mark is ground");
        assertFalse(WaterMath.reflectBlocksSky(0, 2, dist, -90, -100, -50), "in front of the hill");
        assertFalse(WaterMath.reflectBlocksSky(0, 2, dist, -110, -40, -50), "a surface nearer than the water is no blocker");
        assertFalse(WaterMath.reflectBlocksSky(0, clear * 0.5f, dist, -110, -100, -50), "not below the clearance");
        assertFalse(WaterMath.reflectBlocksSky(WaterMath.WATER_REFLECTION, 2, dist, -110, -100, -50), "far water");
        assertFalse(WaterMath.reflectBlocksSky(-WaterMath.GLASS_REFLECTION, 2, dist, -110, -100, -50), "ice");
        //The pass marches before it builds the missed ray's sky, and gives the sky the open share
        assertTrue(COMP.contains("reflectBlocksSky(texelFetch(sceneLit, q, 0).a, dot(toWorld*(scene - v0), nGeom), dist, rayZ, scene.z, v0.z)"));
        assertTrue(COMP.contains("reflectedSky(a, r, ripple, sunVisibility, (1.0 - blocked)*(1.0 - w), celestial)"));
        assertTrue(COMP.indexOf("w = marchReflection(view, toView*r") < COMP.indexOf("reflectedSky(a, r, ripple"), "the march first");
    }

    @Test
    void theMoonsGlintDoesNotTakeTheSunsShadow() {
        Atmosphere night = atmosphere(MIDNIGHT, 0, true);
        float[] moon = {-night.sun()[0], -night.sun()[1], -night.sun()[2]};
        float[] shadowed = WaterMath.reflectedSky(night, moon, 1, 0, 1, WaterMath.SQUARE), lit = WaterMath.reflectedSky(night, moon, 1, 1, 1, WaterMath.SQUARE);
        assertArrayEquals(lit, shadowed, 1e-6f);
        assertTrue(lum(shadowed) > lum(WaterMath.add(AtmosphereMath.skyColour(night, moon), AtmosphereMath.sunGlow(night, moon))) + 0.1f);
        //The sun visibility is upsampled as W5's lightingAt does, with distances from the depth (no lightTex in this pass)
        assertTrue(COMP.contains("sunVisibilityAt(p, size, dist, toView*nGeom)") && COMP.contains("UPSAMPLE_NORMAL_POWER"));
    }

    /** The RMS slope of the normals over a patch of near water (every octave at full strength). */
    private static double rmsSlope(float[] o, float density) {
        double sum = 0;
        int n = 0;
        for (float x = -12; x < 12; x += 0.137f) {
            for (float z = -12; z < 12; z += 0.151f) {
                float[] nn = WaterMath.rippleNormal(x, z, o, 1, density, 0.001f);
                sum += (nn[0] * nn[0] + nn[2] * nn[2]) / (nn[1] * nn[1]);
                n++;
            }
        }
        return Math.sqrt(sum / n);
    }

    /** The mean distance between sign changes of the x slope along a line (blocks): a measure of the wavelength. */
    private static double crossingSpacing(float[] o, float density) {
        int changes = 0;
        float prev = 0, len = 60, step = 0.01f;
        for (float x = 0; x < len; x += step) {
            float sx = -WaterMath.rippleNormal(x, 0.37f * x + 5, o, 1, density, 0.001f)[0];
            if (x > 0 && Math.signum(sx) != Math.signum(prev)) changes++;
            prev = sx;
        }
        return len / Math.max(changes, 1);
    }

    /**
     * 2026-11-09 (session 2a; the user: the ripples' wavelength was so long the water looked like plastic): short waves, a density
     * setting that changes their length but not their steepness, and directional octaves that flow along their own direction.
     */
    @Test
    void ripplesAreShortAndTheDensityChangesTheirLengthButNotTheirSteepness() {
        float[] o = RippleMath.offsets(41.5, -17.25, new double[]{1, 2, 3, 4, 5, 6, 7, 8});
        //Every octave is at most a third of the old longest one (6 blocks), the finest a quarter block or less
        for (int k = 0; k < RippleMath.OCTAVES; k++) assertTrue(WaterMath.rippleCellAcross(k, 1) <= 2.1f, "octave " + k);
        assertTrue(WaterMath.rippleCellAcross(RippleMath.OCTAVES - 1, 1) <= 0.25f);
        //The density divides the cells (rounded to whole cells per period: within 10% at the lowest density, where a period holds
        // only about six of the first octave's turned cells)
        for (int k = 0; k < RippleMath.OCTAVES; k++) {
            for (float d : new float[]{0.25f, 0.5f, 1, 1.5f, 2}) {
                assertEquals(RippleMath.CELLS[k] / d, WaterMath.rippleCellAcross(k, d), 0.1f * RippleMath.CELLS[k] / d, "octave " + k + " at " + d);
            }
        }
        //Denser water has shorter waves: the slope changes sign more often along a line
        double half = crossingSpacing(o, 0.5f), one = crossingSpacing(o, 1), two = crossingSpacing(o, 2);
        assertTrue(half > one * 1.4 && one > two * 1.4, half + " " + one + " " + two);
        //but the same steepness (within 15%): finer, not rougher
        double sHalf = rmsSlope(o, 0.5f), sOne = rmsSlope(o, 1), sTwo = rmsSlope(o, 2);
        assertEquals(sOne, sHalf, 0.15 * sOne);
        assertEquals(sOne, sTwo, 0.15 * sOne);
        //A calm lake's slope (Cox and Munk 1954: about 0.06 RMS in no wind; the old three octaves gave about 0.04)
        assertTrue(sOne > 0.035 && sOne < 0.08, "RMS slope " + sOne);
        //The density is clamped to the settings' range, NaN is 100%
        assertEquals(1, WaterMath.rippleDensity(Float.NaN), 0);
        assertEquals(RippleMath.RIPPLE_DENSITY_MIN, WaterMath.rippleDensity(0), 0);
        assertEquals(RippleMath.RIPPLE_DENSITY_MIN, WaterMath.rippleDensity(Float.NEGATIVE_INFINITY), 0);
        assertEquals(RippleMath.RIPPLE_DENSITY_MAX, WaterMath.rippleDensity(Float.POSITIVE_INFINITY), 0);
        assertArrayEquals(WaterMath.rippleNormal(3, 4, o, 1, Float.NaN, 0.01f), WaterMath.rippleNormal(3, 4, o, 1, 1, 0.01f), 0);
        assertEquals(PostProcessSettings.RIPPLE_DENSITY_MIN / 100f, RippleMath.RIPPLE_DENSITY_MIN, 1e-6f);
        assertEquals(PostProcessSettings.RIPPLE_DENSITY_MAX / 100f, RippleMath.RIPPLE_DENSITY_MAX, 1e-6f);
        //Denser waves fade nearer the camera (the fade follows the rounded cell), so they never shimmer farther out than the old ones
        float far = WaterMath.rippleCellAcross(0, 2) / RippleMath.RIPPLE_FADE_NONE;
        assertArrayEquals(new float[]{0, 1, 0}, WaterMath.rippleNormal(5, 6, o, 1, 2, far), 0);
    }

    @Test
    void eachOctaveFlowsAlongItsWholeNumberDirectionAndTheFirstAlongTheWind() {
        for (int k = 0; k < RippleMath.OCTAVES; k++) {
            float u = RippleMath.DIRS[k][0], v = RippleMath.DIRS[k][1];
            assertEquals(Math.rint(u), u, 0, "whole numbers keep the turned noise periodic");
            assertEquals(Math.rint(v), v, 0);
            double angle = Math.toDegrees(Math.atan2(RippleMath.FLOW[k][1], RippleMath.FLOW[k][0]) - Math.atan2(v, u));
            assertEquals(0, angle, 1.0, "octave " + k + " flows across its crests");
            assertTrue(RippleMath.STRETCHES[k] >= 1, "crests are longer than the waves are wide");
            //Within about 60 degrees of the wind: a chop, not a cross sea
            double toWind = Math.toDegrees(Math.atan2(v, u) - Math.atan2(RippleMath.FLOW[0][1], RippleMath.FLOW[0][0]));
            assertTrue(Math.abs(toWind) < 60, "octave " + k + " " + toWind);
        }
    }

    // ---- 5. RippleMath ----

    @Test
    void wrapKeepsValuesInZeroToPeriod() {
        for (double v = -5000; v < 5000; v += 0.731) {
            double w = RippleMath.wrap(v);
            assertTrue(w >= 0 && w < RippleMath.PERIOD, "" + v);
            assertEquals(0, Math.IEEEremainder(w - v, RippleMath.PERIOD), 1e-6);
        }
        for (double v : new double[]{-RippleMath.PERIOD, -1e-18, 1e15 + 0.5, -1e15, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, -Double.MIN_VALUE}) {
            double w = RippleMath.wrap(v);
            assertTrue(w >= 0 && w < RippleMath.PERIOD, "" + v);
        }
        assertEquals(RippleMath.OCTAVES, RippleMath.FLOW.length);
        assertEquals(RippleMath.PERIOD, RippleMath.RIPPLE_PERIOD, 0);
    }

    @Test
    void offsetsChangeContinuouslyWithTheCamera() {
        double[] phase = {1, 2, 3, 4, 5, 6};
        for (double x = 29990; x < 30010; x += 0.0137) {
            float[] a = RippleMath.offsets(x, -x, phase), b = RippleMath.offsets(x + 0.01, -x + 0.01, phase);
            for (int k = 0; k < 2 * RippleMath.OCTAVES; k++) {
                double step = b[k] - a[k];
                double circular = step - RippleMath.PERIOD * Math.rint(step / RippleMath.PERIOD);
                assertEquals(0.01, circular, 1e-3, "octave offset " + k + " at " + x);
            }
        }
        //The shader's two vec4 ripple slots hold every octave's offset
        assertEquals(8, RippleMath.offsets(12.75, -3.5, null).length);
        assertEquals(4, RippleMath.OCTAVES);
        for (float v : RippleMath.offsets(Double.NaN, Double.POSITIVE_INFINITY, phase)) assertTrue(Float.isFinite(v));
        //The speed factor: the setting, faster in rain, never negative
        assertEquals(1, RippleMath.speedFactor(0, 1), 0);
        assertEquals(1 + RippleMath.RAIN_SPEED, RippleMath.speedFactor(1, 1), 0);
        assertEquals(0, RippleMath.speedFactor(1, 0), 0);
        assertEquals(0, RippleMath.speedFactor(Float.NaN, Float.NaN), 0);
        assertEquals(2, RippleMath.speedFactor(Float.NaN, 2), 0);
        assertEquals(1000.5 / 20.0, RippleMath.seconds(1000, 0.5f), 1e-9);
        //One frame at 20 fps flows, a second's hitch still does, a longer gap is a jump
        assertTrue(RippleMath.MAX_STEP >= 1);
    }

    // ---- 6. the uniforms ----

    private static VulkanShaderMeta meta() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get("postReflection");
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    private static int floats(String type) {
        return type.equals("mat4") ? 16 : type.equals("vec4") ? 4 : type.equals("vec2") ? 2 : 1;
    }

    /** Every member NaN, then the writer: the given locations hold no NaN after it. */
    private static void assertWrites(Consumer<LooseUniformWriter> write, List<Integer> locations) {
        VulkanShaderMeta meta = meta();
        LooseUniformWriter w = new LooseUniformWriter(meta);
        ByteBuffer raw = w.buffer();
        for (int i = 0; i < raw.limit(); i += 4) raw.putFloat(i, Float.NaN);
        write.accept(w);
        ByteBuffer b = w.buffer();
        for (int loc : locations) {
            VulkanShaderMeta.PushMember m = meta.push().stream().filter(x -> x.location() == loc).findFirst()
                    .orElseThrow(() -> new AssertionError("postReflection does not declare location " + loc));
            for (int i = 0; i < floats(m.type()); i++) assertFalse(Float.isNaN(b.getFloat(m.offset() + 4 * i)), "location " + loc + " component " + i);
        }
    }

    private static ReflectionUniforms.Values on(PostProcessSettings s, float[] ripple) {
        AtmosphereFrame f = frame(SUNSET, 0, new float[]{0.47f, 0.65f, 1}, ripple);
        LightingChoice choice = LightingChoice.of(SERIAL, s, true, true, false, true);
        LightingUniforms.Values lighting = LightingUniforms.compute(s, choice, f, SERIAL, true, 1, true);
        return ReflectionUniforms.compute(s, choice, lighting, f, 0, 1);
    }

    @Test
    void valuesWriteEverySlotW6OwnsAndTheShaderDeclaresTheOthersSlots() {
        List<Integer> owned = new ArrayList<>();
        for (int i = 0; i <= 13; i++) owned.add(i);
        for (int i = 23; i <= 26; i++) owned.add(i);
        owned.add(29);//27, 28: CelestialUniforms (session 5)
        ReflectionUniforms.Values v = on(SETTINGS, RippleMath.offsets(100.5, -20.25, new double[]{1, 2, 3, 4, 5, 6}));
        assertTrue(v.on());
        assertWrites(v::write, owned);
        assertWrites(ReflectionUniforms.off()::write, owned);
        //The light volume (clean) and the cloud block (W4) land on declared vec4 slots
        List<Integer> light = List.of(19, 20, 21, 22), clouds = List.of(14, 15, 16, 17, 18);
        assertWrites(w -> LightVolumeUniforms.off().write(w, LightVolumeUniforms.REFLECTION_FIRST), light);
        AtmosphereUniforms.Values atmosphere = AtmosphereUniforms.compute(SETTINGS, frame(SUNSET, 0, new float[]{0.47f, 0.65f, 1}, null), SERIAL, true, false);
        assertWrites(w -> CloudUniforms.compute(SETTINGS, null, atmosphere, SERIAL, true, true, 32).write(w, CloudUniforms.REFLECTION_FIRST), clouds);
        assertWrites(CelestialUniforms.off()::writeReflection, List.of(CelestialUniforms.REFLECTION_CELESTIAL, CelestialUniforms.REFLECTION_AURORA));
        //Values: the depth parameters' fixed meaning, the atmosphere block where the shader reads it, the ripple offsets
        LooseUniformWriter w = new LooseUniformWriter(meta());
        v.write(w);
        ByteBuffer b = w.buffer();
        int o3 = offset(3);
        assertEquals(net.vulkanmod.visuals.lod.LodClamp.CLAMP_THRESHOLD, b.getFloat(o3), 0);
        assertEquals(1, b.getFloat(o3 + 4), 0, "the LOD depth is valid");
        for (int i = 0; i < ReflectionUniforms.ATMOSPHERE_SLOTS.length; i++) {
            for (int c = 0; c < 4; c++) assertEquals(v.block()[i * 4 + c], b.getFloat(offset(ReflectionUniforms.ATMOSPHERE_SLOTS[i]) + 4 * c), 0);
        }
        float[] expect = RippleMath.offsets(100.5, -20.25, new double[]{1, 2, 3, 4, 5, 6});
        for (int i = 0; i < 4; i++) {
            assertEquals(expect[i], b.getFloat(offset(11) + 4 * i), 0);
            assertEquals(expect[4 + i], b.getFloat(offset(12) + 4 * i), 0);
        }
        //2026-11-09: slot 26 x the ripple density setting (ratio)
        assertEquals(1, b.getFloat(offset(ReflectionUniforms.RIPPLE_PARAMS)), 0, "100% by default");
        assertEquals(1.5f, on(settings(c -> c.waterRippleDensity = 150), null).density(), 1e-6f);
        assertEquals(PostProcessSettings.RIPPLE_DENSITY_MAX / 100f, on(settings(c -> c.waterRippleDensity = 900), null).density(), 1e-6f);
        assertEquals(1, ReflectionUniforms.off().density(), 0);
        assertTrue(COMP.contains("rippleNormal(world.xz, rippleOffsetsA, rippleOffsetsB, strength, rippleParams.x, footprint)"));
        assertArrayEquals(AtmosphereUniforms.block(SETTINGS, frame(SUNSET, 0, new float[]{0.47f, 0.65f, 1}, null), true), v.block(), 0,
                "W4's block, sky taken from the frame");
        //The shader's struct and blocks come from these slots
        assertTrue(COMP.contains("Atmosphere(atmosphereSun, atmosphereSunLight, atmosphereMoonLight, atmosphereDepth, atmosphereAmbient, atmosphereGlow, atmosphereFog)"));
        assertTrue(COMP.contains("CloudLight(cloudLit, cloudAmbient, cloudLightDir)"));
    }

    private static int offset(int location) {
        for (VulkanShaderMeta.PushMember m : meta().push()) if (m.location() == location) return m.offset();
        throw new AssertionError("no location " + location);
    }

    @Test
    void thePassIsOffWithoutReflectionsOrFrameAndPaintsTheDebugView() {
        assertTrue(on(SETTINGS, null).on());
        assertFalse(on(settings(c -> {
            c.waterReflections = 0;
            c.blockReflections = 0;
        }), null).on(), "reflections 0");
        assertFalse(on(settings(c -> c.enabled = false), null).on(), "post-processing off");
        LightingChoice choice = LightingChoice.of(SERIAL, SETTINGS, true, true, false, true);
        AtmosphereFrame f = frame(SUNSET, 0, null, null);
        LightingUniforms.Values lighting = LightingUniforms.compute(SETTINGS, choice, f, SERIAL, false, 1, true);
        assertFalse(ReflectionUniforms.compute(SETTINGS, choice, lighting, null, 0, 1).on(), "no frame");
        assertFalse(ReflectionUniforms.compute(SETTINGS, choice, LightingUniforms.off(), f, 0, 1).on(), "no lighting pass");
        LightingChoice none = LightingChoice.of(SERIAL, SETTINGS, true, true, false, false);
        assertFalse(ReflectionUniforms.compute(SETTINGS, none, lighting, f, 0, 1).on(), "no sky light: no reflections chosen");
        assertTrue(on(settings(c -> c.lightDebug = PostProcessSettings.REFLECTIVE_DEBUG), null).debug());
        assertFalse(on(SETTINGS, null).debug());
        assertEquals(0, on(settings(c -> c.waterRipples = 0), null).ripples(), 0);
        //The debug view paints water and ice/glass in two different flat colours; other pixels are written unchanged
        assertTrue(COMP.contains("reflectParams.w > 0.5"));
        assertFalse(WaterMath.DEBUG_WATER_RED == WaterMath.DEBUG_GLASS_RED && WaterMath.DEBUG_WATER_GREEN == WaterMath.DEBUG_GLASS_GREEN
                && WaterMath.DEBUG_WATER_BLUE == WaterMath.DEBUG_GLASS_BLUE);
    }

    // ---- the mirror ----

    /** Java-only tuning values: not shader consts. */
    private static final Set<String> JAVA_ONLY = Set.of("WATER_NIGHT_AMBIENT", "WATER_SURFACE_ALPHA", "RAIN_SPEED", "OCTAVES");

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        int found = 0;
        for (String text : new String[]{GLSL, COMP}) {
            Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
            while (m.find()) {
                Field f;
                try {
                    f = WaterMath.class.getField(m.group(2));
                } catch (NoSuchFieldException e) {
                    f = RippleMath.class.getField(m.group(2));
                }
                double want = Double.parseDouble(m.group(3));
                assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
                found++;
            }
        }
        assertTrue(found >= 55, "constants found: " + found);
        for (Class<?> c : new Class<?>[]{WaterMath.class, RippleMath.class}) {
            for (Field f : c.getFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class) || JAVA_ONLY.contains(f.getName())) continue;
                Pattern p = Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b");
                assertTrue(p.matcher(GLSL).find() || p.matcher(COMP).find(), f.getName() + " missing in the shaders");
            }
        }
        //The hash's finaliser constants and the documented functions for the composite
        assertTrue(GLSL.contains("0x7feb352du") && GLSL.contains("0x846ca68bu"));
        for (String fn : new String[]{"vec3 waterTransmittance(float len)", "vec3 waterColour(vec3 behind, float len, vec3 light)",
                "float reflectionMark(float kind)", "float reflectivity(float mark, float cosTheta)", "vec3 sunDisc(Atmosphere a, vec3 dir, vec4 celestial)",
                "vec3 discGlint(Atmosphere a, vec3 r, float ripple, float sunVisibility, vec4 celestial)", "vec3 rippleNormal(vec2 rel, vec4 offsetsA, vec4 offsetsB, float strength, float density, float footprint)"}) {
            assertTrue(GLSL.contains(fn), fn);
        }
        //water.glsl declares no uniforms or bindings (the composite imports it)
        assertFalse(Pattern.compile("(?m)^\\s*(layout|uniform)\\b").matcher(GLSL).find());
    }

    /**
     * 2026-11-01 (the user: BMC5's water columns looked odd): the lighting pass calls every upright translucent face glass (B5), so the
     * composite gives water's sides no water colour; at WaterAlpha they were a see-through box. Only water's top and bottom faces take
     * the water mark (WaterAlpha); its sides keep vanilla's opacity. Lava keeps its glow mark on every face.
     */
    @Test
    void waterSidesKeepVanillasOpacity() throws Exception {
        assertEquals(FoliageMarker.WATER_ALPHA, FoliageMarker.fluidMark(false, true, false), 0);
        assertEquals(1f, FoliageMarker.fluidMark(false, true, true), 0);
        assertEquals(FoliageMarker.EMISSIVE_FLUID_ALPHA, FoliageMarker.fluidMark(true, false, false), 0);
        assertEquals(FoliageMarker.EMISSIVE_FLUID_ALPHA, FoliageMarker.fluidMark(true, true, true), 0);
        assertEquals(1f, FoliageMarker.fluidMark(false, false, false), 0);
        //LiquidRenderer colours the side quads (its XZ_DIRECTIONS loop) with the side mark
        String liquid = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/net/vulkanmod/render/chunk/build/LiquidRenderer.java"));
        String sides = liquid.substring(liquid.indexOf("for (Direction direction : Util.XZ_DIRECTIONS)"));
        assertTrue(sides.contains("updateColor(r, g, b, brightness, this.sideMarkAlpha);"), "the side faces take the side mark");
    }
}
