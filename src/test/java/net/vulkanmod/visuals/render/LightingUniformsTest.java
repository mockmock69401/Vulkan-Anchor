package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7d design 3.1: what the lighting pass and the copy receive. */
class LightingUniformsTest {
    /** Both strengths at 100%: these tests are about the day, exposure and choice factors, not the default sun shading. */
    private static final PostProcessSettings FULL = PostProcessSettings.from(full());
    private static VisualsConfig full() {
        VisualsConfig c = new VisualsConfig();
        c.aoStrength = 100;
        c.sunShading = 100;
        return c;
    }

    private static final float NOON = 0, MIDNIGHT = 0.5f;
    private static final long SERIAL = 42;
    private static final Matrix4f GL_PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 2048);

    private static AtmosphereFrame frame(long serial, float timeOfDay, Matrix4f modelView) {
        return AtmosphereFrame.of(serial, GL_PROJECTION, new Matrix4f(GL_PROJECTION), modelView, 100, 63,
                new FogParameters(0.6f, 0.7f, 0.9f, 1f, -8, 1e6f, -8, 1e6f), 512, true, timeOfDay, 0, 0, null);
    }

    private static Matrix4f lookingUp() {
        return new Matrix4f().lookAlong(0, 1, 0, 0, 0, -1);
    }

    private static LooseUniformWriter writer(String program) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return new LooseUniformWriter(GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta());
    }

    private static final LightingChoice BOTH = new LightingChoice(SERIAL, true, true, false, false);
    private static final LightingChoice ALL = new LightingChoice(SERIAL, true, true, true, true);

    @Test
    void onWithThisFramesChoiceAndFrame() {
        LightingUniforms.Values v = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        assertTrue(v.on());
        assertEquals(1f, v.aoStrength());
        assertEquals(1f, v.sunWeight(), 1e-6f);
    }

    /** Review focus 1: a choice or a frame from another frame turns the lighting off. */
    @Test
    void offWhenTheChoiceIsForAnotherFrame() {
        assertFalse(LightingUniforms.compute(FULL, new LightingChoice(SERIAL - 1, true, true, false, false), frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).on());
        assertFalse(LightingUniforms.compute(FULL, BOTH, frame(SERIAL - 1, NOON, lookingUp()), SERIAL, true, 1, true).on());
        assertFalse(LightingUniforms.compute(FULL, BOTH, null, SERIAL, true, 1, true).on());
        assertFalse(LightingUniforms.compute(FULL, LightingChoice.NONE, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).on());
    }

    @Test
    void sunWeightFollowsDayExposureAndChoice() {
        assertEquals(0.5f, LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 0.5f, true).sunWeight(), 1e-6f);
        assertEquals(0f, LightingUniforms.compute(FULL, BOTH, frame(SERIAL, MIDNIGHT, lookingUp()), SERIAL, true, 1, true).sunWeight());
        LightingUniforms.Values aoOnly = LightingUniforms.compute(FULL, new LightingChoice(SERIAL, true, false, false, false), frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        assertEquals(0f, aoOnly.sunWeight());
        assertEquals(1f, aoOnly.aoStrength());
    }

    /** Looking straight up at noon the sun is straight ahead: (0, 0, -1) in view space. */
    @Test
    void theSunIsInViewSpace() {
        float[] sun = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).sunView();
        assertEquals(0f, sun[0], 1e-5f);
        assertEquals(0f, sun[1], 1e-5f);
        assertEquals(-1f, sun[2], 1e-5f);
    }

    /** A pitch + yaw view is not its own inverse, so this pins the direction of the transform (model-view, not its inverse). */
    @Test
    void theSunIsInViewSpaceForAnAsymmetricView() {
        Matrix4f modelView = new Matrix4f().rotateX(0.4f).rotateY(0.9f);
        float timeOfDay = 0.1f;
        org.joml.Vector3f expected = modelView.transformDirection(new org.joml.Vector3f(SunMath.sunDirection(timeOfDay))).normalize();
        float[] sun = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, timeOfDay, modelView), SERIAL, true, 1, true).sunView();
        assertEquals(expected.x, sun[0], 1e-5f);
        assertEquals(expected.y, sun[1], 1e-5f);
        assertEquals(expected.z, sun[2], 1e-5f);
    }

    /** The screen position the pass projects to is vanilla's (x, y and w do not change under Reforged's depth remap). */
    @Test
    void theProjectionPlacesPointsWhereVanillaDoes() {
        Matrix4f proj = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).projection();
        Vector4f a = proj.transform(new Vector4f(3, -2, -40, 1)), b = GL_PROJECTION.transform(new Vector4f(3, -2, -40, 1));
        assertEquals(b.x / b.w, a.x / a.w, 1e-5f);
        assertEquals(b.y / b.w, a.y / a.w, 1e-5f);
    }

    @Test
    void writesWhereTheShadersDeclare() {
        LightingUniforms.Values v = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        LooseUniformWriter pass = writer("postLighting");
        assertTrue(pass.inUniformBuffer());
        v.writeLighting(pass);
        for (int location = LightingUniforms.INV_VANILLA_PROJ; location <= LightingUniforms.UP; location++) {
            assertTrue(pass.has(location), "location " + location);
        }
        assertTrue(pass.has(LightingUniforms.EAST), "location " + LightingUniforms.EAST);
        LooseUniformWriter copy = writer("postSceneCopy");
        v.writeCopy(copy);
        assertTrue(copy.has(LightingUniforms.COPY_LIGHTING));
        assertTrue(copy.has(LightingUniforms.COPY_WATER));
        assertTrue(copy.has(LightingUniforms.COPY_LIGHT_SUN));
        assertTrue(copy.has(LightingUniforms.COPY_LIGHT_SHADE));
        LightingUniforms.off().writeCopy(copy);
    }

    /**
     * Session 8 follow-up: the picked sunlight and shade light colours multiply the sun's and the shade's colours in slots 22 and 23; the
     * defaults write exactly the bytes they wrote before.
     */
    @Test
    void thePickedLightColoursTintTheSunAndTheShade() {
        LightingUniforms.Values v = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        LooseUniformWriter plain = writer("postSceneCopy"), same = writer("postSceneCopy"), picked = writer("postSceneCopy");
        v.writeCopy(plain);
        v.tinted(ShaderColours.DEFAULT).writeCopy(same);
        assertEquals(plain.buffer(), same.buffer(), "the defaults change no byte");
        VisualsConfig c = new VisualsConfig();
        c.setColour(ShaderColours.Colour.SUNLIGHT, 0xFF8040);
        c.setColour(ShaderColours.Colour.SHADE_LIGHT, 0x80C0FF);
        ShaderColours colours = ShaderColours.of(c.colours());
        float[] t = colours.tint(ShaderColours.Colour.SUNLIGHT), d = colours.tint(ShaderColours.Colour.SHADE_LIGHT);
        float[] l = v.lightSun();
        v.tinted(colours).writeCopy(picked);
        //the same slots written by hand with the products: the writer puts them where the shader reads them
        LooseUniformWriter probe = writer("postSceneCopy");
        v.writeCopy(probe);
        probe.putVec4(LightingUniforms.COPY_LIGHT_SUN, l[0] * t[0], l[1] * t[1], l[2] * t[2], l[3]);
        probe.putVec4(LightingUniforms.COPY_LIGHT_SHADE, l[5] * d[0], l[6] * d[1], l[7] * d[2], l[4]);
        assertEquals(probe.buffer(), picked.buffer());
        assertFalse(plain.buffer().equals(picked.buffer()));
        assertTrue(t[0] == 1 && t[1] < 1 && t[2] < t[1], "an orange sun: red kept, green and more so blue taken");
        assertTrue(d[2] > 1 && d[0] < 1, "a blue shade is bluer");
    }

    /** Looking straight up, world up is straight ahead: (0, 0, -1) in view space, as the noon sun. */
    @Test
    void worldUpIsInViewSpace() {
        float[] up = LightingUniforms.compute(FULL, ALL, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).upView();
        assertEquals(0f, up[0], 1e-5f);
        assertEquals(0f, up[1], 1e-5f);
        assertEquals(-1f, up[2], 1e-5f);
        Matrix4f modelView = new Matrix4f().rotateX(0.4f).rotateY(0.9f);
        org.joml.Vector3f expected = modelView.transformDirection(new org.joml.Vector3f(0, 1, 0)).normalize();
        float[] tilted = LightingUniforms.compute(FULL, ALL, frame(SERIAL, NOON, modelView), SERIAL, true, 1, true).upView();
        assertEquals(expected.x, tilted[0], 1e-5f);
        assertEquals(expected.y, tilted[1], 1e-5f);
        assertEquals(expected.z, tilted[2], 1e-5f);
    }

    /**
     * 2026-10-19: world east (+x) in view space, beside world up, for the LOD pixels' face normals (lighting.comp: south is east x up).
     * Looking straight up with north at the top of the screen (lookingUp), east is to the left: (-1, 0, 0).
     */
    @Test
    void worldEastIsInViewSpace() {
        float[] east = LightingUniforms.compute(FULL, ALL, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).eastView();
        assertEquals(-1f, east[0], 1e-5f);
        assertEquals(0f, east[1], 1e-5f);
        assertEquals(0f, east[2], 1e-5f);
        Matrix4f modelView = new Matrix4f().rotateX(0.4f).rotateY(0.9f);
        org.joml.Vector3f expected = modelView.transformDirection(new org.joml.Vector3f(1, 0, 0)).normalize();
        LightingUniforms.Values v = LightingUniforms.compute(FULL, ALL, frame(SERIAL, NOON, modelView), SERIAL, true, 1, true);
        assertEquals(expected.x, v.eastView()[0], 1e-5f);
        assertEquals(expected.y, v.eastView()[1], 1e-5f);
        assertEquals(expected.z, v.eastView()[2], 1e-5f);
        //South (world +z) as the shader rebuilds it: east x up, the view's rotation keeps cross products
        org.joml.Vector3f south = new org.joml.Vector3f(v.eastView()[0], v.eastView()[1], v.eastView()[2])
                .cross(v.upView()[0], v.upView()[1], v.upView()[2]);
        org.joml.Vector3f expectedSouth = modelView.transformDirection(new org.joml.Vector3f(0, 0, 1)).normalize();
        assertEquals(expectedSouth.x, south.x, 1e-5f);
        assertEquals(expectedSouth.y, south.y, 1e-5f);
        assertEquals(expectedSouth.z, south.z, 1e-5f);
    }

    /** P7n design 4.2: the sun tint by the sun's height and the strength; no colour without the sun shading or the lighting. */
    @Test
    void theLightColourFollowsTheSunAndTheChoice() {
        float[] noon = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).lightSun();
        assertArrayEquals(ColourMath.sunLight(1, 1), new float[]{noon[0], noon[1], noon[2]}, 1e-5f, "warmed by the default Sun Warmth (2026-10-28)");
        assertEquals(0.7f, noon[3], 1e-6f, "the default Light Colour is 70% (final fix R3)");
        assertEquals(1f, noon[4], 1e-6f, "the colour's sun weight is day x exposure, not the 50% Sun Shading");
        float[] dim = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 0.5f, true).lightSun();
        assertEquals(0.5f, dim[4], 1e-6f);
        LightingUniforms.Values aoOnly = LightingUniforms.compute(FULL, new LightingChoice(SERIAL, true, false, false, false), frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        assertEquals(0f, aoOnly.lightSun()[3], "sun shading off: everything would read as shade");
        assertEquals(0f, aoOnly.lightSun()[4]);
        assertEquals(0f, LightingUniforms.off().lightSun()[3]);
        //2026-10-28: the moonlight's strength is the light colour as far as it is night; torch light needs no pass
        LightingUniforms.Values midnight = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, MIDNIGHT, lookingUp()), SERIAL, true, 1, true, true);
        assertEquals(0.7f, midnight.moonLight(), 1e-5f);
        assertEquals(0f, noonValues().moonLight(), 1e-5f);
        assertEquals(0f, LightingUniforms.compute(FULL, BOTH, frame(SERIAL, MIDNIGHT, lookingUp()), SERIAL, true, 1, true, false).moonLight(), "no sky: no moon");
        assertEquals(0.7f, aoOnly.blockLightColour(), 1e-6f);
        assertEquals(0f, LightingUniforms.off().blockLightColour());
    }

    private static LightingUniforms.Values noonValues() {
        return LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true, true);
    }

    @Test
    void theWaterFollowsTheChoiceAndTheTimeOfDay() {
        LightingUniforms.Values noon = LightingUniforms.compute(FULL, ALL, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        assertEquals(1f, noon.waterStrength());
        assertEquals(WaterMath.ambient(1), noon.waterAmbient(), 1e-6f);
        assertEquals(WaterMath.ambient(0), LightingUniforms.compute(FULL, ALL, frame(SERIAL, MIDNIGHT, lookingUp()), SERIAL, true, 1, true).waterAmbient(), 1e-6f);
        assertEquals(0f, LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).waterStrength());
    }

    @Test
    void onWithTheWaterAlone() {
        LightingUniforms.Values v = LightingUniforms.compute(FULL, new LightingChoice(SERIAL, false, false, true, false), frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        assertTrue(v.on());
        assertEquals(0f, v.aoStrength());
        assertEquals(0f, v.sunWeight());
    }

    /** Review focus 4: with the water colour at 0 the copies are still compared, so water keeps no AO. */
    @Test
    void detectionRunsWithoutTheWaterColour() {
        LightingUniforms.Values v = LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true);
        assertTrue(v.on() && v.vanillaCopies());
        assertEquals(0f, v.waterStrength());
        assertFalse(LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, false).vanillaCopies());
    }

    /** Review focus 1: the camera height's fraction is in [0, 1), below y = 0 too. */
    @Test
    void theCameraHeightFractionIsBelowOne() {
        AtmosphereFrame below = AtmosphereFrame.of(SERIAL, GL_PROJECTION, new Matrix4f(GL_PROJECTION), lookingUp(), -3.25, 63,
                new FogParameters(0.6f, 0.7f, 0.9f, 1f, -8, 1e6f, -8, 1e6f), 512, true, NOON, 0, 0, null);
        assertEquals(0.75f, LightingUniforms.compute(FULL, ALL, below, SERIAL, true, 1, true).cameraYFrac(), 1e-6f);
        assertEquals(0f, LightingUniforms.compute(FULL, ALL, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).cameraYFrac(), 1e-6f);
    }

    /** 2026-10-17: block light's day weight is the day times the camera's sky exposure (out in the day 1, deep underground or at night 0). */
    @Test
    void blockLightsDayWeightFollowsTheCamerasSky() {
        assertEquals(1f, LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 1, true).blockDay(), 1e-6f);
        assertEquals(0f, LightingUniforms.compute(FULL, BOTH, frame(SERIAL, NOON, lookingUp()), SERIAL, true, 0, true).blockDay(), 1e-6f);
        assertEquals(0f, LightingUniforms.compute(FULL, BOTH, frame(SERIAL, MIDNIGHT, lookingUp()), SERIAL, true, 1, true).blockDay(), 1e-6f);
        assertEquals(0f, LightingUniforms.off().blockDay());
    }
}
