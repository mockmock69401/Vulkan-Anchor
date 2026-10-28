package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.Bytecode;
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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W7: the scene composite (scene_copy.comp, mirrored by SceneCompositeMath): wiring, the brief's tests 2..6, the mirror. */
class SceneCompositeTest {
    private static final String PROGRAM = "postSceneCopy";
    private static final String COMP = ShaderFiles.read("post/scene_copy.comp");
    private static final long SERIAL = 7;
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
    private static final float NOON = 0, MIDNIGHT = 0.5f;
    private static final float SUNSET = (float) (Math.acos(0.05) / (2 * Math.PI));
    private static final float[] FOG = {0.75f, 0.4f, 0.3f};
    private static final float[] NETHER_FOG = {0.2f, 0.03f, 0.03f};
    private static final float[] WATER_FOG = {0.05f, 0.2f, 0.6f};
    private static final float[] PLAINS_SKY = {121 / 255f, 167 / 255f, 1};

    // ---- slots, read back from the clean writers through the shader's own layout ----

    private static VulkanShaderMeta meta() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(PROGRAM);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    private static final VulkanShaderMeta META = meta();

    private static VulkanShaderMeta.PushMember member(int location) {
        return META.push().stream().filter(m -> m.location() == location).findFirst()
                .orElseThrow(() -> new AssertionError("no location " + location));
    }

    /** Every slot 3..34 as a vec4, after the writers (zeros where none writes). */
    private static float[][] slots(Consumer<LooseUniformWriter> write) {
        LooseUniformWriter w = new LooseUniformWriter(META);
        write.accept(w);
        ByteBuffer b = w.buffer();
        float[][] s = new float[SceneCompositeMath.SLOTS][4];
        for (int loc = 3; loc < SceneCompositeMath.SLOTS; loc++) {
            int o = member(loc).offset();
            for (int i = 0; i < 4; i++) s[loc][i] = b.getFloat(o + 4 * i);
        }
        return s;
    }

    private static AtmosphereFrame frame(float timeOfDay, float rain, boolean fogCovers, float[] fog, Matrix4f modelView, float[] sky) {
        return frame(timeOfDay, rain, fogCovers, fog, modelView, sky, AtmosphereFrame.CameraFluid.NONE);
    }

