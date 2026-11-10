package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W4: the sky, the haze, the light colours, the shafts and the uniform blocks (AtmosphereMath mirrors atmosphere.glsl). */
class AtmosphereTest {
    private static final long SERIAL = 11;
    private static final PostProcessSettings SETTINGS = PostProcessSettings.from(new VisualsConfig());
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
    private static final float NOON = 0, MIDNIGHT = 0.5f;
    /** Sun height 0.05 (sin of about 2.9 degrees) in the west (-x): the sunset of S2. */
    private static final float SUNSET = (float) (Math.acos(0.05) / (2 * Math.PI));
    private static final float[] FOG = {0.75f, 0.4f, 0.3f};
    private static final float[] WATER_FOG = {0.05f, 0.2f, 0.6f};
    private static final float[] PLAINS_SKY = {121 / 255f, 167 / 255f, 1};

    // ---- frames ----

    private static AtmosphereFrame frame(float timeOfDay, float rain, float thunder, boolean fogCovers, float[] fog, float cameraY, Matrix4f modelView) {
        return new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), modelView.invert(new Matrix4f()),
                new Matrix4f(PROJ).mul(modelView), cameraY, 63, fog[0], fog[1], fog[2], fogCovers, false, timeOfDay, rain, thunder, null,
                PLAINS_SKY.clone(), 256, null, new CloudFrame(192, 100, 200, new float[]{1, 1, 1}));
    }

    private static AtmosphereFrame frame(float timeOfDay, float rain) {
        return frame(timeOfDay, rain, 0, false, FOG, 120, new Matrix4f());
    }

    private static AtmosphereMath.Atmosphere atmosphere(PostProcessSettings s, AtmosphereFrame f, boolean hasSky) {
        return AtmosphereUniforms.compute(s, f, SERIAL, hasSky, false).atmosphere();
    }

    private static AtmosphereMath.Atmosphere sky(float timeOfDay, float rain) {
        return atmosphere(SETTINGS, frame(timeOfDay, rain), true);
    }

    private static PostProcessSettings settings(java.util.function.Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static float[] dir(float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z).normalize();
        return new float[]{v.x, v.y, v.z};
    }

    /** Directions over the whole sphere, the poles included. */
    private static List<float[]> sphere() {
        List<float[]> out = new ArrayList<>();
        out.add(new float[]{0, 1, 0});
        out.add(new float[]{0, -1, 0});
        for (int el = -85; el <= 85; el += 5) {
            for (int az = 0; az < 360; az += 30) {
                double e = Math.toRadians(el), a = Math.toRadians(az);
                out.add(new float[]{(float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e), (float) (Math.cos(e) * Math.cos(a))});
            }
        }
        return out;
    }

    private static float lum(float[] c) {
        return PostMath.luminance(c[0], c[1], c[2]);
    }

    private static void assertFiniteNonNegative(float[] c, String what) {
        for (float f : c) assertTrue(Float.isFinite(f) && f >= 0, what + " " + Arrays.toString(c));
    }

    private static final float[] DISTANCES = {0, 0.5f, 1, 10, 100, 1000, 10000, 1e6f, 1e9f, Float.MAX_VALUE, Float.POSITIVE_INFINITY};

    // ---- 1. no sky light ----

    @Test
    void withoutSkyLightTheSkyIsTheDimensionsFogColourAndNothingIsNaN() {
        AtmosphereUniforms.Values v = AtmosphereUniforms.compute(SETTINGS, frame(NOON, 0), SERIAL, false, false);
        AtmosphereMath.Atmosphere a = v.atmosphere();
        assertEquals(AtmosphereMath.ATMOSPHERE_NO_SKY, a.mode());
        for (float f : v.block()) assertTrue(Float.isFinite(f));
        float[] fog = {PostMath.displayToLinear(FOG[0]), PostMath.displayToLinear(FOG[1]), PostMath.displayToLinear(FOG[2])};
        for (float[] d : sphere()) {
            assertArrayEquals(fog, AtmosphereMath.skyColour(a, d), 1e-6f);
            assertArrayEquals(new float[3], AtmosphereMath.sunGlow(a, d), 0f, "no sun");
            for (float dist : DISTANCES) {
                AtmosphereMath.Haze h = AtmosphereMath.haze(a, d, dist, 1);
                assertFiniteNonNegative(h.transmittance(), "transmittance");
                assertFiniteNonNegative(h.inscatter(), "inscatter");
                //the haze takes the fog colour: inscatter = fog (1 - t), t grey
                for (int i = 0; i < 3; i++) assertEquals(fog[i] * (1 - h.transmittance()[0]), h.inscatter()[i], 1e-6f);
            }
        }
        assertEquals(0f, v.shafts(), 0f);
        assertArrayEquals(new float[3], v.shaftLight(), 0f);
        assertEquals(0f, v.night(), 0f);
        assertFalse(CloudUniforms.compute(SETTINGS, frame(NOON, 0), v, SERIAL, false, true, 32).on(), "no clouds without sky");
    }

    @Test
    void nothingIsNaNAtAnyTimeOrWeatherWithSky() {
        for (float t = 0; t < 1; t += 0.01f) {
            for (float rain : new float[]{0, 0.5f, 1}) {
                AtmosphereUniforms.Values v = AtmosphereUniforms.compute(SETTINGS, frame(t, rain, rain, false, FOG, 70, new Matrix4f()), SERIAL, true, false);
                for (float f : v.block()) assertTrue(Float.isFinite(f), "t " + t);
                AtmosphereMath.Atmosphere a = v.atmosphere();
                for (float[] d : sphere()) {
                    assertFiniteNonNegative(AtmosphereMath.skyColour(a, d), "sky t " + t);
                    assertFiniteNonNegative(AtmosphereMath.sunGlow(a, d), "glow t " + t);
                }
                for (float dist : DISTANCES) {
                    AtmosphereMath.Haze h = AtmosphereMath.haze(a, dir(0.3f, -0.2f, 1), dist, 1);
                    assertFiniteNonNegative(h.transmittance(), "transmittance");
                    assertFiniteNonNegative(h.inscatter(), "inscatter");
                }
            }
        }
        //No frame: no atmosphere, the haze does nothing
        AtmosphereMath.Atmosphere none = AtmosphereUniforms.compute(SETTINGS, null, SERIAL, true, false).atmosphere();
        assertEquals(AtmosphereMath.ATMOSPHERE_NONE, none.mode());
        AtmosphereMath.Haze h = AtmosphereMath.haze(none, dir(0, 0, 1), 5000, 1);
        assertArrayEquals(new float[]{1, 1, 1}, h.transmittance(), 0f);
        assertArrayEquals(new float[3], h.inscatter(), 0f);
        assertEquals(AtmosphereMath.ATMOSPHERE_NONE, AtmosphereUniforms.compute(SETTINGS, frame(NOON, 0), SERIAL + 1, true, false).mode(), "stale frame");
    }

    // ---- 2. the sky by time and weather ----

    @Test
    void zenithBrightnessFallsFromNoonToRainToSunsetToMidnightAndSunsetIsWarm() {
        float[] up = {0, 1, 0};
        float noon = lum(AtmosphereMath.skyColour(sky(NOON, 0), up));
        float rainy = lum(AtmosphereMath.skyColour(sky(NOON, 1), up));
        float sunset = lum(AtmosphereMath.skyColour(sky(SUNSET, 0), up));
        float midnight = lum(AtmosphereMath.skyColour(sky(MIDNIGHT, 0), up));
        for (float l : new float[]{noon, rainy, sunset, midnight}) assertTrue(Float.isFinite(l) && l >= 0, "" + l);
        assertTrue(noon > rainy && rainy > sunset && sunset > midnight, noon + " > " + rainy + " > " + sunset + " > " + midnight);
        assertTrue(midnight > 0, "the night sky is dark, not black");
        //W2's scale: a clear noon sky is about 0.2..0.5 luminance at the zenith and brighter toward the horizon
        assertTrue(noon > 0.1f && noon < 0.5f, "noon zenith " + noon);
        float[] noonHorizon = AtmosphereMath.skyColour(sky(NOON, 0), dir(-1, 0, 0));
        assertTrue(lum(noonHorizon) > noon, "the horizon is brighter than the zenith");
        assertTrue(noonHorizon[2] > noonHorizon[0], "the noon horizon is blue-white");
        //Toward the low western sun the horizon is warmer than the noon horizon
        AtmosphereMath.Atmosphere s = sky(SUNSET, 0);
        assertTrue(s.sun()[0] < -0.99f, "the sun sets in the west");
        float[] sunsetHorizon = AtmosphereMath.skyColour(s, dir(-1, 0.01f, 0));
        assertTrue(sunsetHorizon[0] > sunsetHorizon[2], "more red than blue " + Arrays.toString(sunsetHorizon));
        assertTrue(sunsetHorizon[0] / sunsetHorizon[2] > noonHorizon[0] / noonHorizon[2]);
        //The side away from the sun is cooler than the side toward it, and the glow sits on the sun
        float[] away = AtmosphereMath.skyColour(s, dir(1, 0.01f, 0));
        assertTrue(away[0] / away[2] < sunsetHorizon[0] / sunsetHorizon[2]);
        assertTrue(lum(AtmosphereMath.sunGlow(s, s.sun())) > lum(AtmosphereMath.sunGlow(s, dir(1, 0.3f, 0))) * 50);
    }

    @Test
    void theSettingsActOnTheirOwnPart() {
        float[] sunward = dir(-1, 0.05f, 0);
        //sunset light 0: the low sun's colours lose their hue (the sky's warmth at the sun's side becomes nearly neutral)
        AtmosphereMath.Atmosphere neutral = atmosphere(settings(c -> c.sunsetLight = 0), frame(SUNSET, 0), true);
        float[] n = AtmosphereMath.skyColour(neutral, sunward), w = AtmosphereMath.skyColour(sky(SUNSET, 0), sunward);
        assertTrue(n[0] / n[2] < w[0] / w[2], "sunset light 0 is less warm");
        //sun glow 0: no glow; moonlight 0: a darker night that is still not black
        AtmosphereMath.Atmosphere noGlow = atmosphere(settings(c -> c.sunGlow = 0), frame(SUNSET, 0), true);
        assertArrayEquals(new float[3], AtmosphereMath.sunGlow(noGlow, sunward), 0f);
        float moonlit = lum(AtmosphereMath.skyColour(sky(MIDNIGHT, 0), new float[]{0, 1, 0}));
        float dark = lum(AtmosphereMath.skyColour(atmosphere(settings(c -> c.moonlight = 0), frame(MIDNIGHT, 0), true), new float[]{0, 1, 0}));
        assertTrue(dark < moonlit && dark > 0, dark + " " + moonlit);
        //The night factor
        assertEquals(0f, AtmosphereUniforms.compute(SETTINGS, frame(NOON, 0), SERIAL, true, false).night(), 1e-6f);
        assertEquals(1f, AtmosphereUniforms.compute(SETTINGS, frame(MIDNIGHT, 0), SERIAL, true, false).night(), 1e-6f);
        float last = -1;
        for (float t = 0; t <= 0.5f; t += 0.005f) {
            float night = AtmosphereUniforms.compute(SETTINGS, frame(t, 0), SERIAL, true, false).night();
            assertTrue(night >= last - 1e-6f && night >= 0 && night <= 1, "t " + t);
            last = night;
        }
    }

    // ---- 3. environmental fog ----

    @Test
    void underVanillasEnvironmentalFogTheHazeAndSkyTakeItsColour() {
        AtmosphereFrame f = frame(NOON, 0, 0, true, WATER_FOG, 50, new Matrix4f());
        AtmosphereUniforms.Values v = AtmosphereUniforms.compute(SETTINGS, f, SERIAL, true, false);
        AtmosphereMath.Atmosphere a = v.atmosphere();
        assertEquals(AtmosphereMath.ATMOSPHERE_FOG, a.mode());
        float[] fog = {PostMath.displayToLinear(WATER_FOG[0]), PostMath.displayToLinear(WATER_FOG[1]), PostMath.displayToLinear(WATER_FOG[2])};
        for (float[] d : sphere()) {
            assertArrayEquals(fog, AtmosphereMath.skyColour(a, d), 1e-6f);
            for (float dist : new float[]{4, 40, 400, 4000}) {
                AtmosphereMath.Haze h = AtmosphereMath.haze(a, d, dist, 1);
                float k = 1 - h.transmittance()[0];
                assertTrue(k > 0);
                for (int i = 0; i < 3; i++) assertEquals(fog[i], h.inscatter()[i] / k, 1e-5f, "the haze is vanilla's fog colour");
            }
        }
        assertEquals(0f, v.shafts(), 0f);
        assertFalse(CloudUniforms.compute(SETTINGS, f, v, SERIAL, true, true, 32).on(), "no far clouds under water");
    }

    // ---- 4. haze ----

    @Test
    void hazeTransmittanceIsBoundedFallsWithDistanceAndRisesWithAltitude() {
        List<float[]> dirs = List.of(dir(0, 0, 1), dir(1, -0.3f, 0), dir(0, -1, 0), dir(0.2f, 0.4f, 1), dir(0, 1, 0), dir(-1, 0.02f, 0.3f));
        for (float t : new float[]{NOON, SUNSET, MIDNIGHT}) {
            for (float rain : new float[]{0, 1}) {
                for (float[] d : dirs) {
                    //distance
                    AtmosphereMath.Atmosphere a = atmosphere(SETTINGS, frame(t, rain, 0, false, FOG, 140, new Matrix4f()), true);
                    float[] last = {1, 1, 1};
                    for (float dist = 0; dist <= 20000; dist += 25) {
                        float[] tr = AtmosphereMath.haze(a, d, dist, 1).transmittance();
                        for (int i = 0; i < 3; i++) {
                            assertTrue(tr[i] >= 0 && tr[i] <= 1, "bounded " + tr[i]);
                            assertTrue(tr[i] <= last[i] + 1e-6f, "falls with distance at " + dist);
                        }
                        last = tr;
                    }
                    //altitude (the same view and distance from a higher camera)
                    for (float dist : new float[]{50, 500, 5000}) {
                        float[] below = {0, 0, 0};
                        for (float y = 0; y <= 600; y += 10) {
                            AtmosphereMath.Atmosphere at = atmosphere(SETTINGS, frame(t, rain, 0, false, FOG, y, new Matrix4f()), true);
                            float[] tr = AtmosphereMath.haze(at, d, dist, 1).transmittance();
                            for (int i = 0; i < 3; i++) assertTrue(tr[i] >= below[i] - 1e-6f, "rises with altitude at y " + y);
                            below = tr;
                        }
                    }
                }
            }
        }
        //A thinner fog height thins the fog up high: a high camera sees farther with fog height 32 than 512
        float[] side = dir(0, -0.05f, 1);
        float low = AtmosphereMath.haze(atmosphere(settings(c -> c.fogHeight = 32), frame(NOON, 0, 0, false, FOG, 250, new Matrix4f()), true), side, 3000, 1).transmittance()[1];
        float high = AtmosphereMath.haze(atmosphere(settings(c -> c.fogHeight = 512), frame(NOON, 0, 0, false, FOG, 250, new Matrix4f()), true), side, 3000, 1).transmittance()[1];
        assertTrue(low > high, low + " " + high);
        //Rain thickens the haze; far terrain is hazed, near terrain hardly
        AtmosphereMath.Atmosphere clear = sky(NOON, 0);
        assertTrue(AtmosphereMath.haze(sky(NOON, 1), side, 1000, 1).transmittance()[1] < AtmosphereMath.haze(clear, side, 1000, 1).transmittance()[1]);
        assertTrue(AtmosphereMath.haze(clear, side, 8, 1).transmittance()[1] > 0.99f);
        assertTrue(AtmosphereMath.haze(clear, side, 6000, 1).transmittance()[1] < 0.8f);
    }

    @Test
    void fogStrengthZeroOrAmountZeroGivesNoHaze() {
        PostProcessSettings off = settings(c -> c.fogStrength = 0);
        for (boolean covers : new boolean[]{false, true}) {
            for (boolean hasSky : new boolean[]{true, false}) {
                AtmosphereMath.Atmosphere a = atmosphere(off, frame(SUNSET, 1, 1, covers, FOG, 80, new Matrix4f()), hasSky);
                AtmosphereMath.Atmosphere on = atmosphere(SETTINGS, frame(SUNSET, 1, 1, covers, FOG, 80, new Matrix4f()), hasSky);
                for (float[] d : sphere()) {
                    for (float dist : DISTANCES) {
                        AtmosphereMath.Haze h = AtmosphereMath.haze(a, d, dist, 1);
                        assertArrayEquals(new float[]{1, 1, 1}, h.transmittance(), 0f, "fog strength 0");
                        assertArrayEquals(new float[3], h.inscatter(), 0f);
                        AtmosphereMath.Haze none = AtmosphereMath.haze(on, d, dist, 0);
                        assertArrayEquals(new float[]{1, 1, 1}, none.transmittance(), 0f, "amount 0");
                        assertArrayEquals(new float[3], none.inscatter(), 0f);
                    }
                }
            }
        }
    }

    @Test
    void farTerrainTurnsIntoTheSkyBehindIt() {
        for (float t : new float[]{NOON, SUNSET, MIDNIGHT}) {
            for (float rain : new float[]{0, 1}) {
                AtmosphereMath.Atmosphere a = sky(t, rain);
                for (float[] d : List.of(dir(0, 0.05f, 1), dir(-1, 0.02f, 0), dir(1, 0.3f, 0.2f), dir(0.2f, -0.4f, 1), dir(0, 1, 0))) {
                    //A black surface very far away is the sky pixel (sky and glow) behind it
                    float[] far = AtmosphereMath.applyHaze(a, new float[3], d, 1e7f, 1);
                    float[] skyPixel = AtmosphereMath.skyColour(a, d);
                    float[] glow = AtmosphereMath.sunGlow(a, d);
                    for (int i = 0; i < 3; i++) assertEquals(skyPixel[i] + glow[i], far[i], 2e-3f * (skyPixel[i] + glow[i]) + 1e-6f, "t " + t);
                    //Below the horizon any surface is hidden in the ground fog
                    if (d[1] < 0) {
                        float[] lit = AtmosphereMath.applyHaze(a, new float[]{0.3f, 0.2f, 0.1f}, d, 1e7f, 1);
                        for (int i = 0; i < 3; i++) assertEquals(far[i], lit[i], 1e-3f * far[i] + 1e-6f);
                    }
                }
            }
        }
    }

    /**
     * Final fix R1, R2: slot 29 is x the look weight (the sun-shading setting, 0 with the post-processing off), y 1 with the camera in
     * water (the frame's fluid), z the overcast share and w the mode the lighting's exposures follow, both as if the atmosphere were
     * drawn; with every atmosphere setting that draws something at 0 the composite's block is NONE (vanilla's sky kept), the
     * reflection's own block stays W4's sky for reflected rays.
     */
    @Test
    void theLookSlotAndTheNoneModeWhenNothingIsDrawn() {
        PostProcessSettings nothing = settings(c -> {
            c.fogStrength = 0;
            c.sunGlow = 0;
            c.sunsetLight = 0;
            c.lightShafts = 0;
            c.moonlight = 0;
            c.farClouds = false;
            c.sunShading = 40;
        });
        assertTrue(AtmosphereUniforms.drawsNothing(nothing));
        assertFalse(AtmosphereUniforms.drawsNothing(SETTINGS));
        AtmosphereFrame rain = frame(NOON, 1);
        AtmosphereUniforms.Values none = AtmosphereUniforms.compute(nothing, rain, SERIAL, true, false);
        assertEquals(AtmosphereMath.ATMOSPHERE_NONE, none.mode());
        assertFalse(AtmosphereMath.atmosphereOn(none.atmosphere()));
        assertEquals(0f, none.shafts());
        assertArrayEquals(new float[]{0.4f, 0, AtmosphereUniforms.RAIN_GREY, AtmosphereMath.ATMOSPHERE_SKY}, none.look(), 1e-6f);
        assertEquals(AtmosphereMath.ATMOSPHERE_SKY, AtmosphereUniforms.block(nothing, rain, true)[3], "the reflection keeps W4's sky");
        AtmosphereUniforms.Values sky = AtmosphereUniforms.compute(SETTINGS, rain, SERIAL, true, false);
        assertArrayEquals(new float[]{1, 0, sky.atmosphere().sunLight()[3], AtmosphereMath.ATMOSPHERE_SKY}, sky.look(), 1e-6f);
        //The camera's fluid: only water counts
        for (AtmosphereFrame.CameraFluid fluid : AtmosphereFrame.CameraFluid.values()) {
            AtmosphereFrame f = frame(NOON, 0, 0, true, WATER_FOG, 50, new Matrix4f()).withCameraFluid(fluid);
            float[] look = AtmosphereUniforms.compute(SETTINGS, f, SERIAL, true, false).look();
            assertEquals(fluid == AtmosphereFrame.CameraFluid.WATER ? 1f : 0f, look[1], fluid.toString());
            assertEquals(AtmosphereMath.ATMOSPHERE_FOG, look[3]);
        }
        assertEquals(AtmosphereMath.ATMOSPHERE_NO_SKY, AtmosphereUniforms.compute(nothing, frame(NOON, 0), SERIAL, false, false).look()[3]);
        assertEquals(0f, AtmosphereUniforms.compute(settings(c -> c.enabled = false), rain, SERIAL, true, false).look()[0]);
        assertArrayEquals(new float[4], AtmosphereUniforms.compute(SETTINGS, null, SERIAL, true, false).look(), 0f, "no frame");
    }

    @Test
    void cloudsAreLitTintedAndHazed() {
        AtmosphereFrame noon = frame(NOON, 0);
        AtmosphereUniforms.Values atm = AtmosphereUniforms.compute(SETTINGS, noon, SERIAL, true, false);
        CloudUniforms.Values c = CloudUniforms.compute(SETTINGS, noon, atm, SERIAL, true, true, 32);
        assertTrue(c.on());
        float[] s = c.slots();
        assertEquals(192 + CloudMath.CLOUD_LIFT - 120, s[0], 1e-4f);
        assertEquals(CloudMath.CLOUD_THICKNESS, s[1], 0f);
        assertEquals(32 * CloudMath.LOD_BLOCKS_PER_SECTION, s[2], 0f);
        assertEquals(1f, s[3], 0f);
        assertEquals(100f, s[4], 0f);
        assertEquals(200f, s[5], 0f);
        AtmosphereMath.CloudLight light = c.light();
        //A fully lit, fully covering cloud at noon is near-white, below a white surface in full daylight, above the sky
        float[] full = AtmosphereMath.cloudColour(light, new float[]{1, 1, 50});
        assertTrue(lum(full) > 0.6f && lum(full) <= 1.0f, Arrays.toString(full));
        assertTrue(lum(full) > lum(AtmosphereMath.skyColour(atm.atmosphere(), new float[]{0, 1, 0})));
        //Lit from the sun by day, from the moon at night
        assertArrayEquals(new float[]{atm.atmosphere().sun()[0], atm.atmosphere().sun()[1], atm.atmosphere().sun()[2], 0}, light.light(), 1e-6f);
        AtmosphereFrame night = frame(MIDNIGHT, 0);
        CloudUniforms.Values n = CloudUniforms.compute(SETTINGS, night, AtmosphereUniforms.compute(SETTINGS, night, SERIAL, true, false), SERIAL, true, true, 32);
        assertTrue(n.light().light()[1] > 0.99f, "the moon is up");
        //Sunset clouds are warm on the lit side
        AtmosphereFrame sunset = frame(SUNSET, 0);
        float[] lit = CloudUniforms.compute(SETTINGS, sunset, AtmosphereUniforms.compute(SETTINGS, sunset, SERIAL, true, false), SERIAL, true, true, 32).light().lit();
        assertTrue(lit[0] > lit[2]);
        //No coverage: nothing over the sky; hazed far away the cloud takes the haze
        assertArrayEquals(new float[4], AtmosphereMath.cloudOver(atm.atmosphere(), light, new float[]{0, 0, 0}, dir(0, 0.1f, 1), 1), 0f);
        //(final fix R3: the default fog height is now 40, under which a cloud 1e6 blocks out at 0.6 degrees still shows a little; with a
        // deep ground fog, fog height 96, it is gone into the sky behind it)
        AtmosphereUniforms.Values deepFog = AtmosphereUniforms.compute(settings(x -> x.fogHeight = 96), noon, SERIAL, true, false);
        for (AtmosphereUniforms.Values v : new AtmosphereUniforms.Values[]{atm, deepFog}) {
            float[] farCloud = AtmosphereMath.cloudOver(v.atmosphere(), light, new float[]{1, 1, 1e6f}, dir(0, 0.01f, 1), 1);
            float[] skyThere = AtmosphereMath.skyColour(v.atmosphere(), dir(0, 0.01f, 1));
            float[] glowThere = AtmosphereMath.sunGlow(v.atmosphere(), dir(0, 0.01f, 1));
            for (int i = 0; i < 3; i++) skyThere[i] += glowThere[i];
            if (v == deepFog) assertEquals(lum(skyThere), lum(farCloud), 0.02f * lum(skyThere));
            else assertTrue(Math.abs(lum(farCloud) - lum(skyThere)) < 0.5f * Math.abs(lum(full) - lum(skyThere)), "most of the way into the sky");
        }
        //Off: the setting, the map, no LODs, no vanilla cloud layer
        assertFalse(CloudUniforms.compute(settings(x -> x.farClouds = false), noon, atm, SERIAL, true, true, 32).on());
        assertFalse(CloudUniforms.compute(SETTINGS, noon, atm, SERIAL, true, false, 32).on());
        assertFalse(CloudUniforms.compute(SETTINGS, noon, atm, SERIAL, true, true, 0).on());
        AtmosphereFrame noLayer = noon.withClouds(null);
        assertFalse(CloudUniforms.compute(SETTINGS, noLayer, atm, SERIAL, true, true, 32).on());
        assertEquals(0f, CloudUniforms.compute(SETTINGS, noLayer, atm, SERIAL, true, true, 32).slots()[3], 0f, "cloudShape.w 0 when off");
    }

    // ---- 5. light colours ----

    @Test
    void sunlightIsWarmerLowThanAtNoonAndTheShadeIsCooler() {
        float[] noon = ColourMath.sunLight(1), low = ColourMath.sunLight(0.05f);
        assertTrue(low[0] / low[2] > noon[0] / noon[2], "warmer at a low sun");
        assertTrue(noon[0] >= noon[2], "noon sunlight is white to slightly warm");
        for (float h = -1; h <= 1; h += 0.01f) {
            float[] c = ColourMath.sunLight(h);
            assertFiniteNonNegative(c, "h " + h);
            assertEquals(1f, lum(c), 1e-4f);
        }
        assertFiniteNonNegative(ColourMath.sunLight(Float.NaN), "NaN");
        float[] shade = ColourMath.SKY_SHADE;
        assertFiniteNonNegative(shade, "shade");
        assertEquals(1f, lum(shade), 1e-4f);
        assertTrue(shade[2] / shade[0] > noon[2] / noon[0], "the shade is bluer than noon sunlight");
        assertTrue(shade[2] > shade[0]);
        //Warmth grows monotonically as the sun sinks
        float last = 0;
        for (float h = 1; h >= 0; h -= 0.01f) {
            float[] c = ColourMath.sunLight(h);
            assertTrue(c[0] / c[2] >= last - 1e-5f, "h " + h);
            last = c[0] / c[2];
        }
    }

    // ---- 6. screen-space shafts ----

    /** A camera looking along the sun (world), so the sun sits straight ahead. */
    private static Matrix4f lookingAt(float timeOfDay) {
        Vector3f sun = SunMath.sunDirection(timeOfDay).normalize();
        return new Matrix4f().lookAlong(sun, new Vector3f(0, 1, 0).add(0.001f, 0, 0.001f));
    }

    @Test
    void theShaftsRunOnlyWithTheSunUpAndTheSettingOn() {
        float morning = 0.85f;//sun height cos(0.85 * 2 pi) ~ 0.59, in the east
        AtmosphereFrame f = frame(morning, 0, 0, false, FOG, 80, lookingAt(morning));
        AtmosphereUniforms.Values v = AtmosphereUniforms.compute(SETTINGS, f, SERIAL, true, false);
        assertTrue(v.shafts() > 0);
        assertTrue(lum(v.shaftLight()) > 0);
        assertFiniteNonNegative(v.shaftLight(), "shaft light");
        //The sun straight ahead is at the screen centre
        LooseUniformWriter w = new LooseUniformWriter(meta("shafts.BLUR"));
        v.writeShafts(w);
        ByteBuffer b = w.buffer();
        int o = offsetOf("shafts.BLUR", 0);
        assertEquals(0.5f, b.getFloat(o), 1e-4f);
        assertEquals(0.5f, b.getFloat(o + 4), 1e-4f);
        //Row 0 at the top: looking a little below the sun puts it in the upper half
        Matrix4f below = new Matrix4f().rotateX(0.2f).mul(lookingAt(morning));
        AtmosphereUniforms.Values up = AtmosphereUniforms.compute(SETTINGS, frame(morning, 0, 0, false, FOG, 80, below), SERIAL, true, false);
        assertTrue(up.sunY() < 0.5f && up.sunY() > 0, "sun y " + up.sunY());
        //Off: the sun below the horizon, the setting at 0, the sun behind the camera, no sky, environmental fog
        Matrix4f behind = new Matrix4f().rotateY((float) Math.PI).mul(lookingAt(morning));
        List<AtmosphereUniforms.Values> off = List.of(
                AtmosphereUniforms.compute(SETTINGS, frame(MIDNIGHT, 0, 0, false, FOG, 80, lookingAt(MIDNIGHT)), SERIAL, true, false),
                AtmosphereUniforms.compute(SETTINGS, frame(0.26f, 0, 0, false, FOG, 80, lookingAt(0.26f)), SERIAL, true, false),
                AtmosphereUniforms.compute(settings(c -> c.lightShafts = 0), f, SERIAL, true, false),
                AtmosphereUniforms.compute(SETTINGS, frame(morning, 0, 0, false, FOG, 80, behind), SERIAL, true, false),
                AtmosphereUniforms.compute(SETTINGS, f, SERIAL, false, false),
                AtmosphereUniforms.compute(SETTINGS, frame(morning, 0, 0, true, FOG, 80, lookingAt(morning)), SERIAL, true, false),
                AtmosphereUniforms.compute(SETTINGS, null, SERIAL, true, false));
        for (AtmosphereUniforms.Values x : off) {
            assertEquals(0f, x.shafts(), 0f);
            assertArrayEquals(new float[3], x.shaftLight(), 0f, "the shaft light is exactly 0 whenever the shafts do not run");
        }
        //Rain weakens them; far off screen they stop
        AtmosphereUniforms.Values rain = AtmosphereUniforms.compute(SETTINGS, frame(morning, 1, 0, false, FOG, 80, lookingAt(morning)), SERIAL, true, false);
        assertTrue(rain.shafts() < v.shafts() && rain.shafts() > 0);
        Matrix4f aside = new Matrix4f().rotateY(1.2f).mul(lookingAt(morning));
        assertEquals(0f, AtmosphereUniforms.compute(SETTINGS, frame(morning, 0, 0, false, FOG, 80, aside), SERIAL, true, false).shafts(), 0f);
    }

    // ---- 7. constants and slots ----

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        String text = ShaderFiles.read("post/atmosphere.glsl");
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
        int found = 0;
        while (m.find()) {
            Field f = AtmosphereMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(m.group(1).equals("int") ? int.class : float.class, f.getType(), m.group(2));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 20, "constants found: " + found);
        for (Field f : AtmosphereMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class)) continue;
            String type = f.getType() == int.class ? "int" : "float";
            assertTrue(Pattern.compile("const\\s+" + type + "\\s+" + f.getName() + "\\b").matcher(text).find(), f.getName() + " missing in atmosphere.glsl");
        }
        //The struct's field order is the block's (AtmosphereMath.Atmosphere, AtmosphereUniforms.COPY_SLOTS)
        assertTrue(Pattern.compile("struct Atmosphere \\{\\s*vec4 sun;\\s*vec4 sunLight;\\s*vec4 moonLight;\\s*vec4 depth;\\s*vec4 ambient;\\s*vec4 glow;\\s*vec4 fog;\\s*vec4 fogTint;\\s*vec4 overcastTint;\\s*vec4 moonTint;\\s*};")
                .matcher(text).find());
        assertTrue(Pattern.compile("struct CloudLight \\{\\s*vec4 lit;\\s*vec4 ambient;\\s*vec4 light;\\s*};").matcher(text).find());
        List<String> components = Arrays.stream(AtmosphereMath.Atmosphere.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("sun", "sunLight", "moonLight", "depth", "ambient", "glow", "fog", "fogTint", "overcastTint", "moonTint"), components);
        //The functions the composite and the reflection are written from
        for (String sig : List.of("vec3 skyColour(Atmosphere a, vec3 dir)", "vec3 sunGlow(Atmosphere a, vec3 dir)",
                "void haze(Atmosphere a, vec3 dir, float dist, float amount, out vec3 transmittance, out vec3 inscatter)",
                "vec3 applyHaze(Atmosphere a, vec3 colour, vec3 dir, float dist, float amount)", "vec4 cloudColour(CloudLight c, vec4 m)",
                "vec4 cloudOver(Atmosphere a, CloudLight c, vec4 m, vec3 dir, float amount)", "float hazeAmount(Atmosphere a, float skyLight, bool isLod)",
                "bool atmosphereOn(Atmosphere a)", "vec3 fogColour(Atmosphere a, vec3 dir)")) {
            assertTrue(text.contains(sig), sig);
        }
        //The shafts' sampling stays soft: many steps, a decay near 1 whose total keeps a tenth or more, a threshold above the plain sky
        //Review round 1: 32..48 bilinear taps per half-resolution texel (budget), with the walk's total weight kept near 0.19
        assertTrue(AtmosphereMath.SHAFT_SAMPLES >= 32 && AtmosphereMath.SHAFT_SAMPLES <= 48);
        //No rings, assuming FOV 70 at 2560x1440 and a walk of up to 1.2 screen widths (1536 half-resolution texels): the longest step
        // stays under half the vanilla sun disc's radius (about 77 half-resolution texels), with a 5% margin
        assertTrue(1280.0 * 1.2 / AtmosphereMath.SHAFT_SAMPLES * 1.05 < 77 / 2.0, "step " + 1280.0 * 1.2 / AtmosphereMath.SHAFT_SAMPLES);
        double total = Math.pow(AtmosphereMath.SHAFT_DECAY, AtmosphereMath.SHAFT_SAMPLES);
        assertTrue(total > 0.1 && total < 0.5, "decay over the walk " + total);
        //The plain sky stays at least 10% under the threshold at every sun height (0.02..1 in steps of 0.02, the sunset, morning and
        // afternoon), clear or raining, at the default and the player's settings (its peak: about 1.33 toward a sun at height 0.16)
        List<Float> times = new ArrayList<>(List.of(0f, SUNSET, 1 - SUNSET, 0.85f, 0.95f));
        for (float h = 0.02f; h <= 1.001f; h += 0.02f) times.add((float) (Math.acos(Math.min(h, 1)) / (2 * Math.PI)));
        for (PostProcessSettings s : List.of(SETTINGS, PLAYER)) {
            for (float t : times) {
                for (float rain : new float[]{0, 0.5f, 1}) {
                    AtmosphereMath.Atmosphere a = atmosphere(s, frame(t, rain), true);
                    float brightest = 0;
                    for (float[] d : sphere()) brightest = Math.max(brightest, lum(AtmosphereMath.skyColour(a, d)));
                    assertTrue(AtmosphereMath.SHAFT_THRESHOLD > brightest * 1.1f, "the plain sky casts no beams at t " + t + " rain " + rain + ": " + brightest);
                }
            }
        }
        //Near a low sun the glow core does, at the player's settings (S2's shafts without a disc): 5 degrees above the S2 sun
        AtmosphereMath.Atmosphere low = atmosphere(PLAYER, frame(S2_SUN, 0), true);
        float[] near = fromWest(Math.toDegrees(Math.asin(0.18)) + 5, 0);
        assertTrue(lum(AtmosphereMath.skyColour(low, near)) + lum(AtmosphereMath.sunGlow(low, near)) > AtmosphereMath.SHAFT_THRESHOLD);
        //the sun's glow core does (at noon and at sunset)
        for (float t : new float[]{NOON, SUNSET}) {
            AtmosphereMath.Atmosphere a = sky(t, 0);
            float[] s = a.sun(), d = {s[0], s[1], s[2]};
            float[] core = AtmosphereMath.skyColour(a, d), glow = AtmosphereMath.sunGlow(a, d);
            assertTrue(lum(core) + lum(glow) > AtmosphereMath.SHAFT_THRESHOLD, "t " + t);
        }
    }

    private static VulkanShaderMeta meta(String program) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    private static int offsetOf(String program, int location) {
        for (VulkanShaderMeta.PushMember m : meta(program).push()) if (m.location() == location) return m.offset();
        throw new AssertionError(program + " has no location " + location);
    }

    private static int size(String type) {
        return type.equals("mat4") ? 16 : type.equals("vec4") ? 4 : type.equals("vec2") ? 2 : 1;
    }

    /** Every declared member NaN, then the writer, then the given locations must hold no NaN. */
    private static void assertWrites(String program, java.util.function.Consumer<LooseUniformWriter> write, List<Integer> locations) {
        VulkanShaderMeta meta = meta(program);
        LooseUniformWriter w = new LooseUniformWriter(meta);
        ByteBuffer raw = w.buffer();
        for (int i = 0; i < raw.limit(); i += 4) raw.putFloat(i, Float.NaN);
        write.accept(w);
        ByteBuffer b = w.buffer();
        for (int loc : locations) {
            VulkanShaderMeta.PushMember member = meta.push().stream().filter(m -> m.location() == loc).findFirst()
                    .orElseThrow(() -> new AssertionError(program + " does not declare location " + loc));
            for (int i = 0; i < size(member.type()); i++) {
                assertFalse(Float.isNaN(b.getFloat(member.offset() + 4 * i)), program + " location " + loc + " component " + i + " not written");
            }
        }
    }

    @Test
    void everyOwnedSlotIsWrittenInTheCompositeAndTheReflection() {
        AtmosphereFrame f = frame(SUNSET, 0.3f, 0, false, FOG, 90, lookingAt(SUNSET));
        AtmosphereUniforms.Values v = AtmosphereUniforms.compute(SETTINGS, f, SERIAL, true, true);
        List<Integer> owned = new ArrayList<>(List.of(0, 1, 2, 8));
        for (int s : AtmosphereUniforms.COPY_SLOTS) owned.add(s);
        owned.add(AtmosphereUniforms.VANILLA_SKY);
        owned.add(AtmosphereUniforms.VANILLA_FOG);
        owned.add(AtmosphereUniforms.STAR_GATE);
        owned.add(AtmosphereUniforms.LOOK);
        owned.add(AtmosphereUniforms.BORDER);
        for (int s = AtmosphereUniforms.RESERVED_FIRST; s <= AtmosphereUniforms.RESERVED_LAST; s++) owned.add(s);
        assertEquals(List.of(26, 27, 28, 29, 30, 34, 34), List.of(AtmosphereUniforms.VANILLA_SKY, AtmosphereUniforms.VANILLA_FOG, AtmosphereUniforms.STAR_GATE,
                AtmosphereUniforms.LOOK, AtmosphereUniforms.BORDER, AtmosphereUniforms.RESERVED_FIRST, AtmosphereUniforms.RESERVED_LAST),
                "25..34 are W4's: 25 the struct, 26..28 the stars' inputs, 29 the look slot (final fix R1, R2), 30 the border fog (2026-10-21), 31..33 LightingUniforms' (2026-10-28), 34 reserved");
        assertEquals(List.of(3, 4, 5, 6, 7, 9, 25, 48, 49, 50), Arrays.stream(AtmosphereUniforms.COPY_SLOTS).boxed().toList(), "boundary 3.2: 3..7, 9, 25..34; session 8: the tints 48..50");
        assertWrites("postSceneCopy", v::write, owned);
        //The values: depthParams (fixed meaning) and the block
        LooseUniformWriter w = new LooseUniformWriter(meta("postSceneCopy"));
        v.write(w);
        int o8 = offsetOf("postSceneCopy", 8);
        assertEquals(net.vulkanmod.visuals.lod.LodClamp.CLAMP_THRESHOLD, w.buffer().getFloat(o8), 0f);
        float[] block = v.block();
        for (int i = 0; i < AtmosphereUniforms.COPY_SLOTS.length; i++) {
            int o = offsetOf("postSceneCopy", AtmosphereUniforms.COPY_SLOTS[i]);
            for (int c = 0; c < 4; c++) assertEquals(block[i * 4 + c], w.buffer().getFloat(o + 4 * c), 0f);
        }
        //The cloud block in both programs, also when off
        CloudUniforms.Values on = CloudUniforms.compute(SETTINGS, f, v, SERIAL, true, true, 32);
        CloudUniforms.Values off = CloudUniforms.compute(SETTINGS, null, v, SERIAL, true, true, 32);
        for (CloudUniforms.Values c : List.of(on, off)) {
            List<Integer> copy = new ArrayList<>(), reflection = new ArrayList<>();
            for (int i = 0; i < CloudUniforms.SLOTS; i++) {
                copy.add(CloudUniforms.COPY_FIRST + i);
                reflection.add(CloudUniforms.REFLECTION_FIRST + i);
            }
            assertWrites("postSceneCopy", x -> c.write(x, CloudUniforms.COPY_FIRST), copy);
            assertWrites("postReflection", x -> c.write(x, CloudUniforms.REFLECTION_FIRST), reflection);
        }
        //The shafts' location 0 in both shaft programs
        assertWrites("shafts.MASK", v::writeShafts, List.of(0));
        assertWrites("shafts.BLUR", v::writeShafts, List.of(0));
        //The reflection's copy of the block through writeBlock lands where W6 asks
        assertWrites("postReflection", x -> AtmosphereUniforms.writeBlock(x, v.block(), 4, 5, 6, 7, 9, 10, 23), List.of(4, 5, 6, 7, 9, 10, 23));
        assertArrayEquals(v.block(), AtmosphereUniforms.block(SETTINGS, f, true), 0f, "the reflection's block is the composite's");
    }

    // ---- review round 1: magnitudes against the references, the haze amount, night clouds ----

    /** Tuning round 1: the sun's height in refs/S2.png, about 10.7 degrees (sun disc 20 degrees above the view centre, horizon 9.3 below). */
    private static final float S2_SUN = (float) (Math.acos(0.18) / (2 * Math.PI));

    /** The player's settings of refs/scenes.md (the references were taken with them). */
    private static final PostProcessSettings PLAYER = settings(c -> {
        c.sunsetLight = 50;
        c.sunGlow = 50;
        c.moonlight = 50;
        c.fogHeight = 40;
    });

    /** A direction at an elevation (degrees) and an angle (degrees) from the western sunset sun's azimuth around the horizon. */
    private static float[] fromWest(double elevation, double angle) {
        double e = Math.toRadians(elevation), a = Math.toRadians(angle);
        return new float[]{(float) (-Math.cos(e) * Math.cos(a)), (float) Math.sin(e), (float) (Math.cos(e) * Math.sin(a))};
    }

    /**
     * Bands from the references, read as linear luminance below W2's tone knee (refs S7: about 0.21 at 50 degrees up and 0.67 at the
     * horizon; S2/S13: about 0.30..0.50 pink-lavender at 35 degrees toward the setting sun and 0.75 near its horizon; S3: about 0.014 just
     * above the horizon at night), each with a margin for the post chain the references went through.
     */
    @Test
    void skyMagnitudesFallInTheReferencesBands() {
        AtmosphereFrame high = frame(NOON, 0, 0, false, FOG, 193, new Matrix4f());
        AtmosphereMath.Atmosphere noon = atmosphere(PLAYER, high, true);
        float noon50 = lum(AtmosphereMath.skyColour(noon, fromWest(50, 90)));
        float noonHorizon = lum(AtmosphereMath.skyColour(noon, fromWest(1, 90)));
        assertTrue(noon50 > 0.15f && noon50 < 0.32f, "S7 50 degrees " + noon50);
        assertTrue(noonHorizon > 0.45f && noonHorizon < 0.85f, "S7 horizon " + noonHorizon);
        //Tuning round 1: S2's sun is about 10.7 degrees up (height 0.18, from the reference image), not at the horizon
        AtmosphereMath.Atmosphere sunset = atmosphere(PLAYER, frame(S2_SUN, 0, 0, false, FOG, 193, new Matrix4f()), true);
        float[] s35 = AtmosphereMath.skyColour(sunset, fromWest(35, 0));
        assertTrue(lum(s35) > 0.3f && lum(s35) < 0.6f, "S2 35 degrees toward the sun " + lum(s35));
        //S2's top (197, 178, 179) was pink (red above blue above green). 2026-10-24 made it a warm cream (red above green above blue), which
        // with the rest of the sky the same cream read grey-white (2026-11-09, session 1a, "the sunrise is grey"). Since session 1a it is a
        // lavender: blue above green, the red near the green (the references at 30 degrees toward the sun: Luxium #FCCFE0, Complementary
        // Reimagined #A48288, Fantasy #CA7CB0, all red above blue above green), far from the noon sky's red
        assertTrue(s35[2] > s35[1] && s35[0] > 0.9f * s35[1], "lavender " + Arrays.toString(s35));
        float[] noon35 = AtmosphereMath.skyColour(noon, fromWest(35, 0));
        assertTrue(s35[0] / s35[1] > 1.2f * noon35[0] / noon35[1], "redder than noon " + Arrays.toString(s35) + " " + Arrays.toString(noon35));
        float[] s35side = AtmosphereMath.skyColour(sunset, fromWest(35, 40));
        assertTrue(lum(s35side) > 0.25f, "S2 the sky beside the sun " + lum(s35side));
        float[] nearSun = AtmosphereMath.skyColour(sunset, fromWest(3, 0)), nearGlow = AtmosphereMath.sunGlow(sunset, fromWest(3, 0));
        //2026-10-24: OZONE_SCALE 1 keeps more green, about 7% brighter here (the band was < 1.1)
        assertTrue(lum(nearSun) > 0.55f && lum(nearSun) < 1.25f && lum(nearSun) + lum(nearGlow) > 1.0f,
                "S2 near the sun's horizon " + lum(nearSun) + " + " + lum(nearGlow));
        //S2 near the sun (246, 233, 223): warm peach, red above green above blue
        assertTrue(nearSun[0] > nearSun[1] && nearSun[1] > nearSun[2], "warm peach toward the sun " + Arrays.toString(nearSun));
        //The halo stays coloured: 10 degrees from the sun the sky with its glow is not clipped to white (the core may clip)
        float[] halo = AtmosphereMath.skyColour(sunset, fromWest(Math.toDegrees(Math.asin(0.18)) + 10, 0));
        float[] haloGlow = AtmosphereMath.sunGlow(sunset, fromWest(Math.toDegrees(Math.asin(0.18)) + 10, 0));
        float hr = halo[0] + haloGlow[0], hg = halo[1] + haloGlow[1], hb = halo[2] + haloGlow[2];
        assertTrue(Math.max(hr, Math.max(hg, hb)) < 1.2f, "the halo 10 degrees out " + hr + " " + hg + " " + hb);
        assertTrue((Math.max(hr, Math.max(hg, hb)) - Math.min(hr, Math.min(hg, hb))) / Math.max(hr, Math.max(hg, hb)) > 0.15f, "and coloured");
        //The sunset sky stays bright up to the zenith: at least half the noon zenith
        float noonZenith = lum(AtmosphereMath.skyColour(noon, new float[]{0, 1, 0}));
        float sunsetZenith = lum(AtmosphereMath.skyColour(sunset, new float[]{0, 1, 0}));
        assertTrue(sunsetZenith >= 0.5f * noonZenith, sunsetZenith + " vs noon " + noonZenith);
        //Night (measured, not estimated): S3's top row is just above the horizon
        AtmosphereMath.Atmosphere night = atmosphere(PLAYER, frame(MIDNIGHT, 0, 0, false, FOG, 193, new Matrix4f()), true);
        float nightLow = lum(AtmosphereMath.skyColour(night, fromWest(5, 90)));
        float nightZenith = lum(AtmosphereMath.skyColour(night, new float[]{0, 1, 0}));
        assertTrue(nightLow > 0.007f && nightLow < 0.03f, "S3 near the horizon " + nightLow);
        assertTrue(nightZenith > 0.003f && nightZenith <= nightLow, "night zenith " + nightZenith);
        float[] nightC = AtmosphereMath.skyColour(night, fromWest(5, 90));
        assertTrue(nightC[2] > nightC[0], "a blue-grey night");
    }

    @Test
    void aSettingSunGradesFromOrangeTowardItToMauveAbove() {
        //2026-10-24 (CTE2: "the sunset is only red"; the user's pick v3): with the sun on the horizon the whole sky had the ground's red
        // sunlight
        float horizonSun = (float) (Math.acos(0.005) / (2 * Math.PI));
        AtmosphereMath.Atmosphere a = atmosphere(PLAYER, frame(horizonSun, 0, 0, false, FOG, 193, new Matrix4f()), true);
        float[] zenith = AtmosphereMath.skyColour(a, new float[]{0, 1, 0});
        float[] towardSun = AtmosphereMath.skyColour(a, fromWest(2, 0));
        float[] opposite = AtmosphereMath.skyColour(a, fromWest(30, 180));
        assertTrue(zenith[2] > zenith[1] && zenith[2] > 0.5f * zenith[0], "a mauve zenith " + Arrays.toString(zenith));
        assertTrue(towardSun[0] > towardSun[1] && towardSun[1] > towardSun[2], "orange toward the sun " + Arrays.toString(towardSun));
        //2026-11-09 (session 1a): burnt orange, as the references keep the sky of a sun on the horizon (Luxium #B96044: green about a
        // quarter of the red in linear light), not a pure red
        assertTrue(towardSun[1] > 0.15f * towardSun[0], "with its green kept, not a pure red " + Arrays.toString(towardSun));
        assertTrue(zenith[2] / zenith[0] > 2 * towardSun[2] / towardSun[0], "bluer up than toward the sun");
        assertTrue(opposite[2] / opposite[0] > towardSun[2] / towardSun[0], "bluer away from the sun");
        //2026-11-09 (session 1a): the lift works at every sun height (it stopped at the S2 reference's 0.18, where the zenith took the
        // ground's reddened light), on the reddening air mass the sunset light setting scales
        AtmosphereMath.Atmosphere high = atmosphere(PLAYER, frame(S2_SUN, 0, 0, false, FOG, 193, new Matrix4f()), true);
        assertEquals(AtmosphereMath.airMass(0.18f), high.fog()[3], 1e-3f);
        assertEquals(AtmosphereUniforms.SUN_MAX_AIR_MASS, a.fog()[3], 1e-3f, "capped on the horizon");
    }

    /** The low sun's sky at a time of day: its horizon toward the sun (1 degree up), beside it, away from it, and its top. */
    private static float[][] lowSky(float timeOfDay) {
        AtmosphereMath.Atmosphere a = atmosphere(SETTINGS, frame(timeOfDay, 0, 0, false, FOG, 130, new Matrix4f()), true);
        float sx = Math.signum(a.sun()[0]);
        float[] toward = {sx * (float) Math.cos(Math.toRadians(1)), (float) Math.sin(Math.toRadians(1)), 0};
        float[] side = {0, (float) Math.sin(Math.toRadians(1)), (float) Math.cos(Math.toRadians(1))};
        float[] away = {-toward[0], toward[1], 0};
        float[] top = {sx * (float) Math.cos(Math.toRadians(40)), (float) Math.sin(Math.toRadians(40)), 0};
        return new float[][]{AtmosphereMath.skyColour(a, toward), AtmosphereMath.skyColour(a, side), AtmosphereMath.skyColour(a, away),
                AtmosphereMath.skyColour(a, top), AtmosphereMath.skyColour(a, new float[]{0, 1, 0})};
    }

    /** A Minecraft time (ticks) as vanilla's time of day (0 noon). */
    private static float ticks(int t) {
        double d = ((t / 24000.0 - 0.25) % 1 + 1) % 1;
        return (float) ((2 * d + (0.5 - Math.cos(d * Math.PI) / 2)) / 3);
    }

    @Test
    void theLowSunsSkyGradesByElevationAndDirection() {
        //2026-11-09 (session 1a, the roadmap's SS2/SS3 sunsets and SR2 sunrise; refs/anchor/observations.md): the references put a warm
        // band at the horizon toward the sun, keep the sky away from it cooler, and go lavender to blue toward the top. The build drew one
        // cream-grey over the whole sky (SS2: red minus blue 18 at the horizon and at 30 degrees alike)
        for (int t : new int[]{12200, 12500, 23500}) {
            float[][] c = lowSky(ticks(t));
            float[] toward = c[0], away = c[2], top = c[3], zenith = c[4];
            assertTrue(toward[0] > 1.8f * toward[2] && toward[0] > toward[1] && toward[1] > toward[2], t + " a warm horizon toward the sun " + Arrays.toString(toward));
            assertTrue(top[2] > top[1], t + " a cool top " + Arrays.toString(top));
            assertTrue(zenith[2] > zenith[0] && zenith[2] > zenith[1], t + " a blue zenith " + Arrays.toString(zenith));
            assertTrue(away[2] / away[0] > toward[2] / toward[0], t + " cooler away from the sun " + Arrays.toString(away));
            assertTrue(top[2] / top[0] > 2 * toward[2] / toward[0], t + " cooler up than at the horizon");
        }
    }

    @Test
    void theSkyAroundASetSunIsBlueNotOneRed() {
        //2026-11-09 (session 1a, "the sunset is only red"; SS4 and SR1): with the sun on or just under the horizon the build drew every
        // direction one maroon (red 1.5 to 1.8 times blue, the side views as red as the sun's). The references keep a burnt orange band at the
        // horizon toward the sun and a slate blue sky everywhere else (Luxium SS4 #596698 at the top, #6B6C7F away from the sun)
        for (int t : new int[]{12800, 23000}) {
            float[][] c = lowSky(ticks(t));
            float[] toward = c[0], side = c[1], away = c[2], top = c[3], zenith = c[4];
            //SS4 burnt orange; SR1 (sun 3.5 degrees down) a dusky warm arch, between Luxium's red-orange and Complementary's night
            if (t == 12800) assertTrue(toward[0] > 1.5f * toward[2], t + " warm toward the sun " + Arrays.toString(toward));
            assertTrue(toward[0] / toward[2] > 1.5f * away[0] / away[2], t + " warmer toward the sun " + Arrays.toString(toward) + " " + Arrays.toString(away));
            for (float[] blue : new float[][]{side, away, top, zenith}) {
                assertTrue(blue[2] > blue[0] && blue[2] > blue[1], t + " blue elsewhere " + Arrays.toString(blue));
            }
            //and not black: the twilight sky after sunset stays readable (about Complementary Reimagined's, darker than Luxium's)
            assertTrue(lum(zenith) > 0.02f, t + " zenith " + lum(zenith));
        }
    }

    @Test
    void theSunriseIsCoolerAndBrighterThanTheSunsetAtTheSameHeight() {
        //2026-11-09 (session 1a, the roadmap's direction): SR2 (23500) and SS3 (12500) have the same sun height
        float[][] rise = lowSky(ticks(23500)), set = lowSky(ticks(12500));
        assertTrue(rise[0][2] / rise[0][0] > set[0][2] / set[0][0], "a cooler horizon " + Arrays.toString(rise[0]) + " " + Arrays.toString(set[0]));
        assertTrue(lum(rise[4]) > lum(set[4]), "a brighter zenith " + lum(rise[4]) + " " + lum(set[4]));
    }

    @Test
    void theLowHazeBandIsOnlyThereWhileTheSunIsLow() {
        //2026-11-09 (session 1a): no band at noon, at midnight or under a full overcast, so those skies keep their tuning
        for (float t : new float[]{NOON, MIDNIGHT}) {
            AtmosphereMath.Atmosphere a = sky(t, 0);
            assertEquals(0f, AtmosphereMath.lowBand(a, AtmosphereMath.SKY_MAX_AIR_MASS, AtmosphereMath.SKY_MAX_AIR_MASS), 0f, "t " + t);
        }
        AtmosphereMath.Atmosphere low = sky(ticks(12500), 0);
        assertTrue(AtmosphereMath.lowBand(low, AtmosphereMath.SKY_MAX_AIR_MASS, AtmosphereMath.SKY_MAX_AIR_MASS) > 3, "opaque on the horizon");
        assertTrue(AtmosphereMath.lowBand(low, 1, 1) < 0.05f, "next to nothing at the zenith");
        AtmosphereMath.Atmosphere rain = atmosphere(SETTINGS, frame(ticks(12500), 1), true);
        assertTrue(AtmosphereMath.lowBand(rain, AtmosphereMath.SKY_MAX_AIR_MASS, AtmosphereMath.SKY_MAX_AIR_MASS) < 0.25f * AtmosphereMath.lowBand(low, AtmosphereMath.SKY_MAX_AIR_MASS, AtmosphereMath.SKY_MAX_AIR_MASS), "rain greys it out");
        //Its light keeps the sky's sunlight's luminance (a warmer hue, not more light) and is finite and non-negative
        float[] l = AtmosphereMath.lowLight(low), sl = low.sunLight();
        assertEquals(lum(new float[]{sl[0], sl[1], sl[2]}), lum(l), 1e-4f);
        assertTrue(l[0] / l[2] > sl[0] / sl[2], "redder than the sky's sunlight");
        assertArrayEquals(new float[3], AtmosphereMath.lowLight(sky(MIDNIGHT, 0)), 0f, "no sunlight, no band light");
    }

    @Test
    void theHazeAmountFollowsTheModeTheSkyLightAndLods() {
        AtmosphereMath.Atmosphere sky = sky(NOON, 0);
        assertEquals(0.4f, AtmosphereMath.hazeAmount(sky, 0.4f, false), 0f);
        assertEquals(0f, AtmosphereMath.hazeAmount(sky, Float.NaN, false), 0f, "NaN sky light counts as 0");
        assertEquals(1f, AtmosphereMath.hazeAmount(sky, 3f, false), 0f);
        assertEquals(1f, AtmosphereMath.hazeAmount(sky, 0f, true), 0f, "LOD pixels: full haze");
        AtmosphereMath.Atmosphere fog = atmosphere(SETTINGS, frame(NOON, 0, 0, true, WATER_FOG, 50, new Matrix4f()), true);
        assertEquals(0f, AtmosphereMath.hazeAmount(fog, 1f, false), 0f, "vanilla's fog is already in vanilla pixels");
        assertEquals(1f, AtmosphereMath.hazeAmount(fog, 0f, true), 0f);
        AtmosphereMath.Atmosphere noSky = atmosphere(SETTINGS, frame(NOON, 0), false);
        assertEquals(1f, AtmosphereMath.hazeAmount(noSky, 0f, false), 0f);
        assertEquals(0f, AtmosphereMath.hazeAmount(AtmosphereUniforms.compute(SETTINGS, null, SERIAL, true, false).atmosphere(), 1f, true), 0f);
        //NaN distance or amount: no haze, no NaN
        AtmosphereMath.Haze h = AtmosphereMath.haze(sky, dir(0, 0, 1), Float.NaN, 1);
        assertArrayEquals(new float[]{1, 1, 1}, h.transmittance(), 0f);
        h = AtmosphereMath.haze(sky, dir(0, 0, 1), 1000, Float.NaN);
        assertArrayEquals(new float[]{1, 1, 1}, h.transmittance(), 0f);
        assertArrayEquals(new float[3], h.inscatter(), 0f);
        float[] c = AtmosphereMath.cloudColour(new AtmosphereMath.CloudLight(new float[]{1, 1, 1, 0}, new float[]{0.1f, 0.1f, 0.1f, 0}, new float[]{0, 1, 0, 0}),
                new float[]{Float.NaN, Float.NaN, 0});
        assertArrayEquals(new float[4], c, 0f);
    }

    @Test
    void nightCloudsAreDarkBecauseVanillasCloudColourIs() {
        //Vanilla's getCloudColor at midnight in clear weather: (0.1, 0.1, 0.15)
        AtmosphereFrame night = new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(PROJ),
                120, 63, FOG[0], FOG[1], FOG[2], false, false, MIDNIGHT, 0, 0, null, PLAINS_SKY.clone(), 256, null,
                new CloudFrame(192, 0, 0, new float[]{0.1f, 0.1f, 0.15f}));
        CloudUniforms.Values c = CloudUniforms.compute(SETTINGS, night, AtmosphereUniforms.compute(SETTINGS, night, SERIAL, true, false), SERIAL, true, true, 32);
        float[] full = AtmosphereMath.cloudColour(c.light(), new float[]{1, 1, 50});
        assertTrue(lum(full) < 0.02f, "night clouds " + lum(full));
        assertTrue(lum(full) > 0, "but not black");
        //The vanilla colours the composite keeps the stars by: slots 26 and 27
        float[] v = AtmosphereUniforms.compute(SETTINGS, night, SERIAL, true, false).vanilla();
        assertEquals(PostMath.displayToLinear(PLAINS_SKY[2]), v[2], 1e-6f);
        assertEquals(1f, v[3], 0f);
        assertEquals(PostMath.displayToLinear(FOG[0]), v[4], 1e-6f);
        assertEquals(1f, v[7], 0f);
    }

    // ---- review round 2: vanilla's stars without its sunrise fan ----

    private static float[] scene(float r, float g, float b) {
        return new float[]{PostMath.displayToLinear(r), PostMath.displayToLinear(g), PostMath.displayToLinear(b)};
    }

    private static float[] slot(float[] values, int vec4) {
        return Arrays.copyOfRange(values, vec4 * 4, vec4 * 4 + 4);
    }

    @Test
    void vanillasStarsAreKeptAndItsSunsetFanIsNot() {
        //Sunset: vanilla's dusk sky and fog, its fan (sunrise colour alpha 0.9) toward the western sun
        float[] duskSky = {0.25f, 0.33f, 0.55f}, duskFog = {0.55f, 0.45f, 0.45f};
        AtmosphereFrame sunset = new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(PROJ),
                120, 63, duskFog[0], duskFog[1], duskFog[2], false, false, SUNSET, 0, 0, new float[]{1f, 0.55f, 0.2f, 0.9f}, duskSky, 256, null, null);
        AtmosphereUniforms.Values v = AtmosphereUniforms.compute(SETTINGS, sunset, SERIAL, true, false);
        float[] gate = slot(v.vanilla(), 2);
        assertEquals(0.9f, gate[1], 1e-6f, "the fan's strength");
        AtmosphereMath.Atmosphere a = v.atmosphere();
        //Fan-coloured vanilla pixels toward the sun, beside it and at the top of the fan: no residue
        for (float[] d : List.of(fromWest(3, 0), fromWest(10, 30), fromWest(20, 60), fromWest(5, -45))) {
            for (float[] fanPixel : List.of(scene(0.95f, 0.6f, 0.4f), scene(0.9f, 0.7f, 0.65f), scene(1f, 0.8f, 0.8f))) {
                float[] r = AtmosphereMath.vanillaStars(a, d, fanPixel, slot(v.vanilla(), 0), slot(v.vanilla(), 1), gate);
                assertArrayEquals(new float[3], r, 1e-6f, "the fan must not come back over the sunset sky");
            }
        }
        //Night: a white star pixel over vanilla's dark sky leaves a residue, in every direction (the fan is gone)
        float[] nightSky = {0.02f, 0.02f, 0.05f}, nightFog = {0.03f, 0.04f, 0.08f};
        AtmosphereFrame night = new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(PROJ),
                120, 63, nightFog[0], nightFog[1], nightFog[2], false, false, MIDNIGHT, 0, 0, null, nightSky, 256, null, null);
        AtmosphereUniforms.Values n = AtmosphereUniforms.compute(SETTINGS, night, SERIAL, true, false);
        float[] star = scene(0.8f, 0.8f, 0.8f);
        for (float[] d : sphere()) {
            if (d[1] < 0.05f) continue;
            float[] r = AtmosphereMath.vanillaStars(n.atmosphere(), d, star, slot(n.vanilla(), 0), slot(n.vanilla(), 1), slot(n.vanilla(), 2));
            float want = PostMath.displayToLinear(0.8f) - PostMath.displayToLinear(0.08f);
            for (float f : r) assertEquals(want, f, 1e-5f, "a star keeps its brightness above vanilla's sky");
        }
        //The plain night sky leaves nothing; by day (vanilla's sun texture) nothing; rain hides the stars; no sky, nothing
        assertArrayEquals(new float[3], AtmosphereMath.vanillaStars(n.atmosphere(), new float[]{0, 1, 0}, scene(0.03f, 0.04f, 0.08f),
                slot(n.vanilla(), 0), slot(n.vanilla(), 1), slot(n.vanilla(), 2)), 0f);
        AtmosphereUniforms.Values day = AtmosphereUniforms.compute(SETTINGS, frame(NOON, 0), SERIAL, true, false);
        assertEquals(0f, slot(day.vanilla(), 2)[0], 0f, "no star weight by day");
        assertArrayEquals(new float[3], AtmosphereMath.vanillaStars(day.atmosphere(), new float[]{0, 1, 0}, new float[]{20, 20, 20},
                slot(day.vanilla(), 0), slot(day.vanilla(), 1), slot(day.vanilla(), 2)), 0f);
        AtmosphereFrame rainyNight = new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(PROJ),
                120, 63, nightFog[0], nightFog[1], nightFog[2], false, false, MIDNIGHT, 1, 0, null, nightSky, 256, null, null);
        assertEquals(0f, slot(AtmosphereUniforms.compute(SETTINGS, rainyNight, SERIAL, true, false).vanilla(), 2)[0], 0f);
        AtmosphereUniforms.Values noSky = AtmosphereUniforms.compute(SETTINGS, night, SERIAL, false, false);
        assertArrayEquals(new float[3], AtmosphereMath.vanillaStars(noSky.atmosphere(), new float[]{0, 1, 0}, star,
                slot(noSky.vanilla(), 0), slot(noSky.vanilla(), 1), slot(noSky.vanilla(), 2)), 0f);
        //The header names the function and slot 28
        String text = ShaderFiles.read("post/atmosphere.glsl");
        assertTrue(text.contains("vec3 vanillaStars(Atmosphere a, vec3 dir, vec3 pixel, vec4 vanillaSky, vec4 vanillaFog, vec4 gate)"));
        assertEquals(28, AtmosphereUniforms.STAR_GATE);
    }

    // ---- tuning round 1 ----

    /**
     * 2026-11-09 (session 1b, the user: "the aerial perspective is too weak"; it was "S1/S4/S6/S2 near terrain keeps its colour", with the
     * haze barely there within 150 blocks). Seen from a hill at noon (F1) Luxium and Complementary Reimagined veil terrain 100 to 250
     * blocks away in blue-grey and keep the veil there, while the far distance fades on into the sky. Now: a few blocks out nothing, a clear
     * veil at a render distance's 100 to 250 blocks, leveling off after a few hundred blocks so that Distant Horizons' far terrain keeps
     * some contrast, and in the end the sky behind (farTerrainTurnsIntoTheSkyBehindIt).
     */
    @Test
    void theAerialVeilBuildsNearTheCameraAndLevelsOff() {
        for (float[] scene : new float[][]{{NOON, 0, 110}, {NOON, 0, 160}, {S2_SUN, 0, 193}, {NOON, 0, 250}}) {
            AtmosphereMath.Atmosphere a = atmosphere(PLAYER, frame(scene[0], scene[1], 0, false, FOG, scene[2], new Matrix4f()), true);
            for (float[] d : List.of(dir(0, -0.3f, 1), dir(1, -0.05f, 0), dir(-1, -0.2f, 0.2f))) {
                //terrain points (at or above sea level, 63)
                if (scene[2] + d[1] * 250 < 63) continue;
                String at = Arrays.toString(scene) + " " + Arrays.toString(d);
                //right by the camera: nothing
                assertTrue(AtmosphereMath.haze(a, d, 8, 1).transmittance()[1] > 0.99f, at);
                //a render distance away: a clear veil, not a wall
                AtmosphereMath.Haze mid = AtmosphereMath.haze(a, d, 200, 1);
                assertTrue(mid.transmittance()[1] < 0.93f && mid.transmittance()[1] > 0.6f, "200 blocks " + at + " T " + mid.transmittance()[1]);
                assertTrue(lum(mid.inscatter()) > 0.05f, "200 blocks " + at + " I " + Arrays.toString(mid.inscatter()));
                //Distant Horizons' far terrain keeps some contrast, and softens
                float far = AtmosphereMath.haze(a, d, 3000, 1).transmittance()[1];
                assertTrue(far > 0.05f && far < 0.5f, "3000 blocks " + at + " T " + far);
            }
        }
        //The veil levels off: its air mass grows over 20 times faster in the first 100 blocks than past 3000
        AtmosphereMath.Atmosphere noon = atmosphere(PLAYER, frame(NOON, 0, 0, false, FOG, 160, new Matrix4f()), true);
        float near = AtmosphereMath.hazeMass(noon, -0.1f, 100) / 100;
        float beyond = (AtmosphereMath.hazeMass(noon, -0.1f, 4000) - AtmosphereMath.hazeMass(noon, -0.1f, 3000)) / 1000;
        assertTrue(near > 20 * beyond, near + " " + beyond);
        //and thins with height: a view up the same distance (a cloud, a peak) has less of it than a level one
        assertTrue(AtmosphereMath.hazeMass(noon, 0.3f, 800) < AtmosphereMath.hazeMass(noon, 0, 800));
        //The onset distance itself: monotonic, d^3/(d^2 + onset^2), about d far away
        float last = -1;
        for (float d = 0; d < 20000; d += 1) {
            float e = AtmosphereMath.hazeDistance(d);
            assertTrue(e >= last && e <= d, "d " + d);
            last = e;
        }
        assertEquals(1e6f / (1e4f + AtmosphereMath.HAZE_ONSET * AtmosphereMath.HAZE_ONSET), AtmosphereMath.hazeDistance(100), 0.01f);
        assertEquals(1e6f, AtmosphereMath.hazeDistance(1e6f), 1f);
        //At sunset the in-scatter toward the sun is warm, not cool grey, already a render distance away
        AtmosphereMath.Atmosphere s2 = atmosphere(PLAYER, frame(S2_SUN, 0, 0, false, FOG, 193, new Matrix4f()), true);
        for (float dist : new float[]{200, 1500}) {
            float[] in = AtmosphereMath.haze(s2, fromWest(-10, 0), dist, 1).inscatter();
            assertTrue(in[0] > in[2], "warm haze toward the sunset at " + dist + " " + Arrays.toString(in));
        }
        //At noon the veil is the sky's blue
        float[] blue = AtmosphereMath.haze(noon, dir(0, -0.2f, 1), 200, 1).inscatter();
        assertTrue(blue[2] > blue[1] && blue[1] > blue[0], Arrays.toString(blue));
    }

    /** 2026-11-09 (session 1b): the distance haze setting scales the aerial haze (veil and far fade) and leaves the ground fog alone. */
    @Test
    void theDistanceHazeSettingScalesTheAerialHazeOnly() {
        AtmosphereFrame f = frame(NOON, 0, 0, false, FOG, 160, new Matrix4f());
        AtmosphereMath.Atmosphere none = atmosphere(settings(c -> c.distanceHaze = 0), f, true);
        AtmosphereMath.Atmosphere half = atmosphere(settings(c -> c.distanceHaze = 50), f, true);
        AtmosphereMath.Atmosphere full = atmosphere(SETTINGS, f, true);
        AtmosphereMath.Atmosphere twice = atmosphere(settings(c -> c.distanceHaze = 200), f, true);
        assertEquals(0, AtmosphereMath.hazeMass(none, -0.1f, 500), 0f);
        assertArrayEquals(full.fog(), none.fog(), 0f, "the ground fog is the fog strength's");
        float[] d = dir(0, -0.2f, 1);
        float t0 = AtmosphereMath.haze(none, d, 300, 1).transmittance()[1], t50 = AtmosphereMath.haze(half, d, 300, 1).transmittance()[1];
        float t100 = AtmosphereMath.haze(full, d, 300, 1).transmittance()[1], t200 = AtmosphereMath.haze(twice, d, 300, 1).transmittance()[1];
        assertTrue(t0 > t50 && t50 > t100 && t100 > t200, t0 + " " + t50 + " " + t100 + " " + t200);
        //With none of it the far terrain is still hidden by the ground fog below the horizon, and the sky is unchanged
        assertArrayEquals(AtmosphereMath.skyColour(full, d), AtmosphereMath.skyColour(none, d), 1e-6f);
        assertEquals(0, AtmosphereMath.groundFogDepth(none, 0, 0), 0f);
    }

    /** 2026-11-09 (session 1b, the roadmap: "morning valley fog"): the ground fog thickens on a morning, not on an evening or at noon. */
    @Test
    void aMorningMistFillsTheValleysAndLiftsAsTheSunClimbs() {
        float morning = (float) (1 - Math.acos(0.2) / (2 * Math.PI)), evening = (float) (Math.acos(0.2) / (2 * Math.PI));
        AtmosphereMath.Atmosphere m = atmosphere(SETTINGS, frame(morning, 0, 0, false, FOG, 70, new Matrix4f()), true);
        AtmosphereMath.Atmosphere e = atmosphere(SETTINGS, frame(evening, 0, 0, false, FOG, 70, new Matrix4f()), true);
        AtmosphereMath.Atmosphere n = atmosphere(SETTINGS, frame(NOON, 0, 0, false, FOG, 70, new Matrix4f()), true);
        assertTrue(m.sun()[0] > 0 && e.sun()[0] < 0, "the morning sun is in the east");
        assertEquals(1 + AtmosphereUniforms.MORNING_MIST, m.fog()[0] / n.fog()[0], 1e-3f);
        assertEquals(n.fog()[0], e.fog()[0], 1e-9f);
        //Across the valley (100 blocks, level) the morning mist hides much more than the evening's clear air; it is lit, not grey
        float[] across = dir(1, -0.02f, 0);
        assertTrue(AtmosphereMath.haze(m, across, 100, 1).transmittance()[1] < AtmosphereMath.haze(e, across, 100, 1).transmittance()[1] - 0.03f);
        //Gone by a high morning sun
        AtmosphereMath.Atmosphere late = atmosphere(SETTINGS, frame((float) (1 - Math.acos(0.75) / (2 * Math.PI)), 0, 0, false, FOG, 70, new Matrix4f()), true);
        assertEquals(n.fog()[0], late.fog()[0], 1e-9f);
    }

    /** W7's B3 request: cloudShape x and y whenever vanilla has a cloud layer, also with the far clouds off (w 0). */
    @Test
    void theCloudLayerIsGivenAlsoWithTheFarCloudsOff() {
        AtmosphereFrame noon = frame(NOON, 0);
        AtmosphereUniforms.Values atm = AtmosphereUniforms.compute(SETTINGS, noon, SERIAL, true, false);
        for (CloudUniforms.Values off : List.of(CloudUniforms.compute(settings(c -> c.farClouds = false), noon, atm, SERIAL, true, true, 32),
                CloudUniforms.compute(SETTINGS, noon, atm, SERIAL, true, false, 32),
                CloudUniforms.compute(SETTINGS, frame(NOON, 0, 0, true, WATER_FOG, 120, new Matrix4f()),
                        AtmosphereUniforms.compute(SETTINGS, frame(NOON, 0, 0, true, WATER_FOG, 120, new Matrix4f()), SERIAL, true, false), SERIAL, true, true, 32))) {
            assertFalse(off.on());
            float[] s = off.slots();
            assertEquals(192 + CloudMath.CLOUD_LIFT - 120, s[0], 1e-4f);
            assertTrue(s[1] > 0, "a positive thickness, also for flat clouds");
            assertEquals(0f, s[3], 0f, "w 0: the far clouds are off");
        }
        float[] none = CloudUniforms.compute(SETTINGS, noon.withClouds(null), atm, SERIAL, true, true, 32).slots();
        assertEquals(0f, none[0], 0f);
        assertEquals(0f, none[1], 0f, "no layer: all 0");
        assertEquals(0f, CloudUniforms.compute(SETTINGS, noon, atm, SERIAL, false, true, 32).slots()[1], 0f, "no sky light: no layer");
    }
}