    /** Final fix R2: the camera's fluid as VisualsRenderer captures it from the game camera (sky null: a dimension without sky light). */
    private static AtmosphereFrame frame(float timeOfDay, float rain, boolean fogCovers, float[] fog, Matrix4f modelView, float[] sky,
                                         AtmosphereFrame.CameraFluid fluid) {
        return new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), modelView.invert(new Matrix4f()),
                new Matrix4f(PROJ).mul(modelView), 120, 63, fog[0], fog[1], fog[2], fogCovers, false, timeOfDay, rain, 0, null,
                sky == null ? null : sky.clone(), 256, null, new CloudFrame(192, 100, 200, new float[]{1, 1, 1})).withCameraFluid(fluid);
    }

    private static AtmosphereFrame underWater(float timeOfDay, float[] fog) {
        return frame(timeOfDay, 0, true, fog, new Matrix4f(), PLAINS_SKY, AtmosphereFrame.CameraFluid.WATER);
    }

    private static AtmosphereFrame frame(float timeOfDay, float rain, boolean fogCovers, float[] fog, Matrix4f modelView) {
        return frame(timeOfDay, rain, fogCovers, fog, modelView, PLAINS_SKY);
    }

    private static AtmosphereFrame frame(float timeOfDay) {
        return frame(timeOfDay, 0, false, FOG, new Matrix4f());
    }

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static final PostProcessSettings DEFAULT = settings(c -> {
    });

    /** The composite's slots for a frame: atmosphere, lighting (with sky exposure), no shadows, the light volume on or off, held off. */
    private static float[][] scene(PostProcessSettings s, AtmosphereFrame f, boolean hasSky, boolean volume, float skyExposure) {
        AtmosphereUniforms.Values atmosphere = AtmosphereUniforms.compute(s, f, SERIAL, hasSky, false);
        LightingChoice choice = f == null ? LightingChoice.NONE : LightingChoice.of(SERIAL, s, true, true, f.fogCoversAllRendering(), hasSky);
        LightingUniforms.Values lighting = LightingUniforms.compute(s, choice, f, SERIAL, false, skyExposure, true, hasSky);
        LightVolumeUniforms.Values light = volume
                ? LightVolumeUniforms.compute(s, new LightVolumeMath.Layout(5, 24, 5, -4), 10.5, 70.2, -3.5, f, SERIAL, hasSky, lighting.on())
                : LightVolumeUniforms.off();
        CloudUniforms.Values clouds = CloudUniforms.compute(s, f, atmosphere, SERIAL, hasSky, false, 32);
        return slots(w -> {
            atmosphere.write(w);
            lighting.writeCopy(w);
            ShadowUniforms.off().writeCopy(w);
            light.write(w, LightVolumeUniforms.COPY_FIRST);
            clouds.write(w, CloudUniforms.COPY_FIRST);
            w.putVec4(HeldLightSystem.COPY_HELD, 0, 0, 0, 0);
        });
    }

    /** A vanilla-like lightmap in scene units: sky brightness (white) plus block brightness (warm) over a dark floor, clamped, decoded. */
    private static final SceneCompositeMath.Lightmap LIGHTMAP = (block, sky) -> {
        float s = brightness(sky), b = brightness(block);
        float[] warm = {1, 0.85f, 0.6f};
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = PostMath.displayToLinear(Math.min(1, 0.04f + 0.96f * Math.max(s, b * warm[i])));
        return out;
    };

    private static float brightness(float level) {
        float f = level / 15f;
        return f / (4 - 3 * f);
    }

    private static float[] dir(float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z).normalize();
        return new float[]{v.x, v.y, v.z};
    }

    private static final float[] UP = {0, 1, 0}, NORTH = {0, 0, -1}, EAST = {1, 0, 0}, NONE = {0, 0, 0};
    private static final float OPAQUE = 1, NO_MARCH = 0;

    private static SceneCompositeMath.Pixel surface(float[] rgba, float[] normal, float[] lt, float plantSun, float[] light, float held,
                                                    boolean fromLod, float dist) {
        return new SceneCompositeMath.Pixel(rgba, false, dir(0.3f, -0.4f, -1), dist, fromLod, normal, lt, plantSun, light, 0, held, false,
                new float[3]);
    }

    private static SceneCompositeMath.Pixel surface(float[] rgba, float[] normal, float[] lt, float[] light) {
        return surface(rgba, normal, lt, -1, light, 0, false, 40);
    }

    private static SceneCompositeMath.Pixel skyPixel(float[] rgba, float[] d) {
        return new SceneCompositeMath.Pixel(rgba, true, d, -1, false, NONE, null, -1, new float[3], 0, 0, false, new float[3]);
    }

    private static float[] linear(float[] rgba) {
        return new float[]{PostMath.displayToLinear(rgba[0]), PostMath.displayToLinear(rgba[1]), PostMath.displayToLinear(rgba[2])};
    }

    /** linear(rgba), times a light source's glow (2026-10-28: the glow needs no lighting pass) at the slots' block glow. */
    private static float[] glowed(float[] rgba, float[][] s) {
        float g = rgba[3] == FoliageMarker.EMISSIVE_ALPHA ? SceneCompositeMath.lightGlow(rgba, s[SceneCompositeMath.GLOW][0]) : 1;
        return scale(linear(rgba), g);
    }

    private static float[] scale(float[] c, float k) {
        return new float[]{c[0] * k, c[1] * k, c[2] * k};
    }

    private static float[] rgb(float[] c) {
        return Arrays.copyOf(c, 3);
    }

    private static void assertFinite(float[] c, String what) {
        for (float f : c) assertTrue(Float.isFinite(f) && f >= -1e-6f, what + " " + Arrays.toString(c));
    }

    private static final float[][] COLOURS = {{0.5f, 0.6f, 0.3f, OPAQUE}, {0.9f, 0.9f, 0.9f, OPAQUE}, {0.1f, 0.05f, 0.2f, OPAQUE},
            {0.4f, 0.7f, 0.2f, FoliageMarker.ALPHA}, {1, 0.9f, 0.5f, FoliageMarker.EMISSIVE_ALPHA}, {0, 0, 0, OPAQUE}};
    private static final float[] NAN4 = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};

    // ---- 1. wiring ----

    @Test
    void everyBindingMatchesTheDispatcherAndEverySlotHasItsWritersType() throws Exception {
        ClassNode post = Bytecode.read("net/vulkanmod/visuals/render/VkPostProcess");
        Map<String, Integer> fields = new HashMap<>();
        for (FieldNode f : post.fields) if (f.value instanceof Integer i) fields.put(f.name, i);
        Map<String, VulkanShaderMeta.Kind> expected = new HashMap<>();
        expected.put("DST", VulkanShaderMeta.Kind.IMAGE);
        for (String s : List.of("SRC", "MAIN_DEPTH", "LOD_DEPTH", "LIGHTING", "COPY_CLOUD_MAP", "COPY_LIGHTMAP", "COPY_SUN", "COPY_LOD_LIGHT", "COPY_PLANT_SUN", "COPY_AO")) {
            expected.put(s, VulkanShaderMeta.Kind.SAMPLER);
        }
        for (String s : List.of("COPY_LIGHT_DATA", "COPY_LIGHT_READY", "COPY_HELD_LIGHT")) expected.put(s, VulkanShaderMeta.Kind.STORAGE_BUFFER);
        //2026-10-23: the terrain light, a storage image read in GENERAL
        expected.put("COPY_TERRAIN_LIGHT", VulkanShaderMeta.Kind.IMAGE);
        expected.put("COPY_TERRAIN_ALBEDO", VulkanShaderMeta.Kind.IMAGE);
        Set<String> want = new TreeSet<>();
        for (Map.Entry<String, VulkanShaderMeta.Kind> e : expected.entrySet()) {
            Integer binding = fields.get(e.getKey());
            assertTrue(binding != null, "VkPostProcess." + e.getKey());
            want.add(binding + ":" + e.getValue());
        }
        Set<String> have = new TreeSet<>();
        for (VulkanShaderMeta.Binding b : META.bindings()) {
            if (b.kind() == VulkanShaderMeta.Kind.UNIFORM_BUFFER) continue;//the loose uniform block
            //GlslForVulkan gives each kind its own set; the GL binding number is what VkBindings matches
            have.add(b.binding() + ":" + b.kind());
        }
        assertEquals(want, have);
        assertEquals(16, have.size(), "boundary 3.2: bindings 0..12, W10's ambient occlusion at 13, the terrain light at 14, its albedo at 15");
        //Every slot 0..34 with the type its writer uses: the three matrices, then vec4
        for (int loc = 0; loc <= 34; loc++) assertEquals(loc <= 2 ? "mat4" : "vec4", member(loc).type(), "location " + loc);
        //The clean writers and W4 fill every slot but the held light's (HeldLightSystem needs a device buffer: its slot is checked by value)
        LooseUniformWriter w = new LooseUniformWriter(META);
        ByteBuffer raw = w.buffer();
        for (int i = 0; i < raw.limit(); i += 4) raw.putFloat(i, Float.NaN);
        AtmosphereFrame f = frame(NOON);
        AtmosphereUniforms.Values atmosphere = AtmosphereUniforms.compute(DEFAULT, f, SERIAL, true, false);
        atmosphere.write(w);
        LightingUniforms.compute(DEFAULT, LightingChoice.of(SERIAL, DEFAULT, true, true, false, true), f, SERIAL, false, 1, true).writeCopy(w);
        ShadowUniforms.off().writeCopy(w);
        LightVolumeUniforms.off().write(w, LightVolumeUniforms.COPY_FIRST);
        CloudUniforms.compute(DEFAULT, f, atmosphere, SERIAL, true, true, 32).write(w, CloudUniforms.COPY_FIRST);
        ByteBuffer b = w.buffer();
        for (int loc = 0; loc <= 34; loc++) {
            if (loc == HeldLightSystem.COPY_HELD) continue;
            VulkanShaderMeta.PushMember m = member(loc);
            for (int i = 0; i < (loc <= 2 ? 16 : 4); i++) assertFalse(Float.isNaN(b.getFloat(m.offset() + 4 * i)), "location " + loc);
        }
        assertEquals(24, HeldLightSystem.COPY_HELD);
        assertEquals(10, LightingUniforms.COPY_LIGHTING);
        assertEquals(11, LightingUniforms.COPY_WATER);
        assertEquals(22, LightingUniforms.COPY_LIGHT_SUN);
        assertEquals(23, LightingUniforms.COPY_LIGHT_SHADE);
        assertEquals(17, ShadowUniforms.COPY);
        assertEquals(18, LightVolumeUniforms.COPY_FIRST);
        assertEquals(12, CloudUniforms.COPY_FIRST);
        //The mirror's slot names are the shader's
        assertEquals(SceneCompositeMath.HELD_ORIGIN, HeldLightSystem.COPY_HELD);
        assertEquals(SceneCompositeMath.LIGHT_SUN, LightingUniforms.COPY_LIGHT_SUN);
        assertEquals(SceneCompositeMath.LIGHT_CAMERA, LightVolumeUniforms.COPY_FIRST);
        assertEquals(SceneCompositeMath.CLOUD_SHAPE, CloudUniforms.COPY_FIRST);
        assertEquals(SceneCompositeMath.VANILLA_SKY, AtmosphereUniforms.VANILLA_SKY);
        assertEquals(SceneCompositeMath.STAR_GATE, AtmosphereUniforms.STAR_GATE);
        assertEquals(SceneCompositeMath.LOOK, AtmosphereUniforms.LOOK);
        assertEquals(SceneCompositeMath.BORDER_FOG, AtmosphereUniforms.BORDER);
        assertEquals(29, AtmosphereUniforms.LOOK, "boundary 3.2: W4's slot 29");
        assertArrayEquals(new int[]{3, 4, 5, 6, 7, 9, 25}, AtmosphereUniforms.COPY_SLOTS);
        assertTrue(COMP.contains("Atmosphere(atmosphereSun, atmosphereSunLight, atmosphereMoonLight, atmosphereDepth, atmosphereAmbient, atmosphereGlow, atmosphereFog)"));
        assertTrue(COMP.contains("CloudLight(cloudLit, cloudAmbient, cloudLightDir)"));
    }

    // ---- 2. lighting off, no LOD inputs: only the colour conversion ----

    @Test
    void withTheLightingPassOffAndNoLodTheCompositeOnlyConvertsTheColour() {
        //No atmosphere frame (W4's NONE): no haze; and an atmosphere whose haze is off (fog strength 0)
        List<float[][]> cases = List.of(scene(DEFAULT, null, true, false, 1),
                scene(settings(c -> {
                    c.fogStrength = 0;
                    c.sunShading = 0;
                    c.aoStrength = 0;
                    c.waterDepth = 0;
                    c.waterReflections = 0;
                }), frame(NOON), true, false, 1));
        for (float[][] s : cases) {
            assertEquals(0f, s[SceneCompositeMath.LIGHTING][2], "the pass did not run");
            for (float[] c : COLOURS) {
                for (float[] n : new float[][]{UP, NORTH, EAST, NONE}) {
                    //Whatever the lighting images hold, they are not read; the light is unknown without the volume
                    float[] out = SceneCompositeMath.composite(surface(c, n, NAN4, 0.2f, new float[]{3, 9, 0}, 15, false, 60), s, LIGHTMAP);
                    assertArrayEquals(glowed(c, s), rgb(out), 1e-5f, Arrays.toString(c));
                    assertEquals(0f, out[3], "alpha");
                }
            }
        }
        //No atmosphere: a sky pixel is the game's pixel
        float[] sky = SceneCompositeMath.composite(skyPixel(COLOURS[0], UP), cases.get(0), LIGHTMAP);
        assertArrayEquals(linear(COLOURS[0]), rgb(sky), 1e-6f);
        assertEquals(0f, sky[3]);
        //The shader reads no lighting image unless slot 10 .z says the pass ran
        assertTrue(COMP.contains("bool passRan = lighting.z > 0.5;"));
        assertTrue(COMP.contains("if (passRan) {\n        lt = lightingAt(lightTex, sunTex, p, dist, normalView);\n        if (plant) {\n            plantSun = texelFetch(plantSunTex, p, 0).r;"));
        assertEquals(1, count(COMP, "lightingAt("), "one read of lightTex/sunTex");
        assertEquals(1, count(COMP, "texelFetch(plantSunTex"));
        assertFalse(COMP.contains("texelFetch(lightTex") || COMP.contains("texelFetch(sunTex"), "only through W5's lightingAt");
        assertTrue(COMP.contains("passRan && !cloud ? reflectionMark(kind) : 0.0"), "alpha 0 without the pass");
    }

    // ---- 3. under water: W4's environmental-fog path ----

    @Test
    void underWaterTheHazeIsW4sEnvironmentalFog() {
        AtmosphereFrame f = underWater(NOON, WATER_FOG);
        float[][] s = scene(DEFAULT, f, true, true, 1);
        AtmosphereMath.Atmosphere a = SceneCompositeMath.atmosphere(s);
        assertEquals(AtmosphereMath.ATMOSPHERE_FOG, a.mode());
        assertEquals(0f, s[SceneCompositeMath.LIGHTING][2], "no lighting under the environmental fog (LightingChoice)");
        float[] waterFog = {PostMath.displayToLinear(WATER_FOG[0]), PostMath.displayToLinear(WATER_FOG[1]), PostMath.displayToLinear(WATER_FOG[2])};
        //Tuning round 2 (refs/S5): the whole view under water is seen at UNDERWATER_EXPOSURE
        float e = SceneCompositeMath.UNDERWATER_EXPOSURE;
        assertEquals(e, SceneCompositeMath.underwaterExposure(s[SceneCompositeMath.LOOK]), 1e-6f);
        for (float[] c : COLOURS) {
            for (float dist : new float[]{1, 30, 400, 5000}) {
                //Vanilla pixels already carry vanilla's fog: W4's amount 0
                SceneCompositeMath.Pixel vanilla = surface(c, UP, NAN4, -1, new float[]{15, 0, 1}, 0, false, dist);
                float[] out = SceneCompositeMath.composite(vanilla, s, LIGHTMAP);
                assertEquals(0f, AtmosphereMath.hazeAmount(a, 1, false));
                assertArrayEquals(scale(glowed(c, s), e), rgb(out), 1e-5f);
                //LOD pixels get W4's weak uniform fog in vanilla's fog colour
                SceneCompositeMath.Pixel lod = surface(c, UP, NAN4, -1, new float[]{15, 0, 1}, 0, true, dist);
                float[] lit = SceneCompositeMath.composite(lod, s, LIGHTMAP);
                float[] expect = AtmosphereMath.applyHaze(a, linear(c), lod.dir(), dist, AtmosphereMath.hazeAmount(a, 1, true));
                assertArrayEquals(scale(expect, e), rgb(lit), 1e-5f);
                if (dist >= 400) assertNotEquals(linear(c)[2] * e, lit[2], 1e-4f, "the LOD is fogged");
            }
        }
        //The sky is vanilla's fog colour, no sun disc, glow or stars
        for (float[] d : new float[][]{UP, dir(1, 0.1f, 0), dir(0, 0.01f, 1)}) {
            assertArrayEquals(scale(waterFog, e), rgb(SceneCompositeMath.composite(skyPixel(new float[]{1, 1, 1, 1}, d), s, LIGHTMAP)), 1e-6f);
        }
        //Lava, blindness and powder snow are not water: no exposure (final fix R2: the camera's fluid says so, not the fog colour)
        Object[][] others = {{new float[]{0.6f, 0.1f, 0f}, AtmosphereFrame.CameraFluid.LAVA}, {new float[]{0, 0, 0}, AtmosphereFrame.CameraFluid.NONE},
                {new float[]{0.62f, 0.8f, 0.98f}, AtmosphereFrame.CameraFluid.POWDER_SNOW}};
        for (Object[] other : others) {
            float[][] o = scene(DEFAULT, frame(NOON, 0, true, (float[]) other[0], new Matrix4f(), PLAINS_SKY, (AtmosphereFrame.CameraFluid) other[1]), true, true, 1);
            AtmosphereMath.Atmosphere oa = SceneCompositeMath.atmosphere(o);
            assertEquals(AtmosphereMath.ATMOSPHERE_FOG, oa.mode());
            assertEquals(1f, SceneCompositeMath.underwaterExposure(o[SceneCompositeMath.LOOK]), 1e-6f, other[1].toString());
            float[] c = COLOURS[0];
            assertArrayEquals(linear(c), rgb(SceneCompositeMath.composite(surface(c, UP, NAN4, -1, new float[]{15, 0, 1}, 0, false, 30), o, LIGHTMAP)), 1e-6f);
        }
        //Not in the open air
        assertEquals(1f, SceneCompositeMath.underwaterExposure(scene(DEFAULT, frame(NOON), true, true, 1)[SceneCompositeMath.LOOK]));
        assertTrue(COMP.contains("vec4(lit*underwaterExposure()"));
        assertTrue(COMP.contains("farCloudsOver(a, sky, dir, HAZE_MAX_DISTANCE)*underwaterExposure()"));
        //The shader takes the haze from W4 only (no fog of its own)
        assertTrue(COMP.contains("float amount = hazeAmount(a, known && !cloud ? hazeSky : 1.0, fromLod);"));
        assertTrue(COMP.contains("applyHaze(a, lit, dir, dist, amount)"));
        assertEquals(0, count(COMP, "ATMOSPHERE_FOG"), "the mode is W4's business: the camera's fluid (slot 29 .y) says under water");
    }

    // ---- 4. no sky light ----

    @Test
    void withoutSkyLightNothingIsNaNThereAreNoSunOrSkyEffectsAndTheSkyIsTheDimensionsOwn() {
        AtmosphereFrame f = frame(NOON, 0, false, NETHER_FOG, new Matrix4f());
        float[] fog = {PostMath.displayToLinear(NETHER_FOG[0]), PostMath.displayToLinear(NETHER_FOG[1]), PostMath.displayToLinear(NETHER_FOG[2])};
        for (boolean volume : new boolean[]{false, true}) {
            float[][] s = scene(settings(c -> c.blockLightColour = 0), f, false, volume, 0);
            AtmosphereMath.Atmosphere a = SceneCompositeMath.atmosphere(s);
            assertEquals(AtmosphereMath.ATMOSPHERE_NO_SKY, a.mode());
            float[] sun = {a.sun()[0], a.sun()[1], a.sun()[2]};
            //Review round 1 ruling (B17): the sky keeps the dimension's own look (the End's sky box): no sun, glow, disc or stars
            for (float[] d : new float[][]{UP, sun, dir(1, 0.02f, 0), dir(0, -1, 0)}) {
                for (float[] vanilla : new float[][]{{0.9f, 0.9f, 0.9f, 1}, {0.08f, 0.05f, 0.1f, 1}, {0, 0, 0, 1}}) {
                    float[] out = SceneCompositeMath.composite(skyPixel(vanilla, d), s, LIGHTMAP);
                    assertArrayEquals(linear(vanilla), rgb(out), 1e-6f, "the game's sky pixel, no glow or disc toward the sun");
                    assertEquals(0f, out[3]);
                }
            }
            for (float[] c : COLOURS) {
                for (float dist : new float[]{0, 0.5f, 10, 1000, 1e6f, Float.POSITIVE_INFINITY, Float.NaN}) {
                    for (float[] light : new float[][]{{0, 0, 1}, {0, 15, 1}, {0, 7.5f, 1}, {0, 0, 0}}) {
                        float[] out = SceneCompositeMath.composite(surface(c, NORTH, NAN4, -1, light, 0, false, dist), s, LIGHTMAP);
                        assertFinite(out, "dist " + dist + " light " + Arrays.toString(light));
                    }
                }
                //Tuning round 1 (refs/S9, S10): geometry without sky light at NO_SKY_EXPOSURE, light sources at their own brightness
                if (c[3] == OPAQUE) {
                    float[] near = SceneCompositeMath.composite(surface(c, NORTH, NAN4, -1, new float[]{0, 7, 1}, 0, false, 0), s, LIGHTMAP);
                    for (int i = 0; i < 3; i++) assertEquals(linear(c)[i] * SceneCompositeMath.NO_SKY_EXPOSURE, near[i], 1e-5f);
                }
                if (c[3] == FoliageMarker.EMISSIVE_ALPHA) {
                    float[] lamp = SceneCompositeMath.composite(surface(c, NORTH, NAN4, -1, new float[]{0, 15, 1}, 0, false, 0), s, LIGHTMAP);
                    float g = SceneCompositeMath.lightGlow(c, s[SceneCompositeMath.GLOW][0]);
                    for (int i = 0; i < 3; i++) assertEquals(linear(c)[i] * g, lamp[i], 1e-5f);
                }
                //Far away a surface turns into the fog colour
                float[] far = SceneCompositeMath.composite(surface(c, UP, NAN4, -1, new float[]{0, 0, 1}, 0, false, 1e6f), s, LIGHTMAP);
                assertArrayEquals(fog, rgb(far), 1e-4f);
            }
        }
    }

    // ---- 5. plant and light-source marks; the held light and the light volume only when their slots say ----

    private static float[][] noonLit() {
        return scene(settings(c -> c.fogStrength = 0), frame(NOON), true, true, 1);
    }

    @Test
    void plantAndLightSourceMarksAreRecognisedAndTheHeldLightAndVolumeFollowTheirSlots() {
        assertEquals(LightingMath.PLANT_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.ALPHA));
        assertEquals(LightingMath.EMISSIVE_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.EMISSIVE_ALPHA));
        float[][] s = noonLit();
        assertEquals(1f, s[SceneCompositeMath.LIGHTING][2], "the pass ran");
        assertTrue(s[SceneCompositeMath.LIGHTING][1] > 0.9f, "full sun weight");
        float[] plant = {0.3f, 0.6f, 0.2f, FoliageMarker.ALPHA};
        float[] sunlit = {1, 1, 0, 1}, shaded = {1, 0, 0, 0};
        float[] light = {15, 0, 1};
        //B13: a plant takes its own full-resolution sun visibility, whatever the half-resolution lighting says
        float[] inSun = SceneCompositeMath.composite(surface(plant, NONE, shaded, 1, light, 0, false, 20), s, LIGHTMAP);
        float[] inShadow = SceneCompositeMath.composite(surface(plant, NONE, sunlit, 0, light, 0, false, 20), s, LIGHTMAP);
        assertTrue(inSun[1] > inShadow[1] * 1.5f, Arrays.toString(inSun) + " " + Arrays.toString(inShadow));
        assertArrayEquals(inSun, SceneCompositeMath.composite(surface(plant, NONE, sunlit, 1, light, 0, false, 20), s, LIGHTMAP), 1e-6f);
        //Without its own value (-1) the plant takes lightingAt's
        float[] fallback = SceneCompositeMath.composite(surface(plant, NONE, sunlit, -1, light, 0, false, 20), s, LIGHTMAP);
        assertArrayEquals(inSun, fallback, 1e-6f);
        //Plants are lit as if facing the light: no face shade is divided out of them (as of flat ground)
        float[] ground = {0.3f, 0.6f, 0.2f, 1};
        assertArrayEquals(SceneCompositeMath.composite(surface(ground, UP, sunlit, -1, light, 0, false, 20), s, LIGHTMAP), inSun, 1e-5f);
        //A light source keeps its brightness (times its glow, 2026-10-28): no sun shading, no tint, no relighting, whatever the inputs
        float[] lamp = {1, 0.9f, 0.5f, FoliageMarker.EMISSIVE_ALPHA};
        float lampGlow = SceneCompositeMath.lightGlow(lamp, s[SceneCompositeMath.GLOW][0]);
        for (float[] lt : new float[][]{sunlit, shaded}) {
            for (float held : new float[]{0, 15}) {
                float[] out = SceneCompositeMath.composite(surface(lamp, NORTH, lt, -1, new float[]{0, 15, 1}, held, false, 20), s, LIGHTMAP);
                for (int i = 0; i < 3; i++) assertEquals(linear(lamp)[i] * lampGlow, out[i], 1e-5f);
            }
        }
        assertTrue(COMP.contains("bool plant = isPlant(colour.a);") && COMP.contains("bool emissive = isEmissive(colour.a);"));
        assertTrue(COMP.contains("if (!cloud && !emissive) {"));
        assertTrue(COMP.contains("float direct = ownSun ? plantSun : lt.y;"));

        //B16: the held light only with its slot on (and the light volume's camera)
        //In a dark room vanilla's colour is the albedo times the lightmap's dark floor (display 0.04)
        float[] stone = {0.5f * 0.04f, 0.5f * 0.04f, 0.5f * 0.04f, 1};
        float[] dark = {0, 0, 1};
        float[][] night = scene(settings(c -> c.fogStrength = 0), frame(MIDNIGHT), true, true, 0);
        float[] off = SceneCompositeMath.composite(surface(stone, NORTH, shaded, -1, dark, 14, false, 3), night, LIGHTMAP);
        assertArrayEquals(SceneCompositeMath.composite(surface(stone, NORTH, shaded, -1, dark, 0, false, 3), night, LIGHTMAP), off, 1e-6f,
                "slot 24 .w 0: no held light");
        night[SceneCompositeMath.HELD_ORIGIN][3] = 1;
        float[] on = SceneCompositeMath.composite(surface(stone, NORTH, shaded, -1, dark, 14, false, 3), night, LIGHTMAP);
        assertTrue(on[0] > off[0] * 4, Arrays.toString(on) + " " + Arrays.toString(off));
        //Never brighter than a white surface under the held light (2026-10-28: times the torch light's centre gain at most)
        float[] white = SceneCompositeMath.composite(surface(new float[]{0.04f, 0.04f, 0.04f, 1}, NORTH, shaded, -1, dark, 14, false, 3), night, LIGHTMAP);
        float[] heldColour = LIGHTMAP.linear(14, 0);
        float centreGain = (float) Math.pow(1 / SceneCompositeMath.BLOCK_LIGHT_KNEE, SceneCompositeMath.BLOCK_LIGHT_FALLOFF);
        for (int i = 0; i < 3; i++) assertTrue(white[i] <= Math.max(heldColour[0], Math.max(heldColour[1], heldColour[2])) * centreGain * 1.0001f, Arrays.toString(white));
        //Without the light volume's camera there is no held light either
        night[SceneCompositeMath.LIGHT_CAMERA][3] = 0;
        assertArrayEquals(off, SceneCompositeMath.composite(surface(stone, NORTH, shaded, -1, dark, 14, false, 3), night, LIGHTMAP), 1e-6f);
        //The held gain's bound is HeldLight's
        for (float lit : new float[]{0.001f, 0.05f, 0.3f, 1}) {
            for (float gain : new float[]{1, 4, 40, 256}) {
                for (float heldMax : new float[]{0.1f, 0.6f, 1}) {
                    assertEquals(HeldLight.whiteSurfaceScale(lit, gain, heldMax), SceneCompositeMath.heldRoom(lit, gain, heldMax), 1e-6f);
                }
            }
        }
        assertEquals(HeldLight.SIDE, SceneCompositeMath.HELD_GRID_SIDE);
        //Tuning round 1 (refs/S12): the held light falls off HELD_FALLOFF times as fast as vanilla's spread, from the eye's level
        assertEquals(14f, SceneCompositeMath.heldFalloff(14, 14));
        assertEquals(14f - SceneCompositeMath.HELD_FALLOFF * 3, SceneCompositeMath.heldFalloff(11, 14), 1e-6f);
        assertEquals(0f, SceneCompositeMath.heldFalloff(4, 14), "far surfaces are not lifted");
        assertEquals(0f, SceneCompositeMath.heldFalloff(0, 14));
        for (float level = 0; level <= 14; level += 0.5f) {
            assertTrue(SceneCompositeMath.heldFalloff(level, 14) <= level + 1e-6f, "never above vanilla's spread");
        }
        assertTrue(COMP.contains("blockT = max(blockT, heldFalloff(held, float(heldCell(ivec3(HELD_GRID_SIDE/2)))));"));
        assertEquals(HeldLight.MAX_GAIN, SceneCompositeMath.HELD_MAX_GAIN);
        assertTrue(COMP.contains("if (heldOrigin.w > 0.5 && lightCamera.w > 0.5 && !fromLod) {"));

        //The light volume: unknown light relights nothing; known light under an island lifts where the sun reaches (Sky Light Ambient)
        stone = new float[]{0.5f, 0.5f, 0.5f, 1};
        float[] underIsland = {4, 0, 1};
        float[] lifted = SceneCompositeMath.composite(surface(stone, UP, sunlit, -1, underIsland, 0, false, 20), s, LIGHTMAP);
        float[] notLifted = SceneCompositeMath.composite(surface(stone, UP, new float[]{1, 0, 0, 0}, -1, underIsland, 0, false, 20), s, LIGHTMAP);
        assertTrue(lifted[0] > notLifted[0] * 1.5f, Arrays.toString(lifted) + " " + Arrays.toString(notLifted));
        float[][] noVolume = scene(settings(c -> c.fogStrength = 0), frame(NOON), true, false, 1);
        assertEquals(0f, noVolume[SceneCompositeMath.LIGHT_CAMERA][3]);
        assertEquals(0f, noVolume[SceneCompositeMath.LIGHT_FRAC][3], "no lift without the volume");
        assertTrue(COMP.contains("vec3 pl = pixelLight(p, size, world, lightNormal);"));
    }

    // ---- 6. the light colour (slots 22, 23) ----

    @Test
    void theLightColourWarmsTheSunCoolsTheShadeLeavesTorchesAndStrengthZeroChangesNothing() {
        float[] grey = {0.6f, 0.6f, 0.6f, 1};
        float[] sunlit = {1, 1, 0, 1}, shaded = {1, 0, 0, 0};
        for (float time : new float[]{NOON, SUNSET}) {
            float[][] coloured = scene(settings(c -> {
                c.fogStrength = 0;
                c.lightColour = 100;
            }), frame(time), true, true, 1);
            float[][] plain = scene(settings(c -> {
                c.fogStrength = 0;
                c.lightColour = 0;
            }), frame(time), true, true, 1);
            assertEquals(0f, plain[SceneCompositeMath.LIGHT_SUN][3], "strength 0");
            assertTrue(coloured[SceneCompositeMath.LIGHT_SUN][3] > 0 && coloured[SceneCompositeMath.LIGHT_SHADE][3] > 0);
            float[] sun = SceneCompositeMath.composite(surface(grey, UP, sunlit, new float[]{15, 0, 1}), coloured, LIGHTMAP);
            float[] sunPlain = SceneCompositeMath.composite(surface(grey, UP, sunlit, new float[]{15, 0, 1}), plain, LIGHTMAP);
            float[] shade = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{15, 0, 1}), coloured, LIGHTMAP);
            float[] shadePlain = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{15, 0, 1}), plain, LIGHTMAP);
            assertTrue(sun[0] / sun[2] > sunPlain[0] / sunPlain[2] * 1.01f, "sunlit warmer " + Arrays.toString(sun));
            //2026-10-28: a low sun's warmth reaches the shade too (ColourMath.warmthAt); it stays cooler than the sunlit side
            if (time == NOON) assertTrue(shade[2] / shade[0] > shadePlain[2] / shadePlain[0] * 1.02f, "shade cooler " + Arrays.toString(shade));
            assertTrue(shade[2] / shade[0] > sun[2] / sun[0] * 1.02f, "shade cooler than the sun " + Arrays.toString(shade));
            //Torch light (no sky light) keeps its own colour
            float[] torch = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 14, 1}), coloured, LIGHTMAP);
            float[] torchPlain = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 14, 1}), plain, LIGHTMAP);
            assertArrayEquals(torchPlain, torch, 1e-6f);
            //Strength 0 changes nothing whatever the colours in slots 22/23
            float[][] odd = scene(settings(c -> {
                c.fogStrength = 0;
                c.lightColour = 0;
            }), frame(time), true, true, 1);
            odd[SceneCompositeMath.LIGHT_SUN] = new float[]{5, 0, 0, 0};
            odd[SceneCompositeMath.LIGHT_SHADE] = new float[]{0, 0, 5, odd[SceneCompositeMath.LIGHT_SHADE][3]};
            assertArrayEquals(sunPlain, SceneCompositeMath.composite(surface(grey, UP, sunlit, new float[]{15, 0, 1}), odd, LIGHTMAP), 1e-6f);
        }
        //The tint has luminance 1 (it moves the hue, not the brightness) and fades with the sun's weight (night, rain, underground)
        for (float share : new float[]{0, 0.3f, 1, 1.5f}) {
            float[] t = SceneCompositeMath.lightTint(share, 1, ColourMath.sunLight(0.05f), ColourMath.SKY_SHADE);
            assertEquals(1f, PostMath.luminance(t[0], t[1], t[2]), 0.01f);
            assertArrayEquals(new float[]{1, 1, 1}, SceneCompositeMath.lightTint(share, 0, ColourMath.sunLight(0.05f), ColourMath.SKY_SHADE), 0f);
        }
        float[][] midnight = scene(settings(c -> c.lightColour = 100), frame(MIDNIGHT), true, true, 1);
        assertEquals(0f, midnight[SceneCompositeMath.LIGHT_SHADE][3] * midnight[SceneCompositeMath.LIGHT_SUN][3], 1e-6f, "no tint at night");
        assertTrue(COMP.contains("lightTint(directShare, lightSun.w*lightShade.w, lightSun.rgb, lightShade.rgb)"));
    }

    // ---- 6b. 2026-10-28: sun warmth, torch light colour (slot 31) and moonlight colour (slot 32) ----

    @Test
    void sunWarmthLowersTheSunlightsColourTemperatureAtLuminanceOne() {
        for (float h : new float[]{0.05f, 0.3f, 1}) {
            float[] plain = ColourMath.sunLight(h), warm = ColourMath.sunLight(h, 1), warmer = ColourMath.sunLight(h, 2);
            assertArrayEquals(plain, ColourMath.sunLight(h, 0), 0f);
            assertTrue(warm[0] / warm[2] > plain[0] / plain[2] * 1.3f && warmer[0] / warmer[2] > warm[0] / warm[2], Arrays.toString(warm));
            assertEquals(1f, PostMath.luminance(warm[0], warm[1], warm[2]), 1e-4f);
        }
        assertEquals(4000f, ColourMath.warmKelvin(1), 20f);
        float[] shade = ColourMath.skyShade(1);
        assertTrue(shade[0] / shade[2] > ColourMath.SKY_SHADE[0] / ColourMath.SKY_SHADE[2] && shade[0] < shade[2] * 1.2f, "a warmer shade " + Arrays.toString(shade));
        assertArrayEquals(ColourMath.SKY_SHADE, ColourMath.skyShade(0), 0f);
        assertArrayEquals(new float[]{1, 1, 1}, ColourMath.temperatureTint(ColourMath.WHITE_KELVIN), 1e-5f);
        float[] torch = ColourMath.BLOCK_LIGHT, moon = ColourMath.MOON_LIGHT;
        assertTrue(torch[0] > torch[1] && torch[1] > torch[2], "torch light is orange " + Arrays.toString(torch));
        assertTrue(moon[2] > moon[1] && moon[1] > moon[0], "moonlight is blue " + Arrays.toString(moon));
    }

    @Test
    void torchLightTurnsWarmFallsOffFasterAndStrengthZeroIsVanilla() {
        float[] grey = {0.6f, 0.6f, 0.6f, 1};
        float[] shaded = {1, 0, 0, 0};
        float[][] warm = scene(settings(c -> {
            c.fogStrength = 0;
            c.blockLightColour = 100;
        }), frame(MIDNIGHT), true, true, 1);
        float[][] plain = scene(settings(c -> {
            c.fogStrength = 0;
            c.blockLightColour = 0;
        }), frame(MIDNIGHT), true, true, 1);
        assertEquals(1f, warm[SceneCompositeMath.BLOCK_LIGHT][3]);
        assertEquals(0f, plain[SceneCompositeMath.BLOCK_LIGHT][3]);
        float[] near = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 15, 1}), warm, LIGHTMAP);
        float[] nearPlain = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 15, 1}), plain, LIGHTMAP);
        //Beside the source (level 15) the light takes the warm colour at its own luminance: per channel the factor from the lightmap's
        // colour (the fixture's is already orange) to warm x its luminance, lifted by the knee's centre gain (1/BLOCK_LIGHT_KNEE)^FALLOFF
        float[] lm = LIGHTMAP.linear(15, 0), dark = LIGHTMAP.linear(0, 0), warmColour = ColourMath.BLOCK_LIGHT;
        //The no-light floor stays as it is; the light above it is recoloured and lifted
        float l = PostMath.luminance(lm[0] - dark[0], lm[1] - dark[1], lm[2] - dark[2]);
        float centre = (float) Math.pow(1 / SceneCompositeMath.BLOCK_LIGHT_KNEE, SceneCompositeMath.BLOCK_LIGHT_FALLOFF);
        assertTrue(centre > 1.3f, "brighter right at the source");
        for (int i = 0; i < 3; i++) assertEquals((dark[i] + l * warmColour[i] * centre) / lm[i], near[i] / nearPlain[i], 1e-3f, Arrays.toString(near));
        assertTrue(near[0] / near[2] > nearPlain[0] / nearPlain[2] * 1.5f, "warmer " + Arrays.toString(near));
        float[] far = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 7, 1}), warm, LIGHTMAP);
        float[] farPlain = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 7, 1}), plain, LIGHTMAP);
        assertTrue(PostMath.luminance(far[0], far[1], far[2]) < PostMath.luminance(farPlain[0], farPlain[1], farPlain[2]) * 0.9f, "darker away from it");
        for (float[] px : new float[][]{near, far}) for (float c : px) assertTrue(Float.isFinite(c) && c >= 0);
        //Sky light alone is not touched
        float[] open = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{15, 0, 1}), warm, LIGHTMAP);
        assertArrayEquals(SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{15, 0, 1}), plain, LIGHTMAP), open, 1e-6f);
        assertArrayEquals(new float[]{1, 1, 1}, SceneCompositeMath.blockLightFactor(new float[]{0.2f, 0.1f, 0.05f}, new float[3], new float[]{0.2f, 0.1f, 0.05f},
                new float[]{1, 1, 1}, new float[3], ColourMath.BLOCK_LIGHT, 0), 0f);
        assertTrue(COMP.contains("blockShare*floorShare"));
    }

    @Test
    void torchLightKeepsTheNoLightFloor() {
        //2026-10-28 (nether shots: soul sand valley and cave walls went black): a dimension's ambient light is the lightmap's no-light
        // floor; with no sky light all of it is the block-lit share, and the falloff must not take it away
        SceneCompositeMath.Lightmap nether = (block, sky) -> {
            float b = brightness(block);
            float[] warm = {1, 0.85f, 0.6f};
            float[] out = new float[3];
            for (int i = 0; i < 3; i++) out[i] = PostMath.displayToLinear(Math.min(1, 0.35f + 0.65f * b * warm[i]));
            return out;
        };
        float[] grey = {0.6f, 0.6f, 0.6f, 1};
        float[] shaded = {1, 0, 0, 0};
        float[][] warm = scene(settings(c -> {
            c.fogStrength = 0;
            c.blockLightColour = 100;
        }), frame(MIDNIGHT), true, true, 1);
        float[][] plain = scene(settings(c -> {
            c.fogStrength = 0;
            c.blockLightColour = 0;
        }), frame(MIDNIGHT), true, true, 1);
        float[] unlit = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 0, 1}), warm, nether);
        assertArrayEquals(SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 0, 1}), plain, nether), unlit, 1e-6f);
        //A dim block light still gives at least the floor: the fixture's scene does not follow the level, so the pixel's factor times
        // vanilla's light at level 3 is compared with vanilla's light at level 0
        float[] dim = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 3, 1}), warm, nether);
        float[] dimPlain = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{0, 3, 1}), plain, nether);
        float[] lm3 = nether.linear(3, 0), lm0 = nether.linear(0, 0);
        float lit = PostMath.luminance(dim[0], dim[1], dim[2]) / PostMath.luminance(dimPlain[0], dimPlain[1], dimPlain[2])
                * PostMath.luminance(lm3[0], lm3[1], lm3[2]);
        assertTrue(lit >= PostMath.luminance(lm0[0], lm0[1], lm0[2]) * 0.999f, lit + " vs floor " + Arrays.toString(lm0));
    }

    // ---- 6c. 2026-10-28: block glow and ore glow (slot 33) ----

    private static float[][] glowScene(int blockGlow, int oreGlow) {
        return scene(settings(c -> {
            c.fogStrength = 0;
            c.blockGlow = blockGlow;
            c.oreGlow = oreGlow;
        }), frame(MIDNIGHT), false, true, 1);
    }

    @Test
    void lightSourcesGlowBrightTexelsAndStrengthZeroIsVanilla() {
        float[][] on = glowScene(100, 100), off = glowScene(0, 100);
        assertEquals(1f, on[SceneCompositeMath.GLOW][0]);
        assertEquals(0f, off[SceneCompositeMath.GLOW][0]);
        float[] shaded = {1, 0, 0, 0};
        float[] lava = {0.85f, 0.40f, 0.08f, FoliageMarker.EMISSIVE_ALPHA}, rock = {0.20f, 0.08f, 0.06f, FoliageMarker.EMISSIVE_ALPHA};
        float[] bright = SceneCompositeMath.composite(surface(lava, UP, shaded, new float[]{0, 15, 1}), on, LIGHTMAP);
        float[] plain = SceneCompositeMath.composite(surface(lava, UP, shaded, new float[]{0, 15, 1}), off, LIGHTMAP);
        assertArrayEquals(linear(lava), rgb(plain), 1e-6f);
        float g = SceneCompositeMath.lightGlow(lava, 1);
        assertTrue(g > 2.2f && g <= 1 + SceneCompositeMath.LIGHT_GLOW_GAIN, "lava glows " + g);
        for (int i = 0; i < 3; i++) assertEquals(linear(lava)[i] * g, bright[i], 1e-5f);
        //Dark texels of a light source (magma's rock) stay as they are
        float[] dark = SceneCompositeMath.composite(surface(rock, UP, shaded, new float[]{0, 15, 1}), on, LIGHTMAP);
        assertArrayEquals(linear(rock), rgb(dark), 1e-6f);
        //2026-10-28: a light-emitting fluid's mark is a light source's with FLUID_GLOW_SCALE times the glow
        assertEquals(LightingMath.EMISSIVE_FLUID_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.EMISSIVE_FLUID_ALPHA));
        float[] fluid = {lava[0], lava[1], lava[2], FoliageMarker.EMISSIVE_FLUID_ALPHA};
        float[] lake = SceneCompositeMath.composite(surface(fluid, UP, shaded, new float[]{0, 15, 1}), on, LIGHTMAP);
        float gf = SceneCompositeMath.lightGlow(lava, SceneCompositeMath.FLUID_GLOW_SCALE);
        assertTrue(gf > g * 1.5f, gf + " vs " + g);
        for (int i = 0; i < 3; i++) assertEquals(linear(lava)[i] * gf, lake[i], 1e-4f);
        assertArrayEquals(linear(lava), rgb(SceneCompositeMath.composite(surface(fluid, UP, shaded, new float[]{0, 15, 1}), off, LIGHTMAP)), 1e-6f);
        assertTrue(COMP.contains("lit = scene*lightGlow(colour.rgb, glowParams.x*(alphaCode(colour.a) == EMISSIVE_FLUID_ALPHA_CODE ? FLUID_GLOW_SCALE : 1.0));"));
        assertTrue(COMP.contains("layout(location = 33) uniform vec4 glowParams;"));
    }

    @Test
    void oreSpecksGlowAndTheirHostStoneDoesNot() {
        assertEquals(LightingMath.ORE_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.ORE_ALPHA));
        float[] stone = {0.5f, 0.5f, 0.5f}, coal = {0.15f, 0.15f, 0.15f}, redstone = {0.9f, 0.08f, 0.06f}, netherrack = {0.42f, 0.19f, 0.18f};
        float[] quartz = {0.86f, 0.82f, 0.77f}, gold = {0.98f, 0.85f, 0.3f}, iron = {0.85f, 0.7f, 0.6f}, copper = {0.85f, 0.45f, 0.3f};
        java.util.function.BiFunction<float[], float[], float[][]> around = (host, speck) -> {
            float[][] n = new float[8][];
            for (int i = 0; i < 8; i++) n[i] = i < 2 && speck != null ? speck : host;
            return n;
        };
        //2026-10-28 (in game: white outlines around coal, redstone and nether gold): stone beside dark or coloured specks is no speck,
        // nor a coal speck itself, nor netherrack among netherrack
        assertEquals(0f, SceneCompositeMath.oreSpeck(stone, around.apply(stone, coal)), 1e-6f, "stone beside coal");
        assertEquals(0f, SceneCompositeMath.oreSpeck(stone, around.apply(stone, redstone)), 1e-6f, "stone beside redstone");
        assertEquals(0f, SceneCompositeMath.oreSpeck(coal, around.apply(stone, null)), 1e-6f, "coal");
        assertEquals(0f, SceneCompositeMath.oreSpeck(netherrack, around.apply(netherrack, quartz)), 1e-6f, "netherrack beside quartz");
        //Specks: quartz by its brightness against netherrack, gold, redstone, copper and iron (2026-10-28: was dark) by their saturation
        assertEquals(1f, SceneCompositeMath.oreSpeck(quartz, around.apply(netherrack, null)), 1e-6f, "quartz");
        assertEquals(1f, SceneCompositeMath.oreSpeck(gold, around.apply(netherrack, null)), 1e-6f, "nether gold");
        assertEquals(1f, SceneCompositeMath.oreSpeck(redstone, around.apply(stone, null)), 1e-6f, "redstone");
        assertEquals(1f, SceneCompositeMath.oreSpeck(copper, around.apply(stone, null)), 1e-6f, "copper");
        assertTrue(SceneCompositeMath.oreSpeck(iron, around.apply(stone, null)) > 0.9f, "iron");
        //2026-10-28 (close up, iron's grey rims and redstone's pale highlights went white): a pale texel in grey stone or deepslate is
        // no speck, however bright; in netherrack it is (quartz)
        float[] rim = {0.75f, 0.73f, 0.7f}, deepslate = {0.3f, 0.3f, 0.31f};
        assertEquals(0f, SceneCompositeMath.oreSpeck(rim, around.apply(stone, null)), 1e-6f, "iron's rim in stone");
        assertEquals(0f, SceneCompositeMath.oreSpeck(rim, around.apply(deepslate, null)), 1e-6f, "iron's rim in deepslate");
        //A speck is one on any face: the face shade scales the pixel and its neighbours alike (copper glowed on three faces only)
        for (float shade : new float[]{0.8f, 0.6f, 0.5f}) {
            float[] c = {copper[0] * shade, copper[1] * shade, copper[2] * shade}, s = {stone[0] * shade, stone[1] * shade, stone[2] * shade};
            assertEquals(1f, SceneCompositeMath.oreSpeck(c, around.apply(s, null)), 1e-6f, "copper at face shade " + shade);
        }
        //No neighbours, or too dark to tell: no speck
        assertEquals(0f, SceneCompositeMath.oreSpeck(redstone, new float[0][]), 0f);
        assertEquals(0f, SceneCompositeMath.oreSpeck(new float[]{0.02f, 0.001f, 0.001f}, around.apply(new float[]{0.01f, 0.01f, 0.01f}, null)), 0f);
        //The glow divides the face's vanilla shade out, so a bottom face glows as a top face does (lower faces were darker)
        float[] top = SceneCompositeMath.oreGlow(gold, 1, SceneCompositeMath.bakedShade(UP), 1);
        float[] down = {0, -1, 0};
        float shadeDown = SceneCompositeMath.FACE_SHADE_DOWN;
        float[] bottom = SceneCompositeMath.oreGlow(new float[]{gold[0] * shadeDown, gold[1] * shadeDown, gold[2] * shadeDown}, 1,
                SceneCompositeMath.bakedShade(down), 1);
        //(by luminance: a dark channel sits on sRGB's linear toe, where half the display value is not half the light)
        assertEquals(PostMath.luminance(top[0], top[1], top[2]), PostMath.luminance(bottom[0], bottom[1], bottom[2]),
                PostMath.luminance(top[0], top[1], top[2]) * 0.03f, Arrays.toString(bottom) + " vs " + Arrays.toString(top));
        for (float v : SceneCompositeMath.oreGlow(new float[]{1, 1, 1}, 1, SceneCompositeMath.bakedShade(down), 1)) {
            assertTrue(v <= SceneCompositeMath.ORE_GLOW_LEVEL * PostMath.SCENE_WHITE + 1e-6f, "never above white");
        }
        assertArrayEquals(new float[3], SceneCompositeMath.oreGlow(gold, 0, 1, 1), 0f);
        //The mirror composite has no neighbours: an ore pixel is lit as any block there (the shader reads the ore around it)
        float[][] on = glowScene(100, 100), off = glowScene(100, 0);
        float[] shaded = {1, 0, 0, 0};
        float[] marked = {gold[0] * 0.3f, gold[1] * 0.3f, gold[2] * 0.3f, FoliageMarker.ORE_ALPHA};
        assertArrayEquals(SceneCompositeMath.composite(surface(marked, UP, shaded, new float[]{0, 12, 1}), off, LIGHTMAP),
                SceneCompositeMath.composite(surface(marked, UP, shaded, new float[]{0, 12, 1}), on, LIGHTMAP), 1e-6f);
        assertTrue(COMP.contains("float speck = oreSpeckAt(p, size, colour.rgb, oreHostStep(size, dist));"));
        assertTrue(COMP.contains("lit += oreGlow(base, speck, bakedShade(normalWorld), glowParams.y);"));
    }

    @Test
    void moonlightTurnsTheNightsSkyLitLightBlueAndLeavesTheDay() {
        float[] grey = {0.6f, 0.6f, 0.6f, 1};
        float[] shaded = {1, 0, 0, 0};
        float[][] night = scene(settings(c -> {
            c.fogStrength = 0;
            c.lightColour = 100;
        }), frame(MIDNIGHT), true, true, 1);
        float[][] nightPlain = scene(settings(c -> {
            c.fogStrength = 0;
            c.lightColour = 0;
        }), frame(MIDNIGHT), true, true, 1);
        assertEquals(1f, night[SceneCompositeMath.MOON_LIGHT][3], 1e-5f);
        float[] moonlit = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{15, 0, 1}), night, LIGHTMAP);
        float[] plain = SceneCompositeMath.composite(surface(grey, UP, shaded, new float[]{15, 0, 1}), nightPlain, LIGHTMAP);
        assertTrue(moonlit[2] / moonlit[0] > plain[2] / plain[0] * 1.3f, Arrays.toString(moonlit) + " " + Arrays.toString(plain));
        float[][] noon = scene(settings(c -> c.lightColour = 100), frame(NOON), true, true, 1);
        assertEquals(0f, noon[SceneCompositeMath.MOON_LIGHT][3], 1e-5f, "no moonlight by day");
        assertArrayEquals(new float[]{1, 1, 1}, SceneCompositeMath.moonTint(new float[]{0.5f, 1, 2, 0}), 0f);
    }

    // ---- the other required behaviours ----

    /** B11, B12, W6's contract: water keeps the sun shadow but no face shading, keeps its side sky, takes W6's colour and its mark in alpha. */
    @Test
    void waterTakesW6sColourAndMarkAndKeepsItsSideSky() {
        float[][] s = noonLit();
        float[] water = {0.15f, 0.3f, 0.6f, 1};
        float[] lit = {1, 1, 6, 1}, shadowed = {1, 0, 6, 0}, ice = {1, 1, -1, 1};
        SceneCompositeMath.Pixel open = surface(water, UP, lit, new float[]{15, 0, 1});
        float[] out = SceneCompositeMath.composite(open, s, LIGHTMAP);
        assertEquals(WaterMath.reflectionMark(6), out[3]);
        assertEquals(WaterMath.reflectionMark(-1), SceneCompositeMath.composite(surface(water, UP, ice, new float[]{15, 0, 1}), s, LIGHTMAP)[3]);
        float[] shade = SceneCompositeMath.composite(surface(water, UP, shadowed, new float[]{15, 0, 1}), s, LIGHTMAP);
        assertTrue(shade[2] < out[2] * 0.8f, "an island's shadow falls on the sea");
        //The water colour (W6) depends on the depth strength: 0 gives the lit pixel back
        float[][] noDepth = scene(settings(c -> {
            c.fogStrength = 0;
            c.waterDepth = 0;
        }), frame(NOON), true, true, 1);
        float[] flat = SceneCompositeMath.composite(open, noDepth, LIGHTMAP);
        assertNotEquals(flat[0], out[0], 1e-5f);
        //B12: water under an overhang that sees the sky sideways keeps that light
        SceneCompositeMath.Pixel covered = new SceneCompositeMath.Pixel(water, false, dir(0.3f, -0.4f, -1), 40, false, UP, shadowed, -1,
                new float[]{2, 0, 1}, 0, 0, false, new float[3]);
        SceneCompositeMath.Pixel sideways = new SceneCompositeMath.Pixel(water, false, dir(0.3f, -0.4f, -1), 40, false, UP, shadowed, -1,
                new float[]{2, 0, 1}, 1, 0, false, new float[3]);
        assertTrue(SceneCompositeMath.composite(sideways, s, LIGHTMAP)[2] > SceneCompositeMath.composite(covered, s, LIGHTMAP)[2] * 1.5f);
        //The composite never darkens water by its reflectivity itself (no Fresnel or reflectivity call)
        assertFalse(COMP.contains("reflectivity("));
        assertTrue(COMP.contains("lit = waterColour(lit, kind*waterParams.x, lightAll*perLight*ao*waterParams.y);"));
    }

    /** B3: vanilla's own clouds take no sun shading and no light colour. */
    @Test
    void vanillasCloudsTakeNoLighting() {
        float[] shape = {72, 4, 0, 0};//far clouds off, layer given
        float reach = SceneCompositeMath.volumeReach(25);//12 sections
        assertEquals(192f, reach);
        float[] near = {100, 74, 50}, far = {150, 74, -300};
        assertTrue(SceneCompositeMath.vanillaCloud(near, 120, shape, true, 0xF, reach), "open air under the sky");
        assertTrue(SceneCompositeMath.vanillaCloud(near, 120, shape, true, 0x3F, reach), "block light does not matter");
        assertFalse(SceneCompositeMath.vanillaCloud(near, 120, shape, true, 0, reach), "an opaque block at the layer's height is terrain");
        assertFalse(SceneCompositeMath.vanillaCloud(near, 120, shape, true, 0xE, reach), "a cell short of full sky light (leaves, under cover)");
        assertFalse(SceneCompositeMath.vanillaCloud(near, 120, shape, true, -1, reach), "unknown inside the volume: a section not uploaded yet");
        assertTrue(SceneCompositeMath.vanillaCloud(far, 340, shape, true, -1, reach), "unknown beyond the volume: no vanilla terrain there");
        assertFalse(SceneCompositeMath.vanillaCloud(far, 340, shape, false, -1, reach), "without the light volume nothing is taken for a cloud");
        assertFalse(SceneCompositeMath.vanillaCloud(new float[]{100, 60, 50}, 120, shape, true, 0xF, reach), "below the layer");
        assertFalse(SceneCompositeMath.vanillaCloud(near, 120, new float[]{72, 4, 0, 1}, true, 0xF, reach), "far clouds on: vanilla's are not drawn");
        assertFalse(SceneCompositeMath.vanillaCloud(new float[]{0, 0, 0}, 0, new float[]{0, 0, 0, 0}, true, 0xF, reach), "no layer given");
        //Flat (fast) clouds: thickness 0, the margin
        float[] flat = {72, 0, 0, 0};
        assertTrue(SceneCompositeMath.vanillaCloud(new float[]{10, 72.2f, 10}, 20, flat, true, 0xF, reach));
        assertFalse(SceneCompositeMath.vanillaCloud(new float[]{10, 73.5f, 10}, 20, flat, true, 0xF, reach));
        assertTrue(COMP.contains("return (behind & 15) >= VANILLA_CLOUD_SKY;"));
        assertTrue(COMP.contains("return max(abs(world.x), abs(world.z)) > volumeReach();"));
        float[][] s = scene(DEFAULT, frame(NOON), true, true, 1);
        //A cloud 300 blocks out lies past the render distance: without the border fog (BorderFogTest), which would fade it
        s[SceneCompositeMath.BORDER_FOG] = new float[4];
        float[] cloud = {0.9f, 0.9f, 0.9f, 0.8f};
        AtmosphereMath.Atmosphere a = SceneCompositeMath.atmosphere(s);
        for (float[] lt : new float[][]{{1, 0, 0, 0}, {1, 1.4f, 0, 1}}) {
            SceneCompositeMath.Pixel px = new SceneCompositeMath.Pixel(cloud, false, dir(0.2f, 0.3f, -1), 300, false, NONE, lt, -1,
                    new float[]{15, 0, 1}, 0, 0, true, new float[3]);
            float[] out = SceneCompositeMath.composite(px, s, LIGHTMAP);
            assertArrayEquals(AtmosphereMath.applyHaze(a, linear(cloud), px.dir(), 300, 1), rgb(out), 1e-5f);
            assertEquals(0f, out[3]);
        }
    }

    /** Sky pixels: W4's sky and glow, W6's disc (the shafts need it above SHAFT_THRESHOLD), vanilla's stars through W4. */
    @Test
    void theSkyIsW4sWithW6sDiscAndVanillasStars() {
        float[][] s = scene(DEFAULT, frame(NOON), true, true, 1);
        AtmosphereMath.Atmosphere a = SceneCompositeMath.atmosphere(s);
        float[] sun = {a.sun()[0], a.sun()[1], a.sun()[2]};
        float[] atSun = SceneCompositeMath.composite(skyPixel(new float[]{1, 1, 1, 1}, sun), s, LIGHTMAP);
        assertTrue(PostMath.luminance(atSun[0], atSun[1], atSun[2]) > AtmosphereMath.SHAFT_THRESHOLD * 4, Arrays.toString(atSun));
        float[] zenith = dir(0.5f, 0.6f, 0.2f);
        float[] plain = SceneCompositeMath.composite(skyPixel(new float[]{1, 1, 1, 1}, zenith), s, LIGHTMAP);
        float[] expect = new float[3];
        float[] sky = AtmosphereMath.skyColour(a, zenith), glow = AtmosphereMath.sunGlow(a, zenith);
        for (int i = 0; i < 3; i++) expect[i] = sky[i] + glow[i];
        assertArrayEquals(expect, rgb(plain), 1e-6f, "by day vanilla's sun texture never comes back");
        //At night a white star in vanilla's sky stays
        float[][] night = scene(DEFAULT, frame(MIDNIGHT, 0, false, new float[]{0.02f, 0.02f, 0.05f}, new Matrix4f(), new float[]{0.01f, 0.01f, 0.03f}),
                true, true, 1);
        float[] dark = SceneCompositeMath.composite(skyPixel(new float[]{0, 0, 0.02f, 1}, zenith), night, LIGHTMAP);
        float[] star = SceneCompositeMath.composite(skyPixel(new float[]{0.9f, 0.9f, 0.9f, 1}, zenith), night, LIGHTMAP);
        assertTrue(star[0] > dark[0] + 0.3f);
        assertTrue(COMP.contains("skyColour(a, dir) + sunGlow(a, dir) + sunDisc(a, dir) + vanillaStars(a, dir, scene, vanillaSky, vanillaFog, starGate)"));
        assertFalse(COMP.contains("moonDisc("), "vanilla's moon is kept through vanillaStars");
        assertTrue(COMP.contains("farCloudsOver(a, sky, dir, HAZE_MAX_DISTANCE)"));
    }

    /** Vanilla's baked face shade comes out of the sky-lit light under the sun weight (W5's facing replaces it); weight 0 keeps it. */
    @Test
    void vanillasFaceShadeIsNotAppliedOnTopOfW5sFacing() {
        assertEquals(PostMath.displayToLinear(0.6f), SceneCompositeMath.bakedShade(EAST), 1e-6f);
        assertEquals(PostMath.displayToLinear(0.8f), SceneCompositeMath.bakedShade(NORTH), 1e-6f);
        assertEquals(PostMath.displayToLinear(0.5f), SceneCompositeMath.bakedShade(new float[]{0, -1, 0}), 1e-6f);
        assertEquals(1f, SceneCompositeMath.bakedShade(UP));
        assertEquals(1f, SceneCompositeMath.bakedShade(dir(1, 1, 0)), "not a block face");
        assertEquals(1f, SceneCompositeMath.faceUnshade(0.3f, 0));
        float[][] s = noonLit();
        float[] stone = {0.5f, 0.5f, 0.5f, 1};
        float[] shaded = {1, 0, 0, 0};
        //A shaded wall and shaded ground end at the same light: vanilla's darker wall is not darkened again
        float wallDisplay = PostMath.linearToDisplay(PostMath.displayToLinear(0.5f) * SceneCompositeMath.bakedShade(EAST));
        float[] wall = SceneCompositeMath.composite(surface(new float[]{wallDisplay, wallDisplay, wallDisplay, 1}, EAST, shaded, new float[]{15, 0, 1}), s, LIGHTMAP);
        float[] ground = SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{15, 0, 1}), s, LIGHTMAP);
        assertArrayEquals(ground, wall, 0.02f * ground[0]);
        //Sun shading off: the vanilla look
        float[][] off = scene(settings(c -> {
            c.fogStrength = 0;
            c.sunShading = 0;
            c.lightColour = 0;
        }), frame(NOON), true, true, 1);
        float[] wallOff = SceneCompositeMath.composite(surface(stone, EAST, shaded, new float[]{15, 0, 1}), off, LIGHTMAP);
        float exposure = SceneCompositeMath.skyExposure(off[SceneCompositeMath.WATER][2], off[SceneCompositeMath.WATER][3], off[SceneCompositeMath.LOOK][0]);
        assertEquals(1f, exposure, "sun shading 0: the look weight 0 (final fix R1)");
        assertEquals(PostMath.displayToLinear(0.5f) * exposure, wallOff[0], 1e-5f);
    }

    /** B14: block and sky light have their own exposures; nights and the open sky by day keep the sky-lit light as it is. */
    @Test
    void blockAndSkyLightHaveSeparateExposures() {
        assertEquals(1f, SceneCompositeMath.skyExposure(1, 1, 1), "open sky by day");
        assertEquals(1f, SceneCompositeMath.skyExposure(0, 0, 1), "night");
        assertEquals(SceneCompositeMath.UNDERGROUND_SKY_EXPOSURE, SceneCompositeMath.skyExposure(1, 0, 1), 1e-6f, "underground by day");
        assertEquals(1f, SceneCompositeMath.blockExposure(0, 1), "night: torches at full");
        assertEquals(SceneCompositeMath.DAY_BLOCK_EXPOSURE, SceneCompositeMath.blockExposure(1, 1), 1e-6f);
        //Tuning round 1 (refs/S8): by day block light is at DAY_BLOCK_EXPOSURE wherever the camera is; at night at full
        float[] stone = {0.5f, 0.5f, 0.5f, 1};
        float[][] cave = scene(settings(SceneCompositeTest::plainColours), frame(NOON), true, true, 0);
        float[][] open = scene(settings(SceneCompositeTest::plainColours), frame(NOON), true, true, 1);
        float[][] night = scene(settings(SceneCompositeTest::plainColours), frame(MIDNIGHT), true, true, 0);
        float[] shaded = {1, 0, 0, 0};
        float[] torchCave = SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{0, 14, 1}), cave, LIGHTMAP);
        float[] torchOpen = SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{0, 14, 1}), open, LIGHTMAP);
        float[] torchNight = SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{0, 14, 1}), night, LIGHTMAP);
        assertArrayEquals(torchOpen, torchCave, 1e-6f);
        assertArrayEquals(linear(stone), rgb(torchNight), 1e-5f);
        for (int i = 0; i < 3; i++) assertEquals(linear(stone)[i] * SceneCompositeMath.DAY_BLOCK_EXPOSURE, torchCave[i], 1e-5f);
        assertTrue(COMP.contains("float be = blockExposure(waterParams.z, look);"), "final fix R1: whether or not the lighting pass ran");
    }

    /**
     * 2026-10-23: with the albedo (TerrainLightTarget) the composite relights with vanilla's own product at the new levels: the vanilla
     * pixel where nothing changes, and a held torch exactly a placed one's light in a dark room (no gain cap).
     */
    @Test
    void theAlbedoRelightsAsVanillaAndAHeldTorchAsAPlacedOne() {
        float[] albedo = {0.6f, 0.5f, 0.4f}, sunlit = {1, 1, 0, 1}, shaded = {1, 0, 0, 0};
        float[][] s = noonLit();
        s[SceneCompositeMath.LIGHT_CAMERA][3] = 2;
        s[SceneCompositeMath.HELD_ORIGIN][3] = 1;
        java.util.function.BiFunction<Float, Float, float[]> vanilla = (block, sky) -> {
            float[] lmd = SceneCompositeMath.lightmapDisplay(LIGHTMAP, block, sky);
            return new float[]{albedo[0] * lmd[0], albedo[1] * lmd[1], albedo[2] * lmd[2], 1};
        };
        //Unchanged levels: the albedo path is the scene path
        float[] colour = vanilla.apply(0f, 15f);
        float[] withAlbedo = SceneCompositeMath.composite(new SceneCompositeMath.Pixel(colour, false, dir(0.3f, -0.4f, -1), 3, false, UP, sunlit, -1,
                new float[]{15, 0, 2}, 0, 0, false, new float[3], 1, albedo), s, LIGHTMAP);
        float[] without = SceneCompositeMath.composite(new SceneCompositeMath.Pixel(colour, false, dir(0.3f, -0.4f, -1), 3, false, UP, sunlit, -1,
                new float[]{15, 0, 2}, 0, 0, false, new float[3], 1, null), s, LIGHTMAP);
        assertArrayEquals(rgb(without), rgb(withAlbedo), 2e-3f);
        //A dark room: a torch placed (block 14 in vanilla's own light) and one held (the scene lit at block 0, the held light 14)
        float[] placed = SceneCompositeMath.composite(new SceneCompositeMath.Pixel(vanilla.apply(14f, 0f), false, dir(0.3f, -0.4f, -1), 3, false, NORTH,
                shaded, -1, new float[]{0, 14, 2}, 0, 0, false, new float[3], 1, albedo), s, LIGHTMAP);
        float[] held = SceneCompositeMath.composite(new SceneCompositeMath.Pixel(vanilla.apply(0f, 0f), false, dir(0.3f, -0.4f, -1), 3, false, NORTH,
                shaded, -1, new float[]{0, 0, 2}, 0, 14, false, new float[3], 1, albedo), s, LIGHTMAP);
        assertArrayEquals(rgb(placed), rgb(held), 1e-6f);
        //Without its albedo a pixel keeps the gain path; with the volume's light (source 1) the albedo is not read
        float[] volume = SceneCompositeMath.composite(new SceneCompositeMath.Pixel(vanilla.apply(0f, 0f), false, dir(0.3f, -0.4f, -1), 3, false, NORTH,
                shaded, -1, new float[]{0, 0, 1}, 0, 14, false, new float[3], 1, albedo), s, LIGHTMAP);
        float[] volumeNoAlbedo = SceneCompositeMath.composite(new SceneCompositeMath.Pixel(vanilla.apply(0f, 0f), false, dir(0.3f, -0.4f, -1), 3, false, NORTH,
                shaded, -1, new float[]{0, 0, 1}, 0, 14, false, new float[3], 1, null), s, LIGHTMAP);
        assertArrayEquals(rgb(volumeNoAlbedo), rgb(volume), 0f);
    }

    /** 2026-10-23 (CTE2: the view under water turned bright once vanilla's water fog cleared): from inside water it is the water's fog throughout. */
    @Test
    void fromInsideWaterTheViewIsTheWatersFogThroughout() {
        AtmosphereFrame clear = frame(NOON);
        assertFalse(clear.fogCoversAllRendering());
        assertTrue(clear.withCameraFluid(AtmosphereFrame.CameraFluid.WATER).fogCoversAllRendering());
        assertFalse(clear.withCameraFluid(AtmosphereFrame.CameraFluid.NONE).fogCoversAllRendering());
    }

    /** 2026-10-22: block light only takes what it adds to the sky's light, so a torch placed or held on sunlit ground does not darken it. */
    @Test
    void aTorchInFullDaylightDoesNotDarkenTheGround() {
        float[] floor = {0.04f, 0.04f, 0.04f}, full = {1, 1, 1};
        assertArrayEquals(full, SceneCompositeMath.skyLitShare(full, full, floor), 1e-6f, "the lightmap already full: all sky");
        assertArrayEquals(new float[3], SceneCompositeMath.skyLitShare(floor, full, floor), 1e-6f, "no sky light: all block");
        assertArrayEquals(new float[]{0.25f, 0.25f, 0.25f}, SceneCompositeMath.skyLitShare(new float[]{0.28f, 0.28f, 0.28f}, full, floor), 1e-6f);
        assertArrayEquals(new float[3], SceneCompositeMath.skyLitShare(floor, floor, floor), 1e-6f, "dark");
        float[] stone = {0.5f, 0.5f, 0.5f, 1}, sunlit = {1, 1, 0, 1};
        float[][] s = noonLit();
        s[SceneCompositeMath.LIGHT_CAMERA][3] = 1;
        s[SceneCompositeMath.HELD_ORIGIN][3] = 1;
        float[] empty = SceneCompositeMath.composite(surface(stone, UP, sunlit, -1, new float[]{15, 0, 1}, 0, false, 3), s, LIGHTMAP);
        float[] placed = SceneCompositeMath.composite(surface(stone, UP, sunlit, -1, new float[]{15, 14, 1}, 0, false, 3), s, LIGHTMAP);
        float[] held = SceneCompositeMath.composite(surface(stone, UP, sunlit, -1, new float[]{15, 0, 1}, 14, false, 3), s, LIGHTMAP);
        assertArrayEquals(rgb(empty), rgb(placed), 1e-5f, "placed");
        assertArrayEquals(rgb(empty), rgb(held), 1e-5f, "held");
        assertTrue(COMP.contains("share = skyLitShare(skyOnly, lightAll, dark);"));
    }

    /** B18: debug views 1..3 replace the colour; 4 is W6's and leaves the composite alone. */
    @Test
    void theLightDebugViewsShowTheLight() {
        float[][] s = noonLit();
        float[] stone = {0.5f, 0.5f, 0.5f, 1};
        float[] normal = SceneCompositeMath.composite(surface(stone, UP, new float[]{1, 1, 0, 1}, new float[]{12, 6, 1}), s, LIGHTMAP);
        for (int view = 1; view <= 4; view++) {
            s[SceneCompositeMath.LIGHT_MODE][0] = view;
            float[] out = SceneCompositeMath.composite(surface(stone, UP, new float[]{1, 1, 0, 1}, new float[]{12, 6, 1}), s, LIGHTMAP);
            if (view == 4) {
                assertArrayEquals(normal, out, 1e-6f);
                continue;
            }
            assertEquals(0f, out[3]);
            assertFinite(out, "view " + view);
            float[] unknown = SceneCompositeMath.composite(surface(stone, UP, new float[]{1, 1, 0, 1}, new float[]{0, 0, 0}), s, LIGHTMAP);
            assertArrayEquals(new float[]{SceneCompositeMath.DEBUG_UNKNOWN_RED, SceneCompositeMath.DEBUG_UNKNOWN_GREEN, SceneCompositeMath.DEBUG_UNKNOWN_BLUE},
                    rgb(unknown), 0f);
        }
        assertEquals(PostMath.displayToLinear(6 / 15f), SceneCompositeMath.debugView(2, true, 12, 6, new float[]{1, 1, 1}, 0)[0], 1e-6f);
        float[] shares = SceneCompositeMath.debugView(3, true, 15, 0, new float[]{1, 1, 1}, 0.5f);
        assertEquals(1f, shares[0] + shares[1] + shares[2], 1e-6f);
        assertEquals(PostProcessSettings.REFLECTIVE_DEBUG, 4);
    }

    /** Review round 1, Important 1: glass, ice and slime (and water) are a texel blended over what lies behind: no face shade divided out. */
    @Test
    void translucentPixelsAreNotUnshaded() {
        float[][] s = noonLit();
        float[] glass = {0.4f, 0.45f, 0.5f, 1};
        float[] light = {15, 0, 1};
        for (float kind : new float[]{-1, -3}) {
            float[] lt = {1, 1, kind, 1};
            float[] east = SceneCompositeMath.composite(surface(glass, EAST, lt, light), s, LIGHTMAP);
            float[] up = SceneCompositeMath.composite(surface(glass, UP, lt, light), s, LIGHTMAP);
            float[] down = SceneCompositeMath.composite(surface(glass, new float[]{0, -1, 0}, lt, light), s, LIGHTMAP);
            assertArrayEquals(up, east, 1e-6f, "an east window is lit like a flat pane");
            assertArrayEquals(up, down, 1e-6f, "a glass ceiling likewise");
            //At most the sun level (W5) and the warm tint (2026-10-28: about 1.35 in red at the default Sun Warmth): no 1/shade on top (east
            // 3.1x, down 4.7x)
            assertTrue(east[0] <= linear(glass)[0] * LightingMath.sunLevel(s[SceneCompositeMath.LIGHTING][3]) * 1.5f, "not brightened by 1/shade: " + Arrays.toString(east));
        }
        //An opaque east face at full weight is unshaded (the reference)
        float[] stone = SceneCompositeMath.composite(surface(glass, EAST, new float[]{1, 1, 0, 1}, light), s, LIGHTMAP);
        float[] flat = SceneCompositeMath.composite(surface(glass, UP, new float[]{1, 1, 0, 1}, light), s, LIGHTMAP);
        assertTrue(stone[0] > flat[0] * 2);
        assertTrue(COMP.contains("if (!plant && kind == 0.0) {"));
    }

    /**
     * Review round 1, minors: the haze takes the lifted sky light (water its own), the held light's normal falls back like pixelLight's,
     * water's light includes the AO, no haze for an empty cloud march.
     */
    @Test
    void theRoundOneMinorsHold() {
        float[][] s = scene(DEFAULT, frame(NOON), true, true, 1);
        //Surfaces 800 blocks out: without the border fog (BorderFogTest)
        s[SceneCompositeMath.BORDER_FOG] = new float[4];
        AtmosphereMath.Atmosphere a = SceneCompositeMath.atmosphere(s);
        float[] stone = {0.5f, 0.5f, 0.5f, 1};
        //Under an island (sky 4) in the sun: the lifted sky light sets the haze amount
        SceneCompositeMath.Pixel px = new SceneCompositeMath.Pixel(stone, false, dir(0.3f, -0.4f, -1), 800, false, UP, new float[]{1, 1, 0, 1},
                -1, new float[]{4, 0, 1}, 0, 0, false, new float[3]);
        float skyT = RelightMath.liftedSky(4, RelightMath.lift(1, s[SceneCompositeMath.LIGHT_FRAC][3]));
        assertTrue(skyT > 4);
        float[] out = SceneCompositeMath.composite(px, s, LIGHTMAP);
        float[][] noFog = scene(settings(c -> c.fogStrength = 0), frame(NOON), true, true, 1);
        noFog[SceneCompositeMath.BORDER_FOG] = new float[4];
        float[] unhazed = SceneCompositeMath.composite(px, noFog, LIGHTMAP);
        assertArrayEquals(AtmosphereMath.applyHaze(a, rgb(unhazed), px.dir(), 800, AtmosphereMath.hazeAmount(a, skyT / 15, false)), rgb(out), 1e-5f);
        assertTrue(COMP.contains("float hazeSky = water ? skyShare(sky) : clamp(skyT/15.0, 0.0, 1.0);"));
        assertTrue(COMP.contains("heldLightAt(lightFrac.xyz + world, dot(lightNormal, lightNormal) > 0.25 ? lightNormal : -world)"));
        assertTrue(COMP.contains("lightAll*perLight*ao*waterParams.y"));
        assertTrue(COMP.contains("if (!(m.y > 0.0)) {\n        return colour;"));
        //Water: the AO darkens the body's light too (W6: what a white texel receives). W10: only the sky light's ambient part of it
        float[][] lit = noonLit();
        lit[SceneCompositeMath.LIGHTING][0] = 1;
        float[] black = {0, 0, 0, 1};
        float[] open = SceneCompositeMath.composite(withAo(surface(black, UP, new float[]{1, 1, 30, 1}, new float[]{15, 0, 1}), 1), lit, LIGHTMAP);
        float[] occluded = SceneCompositeMath.composite(withAo(surface(black, UP, new float[]{1, 1, 30, 1}, new float[]{15, 0, 1}), 0.5f), lit, LIGHTMAP);
        float[] l = lit[SceneCompositeMath.LIGHTING];
        float ambientShare = LightingMath.sunShadingAt(0, 1, l[1], l[3]) / LightingMath.sunShadingAt(1, 1, l[1], l[3]);
        assertTrue(open[2] > 0);
        assertEquals(open[2] * (1 - 0.5f * ambientShare), occluded[2], 1e-5f);
    }

    private static SceneCompositeMath.Pixel withAo(SceneCompositeMath.Pixel p, float ao) {
        return new SceneCompositeMath.Pixel(p.colour(), p.sky(), p.dir(), p.dist(), p.fromLod(), p.normal(), p.lt(), p.plantSun(), p.light(),
                p.sideSky(), p.held(), p.cloud(), p.cloudMarch(), ao);
    }

    /**
     * W10: at an AO below 1 the composite darkens only the sky light's ambient part and the lightmap floor: the direct sun's part, torch
     * light and the sky are unchanged, shade is darkened by the whole AO, strength 0 changes nothing.
     */
    @Test
    void theAmbientOcclusionLeavesDirectSunTorchLightAndTheSkyAlone() {
        float[][] s = noonLit();
        assertEquals(1f, s[SceneCompositeMath.LIGHTING][0], "the default strength reaches slot 10 .x");
        float[] stone = {0.5f, 0.5f, 0.5f, OPAQUE};
        float[] sunlit = {1, 1, 0, 1}, shaded = {1, 0, 0, 1};//the same sun visibility: the same Sky Light Ambient lift
        float[] daylight = {15, 0, 1};
        float ao = 0.4f;
        float[] sunOpen = rgb(SceneCompositeMath.composite(withAo(surface(stone, UP, sunlit, daylight), 1), s, LIGHTMAP));
        float[] sunAo = rgb(SceneCompositeMath.composite(withAo(surface(stone, UP, sunlit, daylight), ao), s, LIGHTMAP));
        float[] shadeOpen = rgb(SceneCompositeMath.composite(withAo(surface(stone, UP, shaded, daylight), 1), s, LIGHTMAP));
        float[] shadeAo = rgb(SceneCompositeMath.composite(withAo(surface(stone, UP, shaded, daylight), ao), s, LIGHTMAP));
        float[] l = s[SceneCompositeMath.LIGHTING];
        float ambientShare = LightingMath.sunShadingAt(0, 1, l[1], l[3]) / LightingMath.sunShadingAt(1, 1, l[1], l[3]);
        assertTrue(ambientShare > 0.2f && ambientShare < 0.5f);
        for (int i = 0; i < 3; i++) {
            //In full sun only the ambient share of the sky-lit light is occluded: the direct sun's part stays (the light colour
            // tints both alike, so it cancels in the ratio)
            assertEquals(sunOpen[i] * (1 - (1 - ao) * ambientShare), sunAo[i], 1e-5f * Math.max(1, sunOpen[i]));
            //Shade is all ambient: it takes the whole AO
            assertEquals(shadeOpen[i] * ao, shadeAo[i], 1e-5f);
            assertTrue(sunAo[i] < sunOpen[i] && sunAo[i] > sunOpen[i] * 0.6f, "full sun keeps its direct part");
        }
        //Torch light (no sky light): only the small lightmap floor share goes
        float[] torch = {0, 14, 1}, dark = {1, 0, 0, 0};//no sky light, no sun: no Sky Light Ambient lift
        float[] torchOpen = rgb(SceneCompositeMath.composite(withAo(surface(stone, UP, dark, torch), 1), s, LIGHTMAP));
        float[] torchAo = rgb(SceneCompositeMath.composite(withAo(surface(stone, UP, dark, torch), ao), s, LIGHTMAP));
        for (int i = 0; i < 3; i++) assertTrue(torchAo[i] <= torchOpen[i] && torchAo[i] > torchOpen[i] * 0.97f, "torch " + i);
        //The sky is never darkened
        assertArrayEquals(SceneCompositeMath.composite(withAo(skyPixel(COLOURS[0], UP), 1), s, LIGHTMAP),
                SceneCompositeMath.composite(withAo(skyPixel(COLOURS[0], UP), ao), s, LIGHTMAP), 0f);
        //Strength 0: nothing changes
        s[SceneCompositeMath.LIGHTING][0] = 0;
        assertArrayEquals(shadeOpen, rgb(SceneCompositeMath.composite(withAo(surface(stone, UP, shaded, daylight), ao), s, LIGHTMAP)), 0f);
        assertTrue(COMP.contains("vec3 occludable = share*sunShadingAt(0.0, skyLight, sunWeight, lighting.w)*faceUnshade(shade, sunWeight)*tint*se"));
    }

    /** Tuning round 3 (refs/S4, S3): the sky-lit light's overcast-day and night exposures; block light and the off cases untouched. */
    @Test
    void theSkyLitLightHasOvercastAndNightExposures() {
        float full = 1 + (SceneCompositeMath.OVERCAST_EXPOSURE - 1) * AtmosphereUniforms.RAIN_GREY;
        assertEquals(1f, SceneCompositeMath.skyWeatherExposure(0, 1, 1, 1), 1e-6f, "clear day");
        assertEquals(full, SceneCompositeMath.skyWeatherExposure(AtmosphereUniforms.RAIN_GREY, 1, 1, 1), 1e-5f, "full rain at noon");
        assertTrue(full > 2.8f && full < 3.4f, "refs/S4: about 3x vanilla");
        assertEquals(1f, SceneCompositeMath.skyWeatherExposure(AtmosphereUniforms.RAIN_GREY, 1, 0, 1), 1e-6f, "underground");
        assertEquals(SceneCompositeMath.NIGHT_SKY_EXPOSURE, SceneCompositeMath.skyWeatherExposure(AtmosphereUniforms.RAIN_GREY, 0, 0, 1), 1e-6f,
                "night: no overcast lift");
        float[] stone = {0.5f, 0.5f, 0.5f, 1};
        float[] shaded = {1, 0, 0, 0};
        float[][] rain = scene(settings(SceneCompositeTest::plainColours), frame(NOON, 1, false, FOG, new Matrix4f()), true, true, 1);
        AtmosphereMath.Atmosphere a = SceneCompositeMath.atmosphere(rain);
        assertEquals(AtmosphereUniforms.RAIN_GREY, a.sunLight()[3], 1e-6f);
        assertEquals(1f, rain[SceneCompositeMath.LIGHTING][2], "the pass runs in rain");
        float[] wet = SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{15, 0, 1}), rain, LIGHTMAP);
        for (int i = 0; i < 3; i++) assertEquals(linear(stone)[i] * full, wet[i], 1e-4f, "soft overcast light, no sun shading");
        //Block light keeps its exposure; underground the overcast lift is gone
        float[][] clear = scene(settings(SceneCompositeTest::plainColours), frame(NOON), true, true, 1);
        assertArrayEquals(SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{0, 14, 1}), clear, LIGHTMAP),
                SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{0, 14, 1}), rain, LIGHTMAP), 1e-6f);
        float[][] rainCave = scene(settings(SceneCompositeTest::plainColours), frame(NOON, 1, false, FOG, new Matrix4f()), true, true, 0);
        float[] caveMouth = SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{15, 0, 1}), rainCave, LIGHTMAP);
        assertTrue(caveMouth[0] < wet[0] / 2);
        //The post-processing settings that disable the lighting: no exposure
        float[][] off = scene(settings(c -> {
            c.fogStrength = 0;
            c.sunShading = 0;
            c.aoStrength = 0;
            c.waterDepth = 0;
            c.waterReflections = 0;
        }), frame(NOON, 1, false, FOG, new Matrix4f()), true, true, 1);
        assertArrayEquals(linear(stone), rgb(SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{15, 0, 1}), off, LIGHTMAP)), 1e-6f);
        //Night: moonlit terrain at NIGHT_SKY_EXPOSURE, the village's block light at full
        float[][] night = scene(settings(SceneCompositeTest::plainColours), frame(MIDNIGHT), true, true, 1);
        float[] moonlit = SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{15, 0, 1}), night, LIGHTMAP);
        for (int i = 0; i < 3; i++) assertEquals(linear(stone)[i] * SceneCompositeMath.NIGHT_SKY_EXPOSURE, moonlit[i], 1e-5f);
        assertArrayEquals(linear(stone), rgb(SceneCompositeMath.composite(surface(stone, UP, shaded, new float[]{0, 14, 1}), night, LIGHTMAP)), 1e-5f);
        assertTrue(COMP.contains("se *= skyWeatherExposure(lookParams.z, waterParams.z, waterParams.w, look);"));
    }

    /** Tuning round 3 (refs/S13): with the camera inside vanilla's cloud layer the far clouds start FAR_CLOUD_NEAR out. */
    @Test
    void theFarCloudsDoNotVeilTheViewFromInsideTheLayer() {
        assertEquals(SceneCompositeMath.FAR_CLOUD_NEAR, SceneCompositeMath.farCloudNear(new float[]{-0.7f, 4, 512, 1}), "inside");
        assertEquals(SceneCompositeMath.FAR_CLOUD_NEAR, SceneCompositeMath.farCloudNear(new float[]{0.5f, 4, 512, 1}), "just below");
        assertEquals(0f, SceneCompositeMath.farCloudNear(new float[]{2.3f, 4, 512, 1}), "below the layer: nothing in front looking down anyway");
        assertEquals(0f, SceneCompositeMath.farCloudNear(new float[]{-10, 4, 512, 1}), "above: the clouds just below stay");
        assertEquals(0f, SceneCompositeMath.farCloudNear(new float[]{60, 4, 512, 1}), "far below");
        assertTrue(COMP.contains("vec3 m = cloudMarch(dir*near, dir, maxDist - near, cloudLightDir.xyz);"));
        assertTrue(COMP.contains("m.z += near;"));
    }

    // ---- final fix R1, R2: the look weight, the camera's fluid, every setting at 0 ----

    /** Every strength setting at 0 and every switch off; exposure, contrast and saturation at their neutral 100. */
    /** The exposure tests' settings: no fog and none of the 2026-10-28 colours (torch light colour, moonlight), which move the hue. */
    private static void plainColours(VisualsConfig c) {
        c.fogStrength = 0;
        c.blockLightColour = 0;
        c.lightColour = 0;
    }

    private static final Consumer<VisualsConfig> ALL_ZERO = c -> {
        c.toneStrength = 0;
        c.gradeStrength = 0;
        c.bloomStrength = 0;
        c.vignetteStrength = 0;
        c.saturation = 100;
        c.fogStrength = 0;
        c.sunGlow = 0;
        c.sunsetLight = 0;
        c.lightShafts = 0;
        c.moonlight = 0;
        c.aoStrength = 0;
        c.sunShading = 0;
        c.waterDepth = 0;
        c.waterReflections = 0;
        c.waterRipples = 0;
        c.waterRippleSpeed = 0;
        c.skyLightAmbient = 0;
        c.lightColour = 0;
        c.sunWarmth = 0;
        c.blockLightColour = 0;
        c.blockGlow = 0;
        c.oreGlow = 0;
        c.farClouds = false;
        c.sunShadows = false;
        c.vanillaShadows = false;
        c.heldLight = false;
        c.taa = false;
        c.antiAliasing = 0;
    };
    private static final PostProcessSettings ZERO = settings(ALL_ZERO);

    private record Case(String name, AtmosphereFrame frame, boolean hasSky) {
    }

    private static List<Case> cases() {
        return List.of(new Case("night", frame(MIDNIGHT), true), new Case("day", frame(NOON), true),
                new Case("rain", frame(NOON, 1, false, FOG, new Matrix4f()), true), new Case("under water", underWater(NOON, WATER_FOG), true),
                new Case("nether", frame(MIDNIGHT, 0, false, NETHER_FOG, new Matrix4f(), null), false));
    }

    /**
     * Final fix R1 (B20), end to end: with every setting at 0 the chain LightingChoice -> LightingUniforms -> AtmosphereUniforms ->
     * SceneCompositeMath -> PostMath gives vanilla's pixel back (night, day, rain, under water, the Nether), whatever the light, the
     * per-pixel light on or off, vanilla or LOD pixels, sky or geometry.
     */
    @Test
    void withEverySettingAtZeroTheImageIsVanillas() {
        for (Case k : cases()) {
            AtmosphereUniforms.Values atmosphere = AtmosphereUniforms.compute(ZERO, k.frame(), SERIAL, k.hasSky(), false);
            assertEquals(AtmosphereMath.ATMOSPHERE_NONE, atmosphere.mode(), k.name() + ": W4 draws nothing, vanilla's sky is kept");
            PostUniforms.Values post = PostUniforms.compute(ZERO, 1920, 1080, k.frame().timeOfDay(), k.hasSky(), 7, atmosphere.shaftLight());
            for (boolean volume : new boolean[]{false, true}) {
                for (float skyExposure : new float[]{0, 1}) {
                    float[][] s = scene(ZERO, k.frame(), k.hasSky(), volume, skyExposure);
                    assertEquals(0f, s[SceneCompositeMath.LIGHTING][2], k.name() + ": no lighting pass");
                    assertEquals(0f, s[SceneCompositeMath.LOOK][0], k.name() + ": look weight 0");
                    for (float[] c : COLOURS) {
                        List<SceneCompositeMath.Pixel> pixels = new java.util.ArrayList<>();
                        pixels.add(skyPixel(c, dir(0.3f, 0.5f, -1)));
                        for (float[] light : new float[][]{{15, 0, 1}, {0, 14, 1}, {7, 7, 1}, {4, 12, 1}, {0, 0, 0}}) {
                            for (boolean lod : new boolean[]{false, true}) {
                                for (float dist : new float[]{5, 300, 5000}) {
                                    pixels.add(surface(c, NORTH, new float[]{0.3f, 0.2f, 7, 0.1f}, 0.4f, light, 15, lod, dist));
                                }
                            }
                        }
                        pixels.add(new SceneCompositeMath.Pixel(c, false, dir(0.2f, 0.3f, -1), 300, false, NONE, NAN4, -1, new float[]{15, 0, 1},
                                1, 0, true, new float[3]));
                        for (SceneCompositeMath.Pixel px : pixels) {
                            float[] scene = SceneCompositeMath.composite(px, s, LIGHTMAP);
                            assertArrayEquals(linear(c), rgb(scene), 1e-6f, k.name() + " " + Arrays.toString(px.light()));
                            float[] display = PostMath.finalColour(post, rgb(scene), new float[]{9, 9, 9}, 5, 100.5f, 700.5f);
                            assertArrayEquals(rgb(c), display, 2e-4f, k.name() + ": the display colour is vanilla's");
                        }
                    }
                }
            }
        }
        //Moonlight 0 leaves vanilla's night lightmap (the moonlight tooltip)
        for (float v : new float[]{0.24f, 0.5f, 1}) assertEquals(v, Moonlight.skyFactor(v, ZERO.moonlight(), MIDNIGHT, 0, 0, true), 0f);
        //Any drawing atmosphere setting brings W4's sky back
        List<Consumer<VisualsConfig>> each = List.of(c -> c.fogStrength = 1, c -> c.sunGlow = 1, c -> c.sunsetLight = 1, c -> c.lightShafts = 1,
                c -> c.moonlight = 1, c -> c.farClouds = true);
        for (Consumer<VisualsConfig> one : each) {
            assertEquals(AtmosphereMath.ATMOSPHERE_SKY, AtmosphereUniforms.compute(settings(ALL_ZERO.andThen(one)), frame(NOON), SERIAL, true, false).mode());
        }
    }

    /**
     * Final fix R1: moving the AO alone from 0 to 100 changes torch-lit and rain-lit pixels only by the AO's own factor: with no
     * occlusion (aoAt 1) not at all, though it starts the lighting pass when everything else is 0.
     */
    @Test
    void theAmbientOcclusionAloneChangesTorchAndRainLightOnlyByItsFactor() {
        List<Consumer<VisualsConfig>> bases = List.of(ALL_ZERO, c -> {
        });
        float[] stone = {0.5f, 0.5f, 0.5f, OPAQUE};
        for (Consumer<VisualsConfig> base : bases) {
            PostProcessSettings off = settings(base.andThen(c -> c.aoStrength = 0)), on = settings(base.andThen(c -> c.aoStrength = 100));
            for (AtmosphereFrame f : new AtmosphereFrame[]{frame(NOON), frame(NOON, 1, false, FOG, new Matrix4f()), frame(MIDNIGHT)}) {
                for (float skyExposure : new float[]{0, 1}) {
                    float[][] a = scene(off, f, true, true, skyExposure), b = scene(on, f, true, true, skyExposure);
                    assertEquals(1f, b[SceneCompositeMath.LIGHTING][2], "the AO runs the lighting pass");
                    for (float[] light : new float[][]{{0, 14, 1}, {15, 0, 1}, {15, 10, 1}, {8, 13, 1}}) {
                        for (float[] lt : new float[][]{{1, 1, 0, 1}, {1, 0, 0, 0}}) {
                            SceneCompositeMath.Pixel px = surface(stone, UP, lt, -1, light, 0, false, 20);
                            float[] open0 = rgb(SceneCompositeMath.composite(withAo(px, 1), a, LIGHTMAP));
                            float[] open1 = rgb(SceneCompositeMath.composite(withAo(px, 1), b, LIGHTMAP));
                            assertArrayEquals(open0, open1, 1e-6f * Math.max(1, open0[0]), "no occlusion: the same pixel " + Arrays.toString(light));
                            float[] occluded = rgb(SceneCompositeMath.composite(withAo(px, 0.5f), b, LIGHTMAP));
                            float occlusion = AmbientOcclusion.strengthApplied(0.5f, 1);
                            for (int i = 0; i < 3; i++) {
                                assertTrue(occluded[i] <= open0[i] * (1 + 1e-6f) && occluded[i] >= open0[i] * occlusion * (1 - 1e-6f),
                                        "only the AO's factor " + Arrays.toString(light) + " " + Arrays.toString(occluded) + " " + Arrays.toString(open0));
                            }
                            if (light[0] == 0 && lt[3] == 0) {
                                //torch light with no sun to lift its sky light (Sky Light Ambient)
                                for (int i = 0; i < 3; i++) assertTrue(occluded[i] > open0[i] * 0.97f, "torch light keeps all but the floor");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Final fix R1: every exposure the composite adds is mix(1, E, look), look = the sun-shading setting (slot 29 .x); they no longer
     * depend on whether the lighting pass ran, and the sky-light ones apply only where the light is known.
     */
    @Test
    void theExposuresFollowTheLookWeightAndNotTheLightingPass() {
        assertEquals(1f, scene(DEFAULT, frame(NOON), true, true, 1)[SceneCompositeMath.LOOK][0], 1e-6f);
        assertEquals(0.5f, scene(settings(c -> c.sunShading = 50), frame(NOON), true, true, 1)[SceneCompositeMath.LOOK][0], 1e-6f);
        assertEquals(0f, AtmosphereUniforms.compute(settings(c -> c.enabled = false), frame(NOON), SERIAL, true, false).look()[0], "off: 0");
        //The mirror's functions at look 0, 0.5 and 1
        for (float look : new float[]{0, 0.5f, 1}) {
            assertEquals(SceneCompositeMath.mix(1, SceneCompositeMath.UNDERGROUND_SKY_EXPOSURE, look), SceneCompositeMath.skyExposure(1, 0, look), 1e-6f);
            assertEquals(SceneCompositeMath.mix(1, SceneCompositeMath.DAY_BLOCK_EXPOSURE, look), SceneCompositeMath.blockExposure(1, look), 1e-6f);
            assertEquals(SceneCompositeMath.mix(1, SceneCompositeMath.NIGHT_SKY_EXPOSURE, look), SceneCompositeMath.skyWeatherExposure(0.8f, 0, 0, look), 1e-6f);
            assertEquals(SceneCompositeMath.mix(1, SceneCompositeMath.OVERCAST_EXPOSURE, look), SceneCompositeMath.skyWeatherExposure(1, 1, 1, look), 1e-6f);
            assertEquals(SceneCompositeMath.mix(1, SceneCompositeMath.UNDERWATER_EXPOSURE, look), SceneCompositeMath.underwaterExposure(new float[]{look, 1, 0, 0}), 1e-6f);
            assertEquals(1f, SceneCompositeMath.underwaterExposure(new float[]{look, 0, 0, 0}), 0f);
        }
        float[] stone = {0.5f, 0.5f, 0.5f, OPAQUE};
        float[] torch = {0, 14, 1}, open = {15, 0, 1}, unknown = {0, 0, 0};
        //The Nether at sun shading 50: half the no-sky exposure (the lighting pass never runs there)
        AtmosphereFrame nether = frame(MIDNIGHT, 0, false, NETHER_FOG, new Matrix4f(), null);
        float[][] half = scene(settings(c -> {
            c.sunShading = 50;
            c.fogStrength = 0;
            c.blockLightColour = 0;
        }), nether, false, true, 0);
        assertEquals(0f, half[SceneCompositeMath.LIGHTING][2]);
        float[] lit = SceneCompositeMath.composite(surface(stone, NORTH, NAN4, -1, torch, 0, false, 10), half, LIGHTMAP);
        for (int i = 0; i < 3; i++) assertEquals(linear(stone)[i] * (1 + (SceneCompositeMath.NO_SKY_EXPOSURE - 1) * 0.5f), lit[i], 1e-5f);
        //Under water by day the lighting pass does not run: torch light still takes the day's block exposure, the view the underwater one
        float[][] water = scene(settings(c -> c.blockLightColour = 0), underWater(NOON, WATER_FOG), true, true, 1);
        assertEquals(0f, water[SceneCompositeMath.LIGHTING][2]);
        float[] torchLit = SceneCompositeMath.composite(surface(stone, UP, NAN4, -1, torch, 0, false, 10), water, LIGHTMAP);
        for (int i = 0; i < 3; i++) {
            assertEquals(linear(stone)[i] * SceneCompositeMath.DAY_BLOCK_EXPOSURE * SceneCompositeMath.UNDERWATER_EXPOSURE, torchLit[i], 1e-5f);
        }
        //At night the known sky-lit light takes the night exposure; unknown light (a section not loaded yet, the volume off) none
        float[][] night = scene(settings(SceneCompositeTest::plainColours), frame(MIDNIGHT), true, true, 1);
        float[] known = SceneCompositeMath.composite(surface(stone, UP, new float[]{1, 0, 0, 0}, -1, open, 0, false, 10), night, LIGHTMAP);
        float[] notKnown = SceneCompositeMath.composite(surface(stone, UP, new float[]{1, 0, 0, 0}, -1, unknown, 0, false, 10), night, LIGHTMAP);
        for (int i = 0; i < 3; i++) {
            assertEquals(linear(stone)[i] * SceneCompositeMath.NIGHT_SKY_EXPOSURE, known[i], 1e-5f);
            assertEquals(linear(stone)[i], notKnown[i], 1e-6f);
        }
        assertTrue(COMP.contains("float look = lookWeight();"));
        assertTrue(COMP.contains("bool exposed = known || lightCamera.w < 0.5;"));
        assertTrue(COMP.contains("float se = exposed ? skyExposure(waterParams.z, waterParams.w, look) : 1.0;"));
        assertFalse(COMP.contains("passRan ? skyExposure") || COMP.contains("passRan ? blockExposure") || COMP.contains("passRan && atmosphereSky(a)"));
    }

    /**
     * Final fix follow-up 1: with the light volume off (lightCamera.w 0) "unknown" is not "not yet loaded": vanilla pixels take the
     * sky-light exposures on their lightmap share (all sky-lit), so they are lit like their LOD neighbours (always known) in rain and at
     * night, no lasting vanilla/LOD seam. Inside an active volume an unloaded section stays unexposed (the accepted transient).
     */
    @Test
    void withTheLightVolumeOffVanillaAndLodNeighboursAreLitAlike() {
        float[] stone = {0.5f, 0.5f, 0.5f, OPAQUE};
        float[] shaded = {1, 0, 0, 0};
        AtmosphereFrame rain = frame(NOON, 1, false, FOG, new Matrix4f()), night = frame(MIDNIGHT);
        for (AtmosphereFrame f : new AtmosphereFrame[]{rain, night}) {
            float[][] s = scene(settings(SceneCompositeTest::plainColours), f, true, false, 1);
            assertEquals(0f, s[SceneCompositeMath.LIGHT_CAMERA][3], "the volume is off");
            float[] vanilla = SceneCompositeMath.composite(surface(stone, UP, shaded, -1, new float[]{0, 0, 0}, 0, false, 40), s, LIGHTMAP);
            float[] lod = SceneCompositeMath.composite(surface(stone, UP, shaded, -1, new float[]{15, 0, 1}, 0, true, 40), s, LIGHTMAP);
            assertArrayEquals(rgb(lod), rgb(vanilla), 1e-5f, "vanilla and LOD alike");
            float e = f == rain ? 1 + (SceneCompositeMath.OVERCAST_EXPOSURE - 1) * AtmosphereUniforms.RAIN_GREY : SceneCompositeMath.NIGHT_SKY_EXPOSURE;
            for (int i = 0; i < 3; i++) assertEquals(linear(stone)[i] * e, vanilla[i], 1e-4f, "the sky-light exposure");
            //Inside an active volume a section not loaded yet stays unexposed
            float[][] on = scene(settings(SceneCompositeTest::plainColours), f, true, true, 1);
            assertArrayEquals(linear(stone), rgb(SceneCompositeMath.composite(surface(stone, UP, shaded, -1, new float[]{0, 0, 0}, 0, false, 40), on, LIGHTMAP)), 1e-6f);
        }
    }

    /**
     * Final fix R2: the camera under water is the frame's camera fluid (AtmosphereFrame, slot 29 .y), not the fog colour: swamp and
     * mangrove water (brown-green fog) take the underwater exposure, a modded blue fog in the open air does not.
     */
    @Test
    void theCameraUnderWaterIsTheFramesFluidNotTheFogColour() {
        float[] swamp = {0.24f, 0.25f, 0.14f}, modBlue = {0.05f, 0.2f, 0.6f};
        float[][] inSwamp = scene(DEFAULT, underWater(NOON, swamp), true, true, 1);
        assertEquals(1f, inSwamp[SceneCompositeMath.LOOK][1]);
        assertEquals(SceneCompositeMath.UNDERWATER_EXPOSURE, SceneCompositeMath.underwaterExposure(inSwamp[SceneCompositeMath.LOOK]), 1e-6f);
        float[][] blueFog = scene(DEFAULT, frame(NOON, 0, true, modBlue, new Matrix4f(), PLAINS_SKY, AtmosphereFrame.CameraFluid.NONE), true, true, 1);
        assertEquals(0f, blueFog[SceneCompositeMath.LOOK][1]);
        assertEquals(1f, SceneCompositeMath.underwaterExposure(blueFog[SceneCompositeMath.LOOK]), 0f);
        //Sky pixels too (vanilla's fog colour under W4's FOG mode)
        float[] sky = SceneCompositeMath.composite(skyPixel(new float[]{1, 1, 1, 1}, UP), inSwamp, LIGHTMAP);
        assertEquals(PostMath.displayToLinear(swamp[0]) * SceneCompositeMath.UNDERWATER_EXPOSURE, sky[0], 1e-6f);
        assertEquals(PostMath.displayToLinear(modBlue[0]),
                SceneCompositeMath.composite(skyPixel(new float[]{1, 1, 1, 1}, UP), blueFog, LIGHTMAP)[0], 1e-6f);
        //The frame carries the fluid through its other copies
        AtmosphereFrame f = underWater(NOON, swamp);
        assertTrue(f.cameraInWater());
        assertTrue(f.withSky(null, 64).withRipple(new float[8]).withClouds(null).cameraInWater());
        assertFalse(frame(NOON).cameraInWater());
        assertFalse(frame(NOON, 0, true, swamp, new Matrix4f(), PLAINS_SKY, AtmosphereFrame.CameraFluid.LAVA).cameraInWater());
        assertFalse(COMP.contains("WATER_FOG_BLUE"), "no fog-colour test any more");
    }

    // ---- the mirror ----

    private static final Set<String> JAVA_ONLY = Set.of("SLOTS", "LIGHTING", "WATER", "CLOUD_SHAPE", "CLOUD_LIT", "CLOUD_AMBIENT", "CLOUD_LIGHT_DIR",
            "LIGHT_CAMERA", "LIGHT_FRAC", "LIGHT_MODE", "LIGHT_SUN", "LIGHT_SHADE", "HELD_ORIGIN", "VANILLA_SKY", "VANILLA_FOG", "STAR_GATE", "LOOK", "BORDER_FOG", "BLOCK_LIGHT", "MOON_LIGHT", "GLOW");

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        int found = 0;
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(COMP);
        while (m.find()) {
            Field f = SceneCompositeMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 20, "constants found: " + found);
        for (Field f : SceneCompositeMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class) || JAVA_ONLY.contains(f.getName())) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(COMP).find(), f.getName() + " missing in the shader");
        }
        //It assembles the other packages' exports and reimplements none of them
        for (String call : new String[]{"vanillaToScene(", "lightingAt(", "sunShadingAt(", "shadeLevel(lighting.w)", "sunLevel(lighting.w)", "sunShare(", "isPlant(", "isEmissive(", "snapNormal(depthNormal(",
                "lodFaceNormal(", "waterColour(", "reflectionMark(", "sunDisc(", "skyColour(", "sunGlow(", "vanillaStars(", "hazeAmount(", "applyHaze(",
                "cloudOver(", "cloudMarch(", "pixelLight(", "relightGain(", "relightCapped(", "liftedSky(", "waterSky(", "sideSky(", "scenePositionFrom("}) {
            assertTrue(COMP.contains(call), call);
        }
        for (String own : new String[]{"vec3 skyColour(", "vec3 sunGlow(", "void haze(", "float sunShading(", "float sunShadingAt(", "vec3 waterColour(", "vec3 sunDisc(",
                "vec4 lightingAt(", "vec3 pixelLight("}) {
            assertFalse(COMP.contains(own), own);
        }
        assertEquals(LightingMath.PLANT_ALPHA_CODE, 254);
        assertEquals(RelightMath.MAX_GAIN, 4f);
    }

    private static int count(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }
}
