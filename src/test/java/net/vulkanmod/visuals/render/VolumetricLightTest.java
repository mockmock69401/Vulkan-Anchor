package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W8: when the volumetric shafts run, their colour, the shader's uniforms and bindings, the march and the blur (VolumetricMath). */
class VolumetricLightTest {
    private static final long SERIAL = 11;
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f, true);
    /** Sun heights: cos(2 pi timeOfDay). */
    private static final float NOON = 0, SUNSET = (float) (Math.acos(0.1) / (2 * Math.PI)), MIDNIGHT = 0.5f;

    private static PostProcessSettings settings(int lightShafts) {
        VisualsConfig c = new VisualsConfig();
        c.lightShafts = lightShafts;
        return PostProcessSettings.from(c);
    }

    private static AtmosphereFrame frame(float timeOfDay, float rain, float thunder) {
        return new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(), 70, 63,
                0.6f, 0.7f, 0.9f, false, false, timeOfDay, rain, thunder, null, null, 256, null, null);
    }

    private static LightingUniforms.Values lighting(PostProcessSettings s, AtmosphereFrame f, boolean hasSky) {
        return LightingUniforms.compute(s, LightingChoice.of(SERIAL, s, true, true, false, hasSky), f, SERIAL, false, 1, true);
    }

    private static ShadowUniforms.Values shadowOn(boolean far) {
        return new ShadowUniforms.Values(true, far, new Matrix4f(), new Matrix4f(), new float[4], new Matrix4f(), new Matrix4f(), 0, 0, false,
                new Matrix4f(), new Matrix4f(), new float[3]);
    }

    private static VolumetricLight.Values at(float timeOfDay, float rain, float thunder) {
        PostProcessSettings s = settings(100);
        AtmosphereFrame f = frame(timeOfDay, rain, thunder);
        return VolumetricLight.compute(s, f, lighting(s, frame(NOON, 0, 0), true), shadowOn(true), 1, 256, 8192);
    }

    private static void assertOff(VolumetricLight.Values v, String why) {
        assertFalse(v.on(), why);
        assertArrayEquals(new float[3], v.shaftLight(), 0f, why);
    }

    // ---- 1. when it runs ----

    @Test
    void itRunsWithAFrameSkyASunAboveTheHorizonTheSettingAndACascade() {
        VolumetricLight.Values v = at(SUNSET, 0, 0);
        assertTrue(v.on());
        float[] c = v.shaftLight();
        assertTrue(c[0] > 0 && c[1] > 0 && c[2] > 0);
        //With only the near cascade the ray stays inside it
        PostProcessSettings s = settings(100);
        VolumetricLight.Values near = VolumetricLight.compute(s, frame(NOON, 0, 0), lighting(s, frame(NOON, 0, 0), true), shadowOn(false), 1, 256, 8192);
        assertTrue(near.on());
        assertEquals(ShadowMath.NEAR_RADIUS, near.maxDistance(), 0f);
        assertEquals(ShadowMath.FAR_RADIUS, v.maxDistance(), 0f);
        //No farther than what is drawn
        assertEquals(300f, VolumetricLight.compute(s, frame(NOON, 0, 0), lighting(s, frame(NOON, 0, 0), true), shadowOn(true), 1, 300, 0).maxDistance(), 0f);
    }

    @Test
    void itIsOffWithoutFrameSkySunSettingOrCascade() {
        PostProcessSettings s = settings(100);
        AtmosphereFrame noon = frame(NOON, 0, 0);
        LightingUniforms.Values lit = lighting(s, noon, true);
        assertTrue(lit.on());
        assertOff(VolumetricLight.compute(s, null, lit, shadowOn(true), 1, 256, 8192), "null frame");
        //No sky light: the lighting pass does not run (LightingChoice), so neither do the cascades nor the shafts (B17)
        LightingUniforms.Values noSky = lighting(s, noon, false);
        assertFalse(noSky.on());
        assertOff(VolumetricLight.compute(s, noon, noSky, shadowOn(true), 1, 256, 8192), "no sky");
        assertOff(VolumetricLight.compute(s, noon, noSky, ShadowUniforms.compute(noSky, noon, null, null, null), 1, 256, 8192), "no sky, clean shadow");
        assertOff(VolumetricLight.compute(s, frame(MIDNIGHT, 0, 0), lit, shadowOn(true), 1, 256, 8192), "midnight");
        assertOff(VolumetricLight.compute(s, frame(0.3f, 0, 0), lit, shadowOn(true), 1, 256, 8192), "sun below the horizon");
        //Setting 0 (B20)
        PostProcessSettings zero = settings(0);
        assertOff(VolumetricLight.compute(zero, noon, lighting(zero, noon, true), shadowOn(true), 1, 256, 8192), "setting 0");
        assertOff(VolumetricLight.compute(s, noon, lit, ShadowUniforms.off(), 1, 256, 8192), "no cascade");
        assertOff(VolumetricLight.compute(s, noon, lit, null, 1, 256, 8192), "null shadow");
        assertOff(VolumetricLight.compute(s, noon, lit, shadowOn(true), 0, 256, 8192), "sky exposure 0");
        assertOff(VolumetricLight.compute(s, noon, LightingUniforms.off(), shadowOn(true), 1, 256, 8192), "lighting off");
        assertOff(VolumetricLight.off(), "off()");
    }

    @Test
    void theColourDoesNotIncludeTheSetting() {
        PostProcessSettings full = settings(100), half = settings(50);
        AtmosphereFrame f = frame(SUNSET, 0, 0);
        float[] a = VolumetricLight.compute(full, f, lighting(full, frame(NOON, 0, 0), true), shadowOn(true), 1, 256, 8192).shaftLight();
        float[] b = VolumetricLight.compute(half, f, lighting(half, frame(NOON, 0, 0), true), shadowOn(true), 1, 256, 8192).shaftLight();
        assertArrayEquals(a, b, 0f);
    }

    // ---- 2 and 5. colour and amount over the sun's height and the weather ----

    private static float warmth(float[] c) {
        return c[0] / c[2];
    }

    private static float luma(float[] c) {
        return PostMath.luminance(c[0], c[1], c[2]);
    }

    @Test
    void theColourIsWarmerTheLowerTheSunAndNeverNegative() {
        float lastWarmth = Float.MAX_VALUE;
        for (int i = 1; i <= 200; i++) {
            float h = i / 200f;
            float[] c = VolumetricLight.shaftColour(h, 0, 0);
            for (float f : c) assertTrue(Float.isFinite(f) && f >= 0, "h " + h);
            assertTrue(luma(c) > 0, "h " + h);
            float w = warmth(c);
            assertTrue(w <= lastWarmth + 1e-6f, "warmth grows with the sun at h " + h);
            assertTrue(c[0] >= c[1] && c[1] >= c[2], "sunlight is never bluer than white, h " + h);
            lastWarmth = w;
        }
        assertTrue(warmth(VolumetricLight.shaftColour(0.1f, 0, 0)) > 1.5f * warmth(VolumetricLight.shaftColour(1, 0, 0)));
        //Through compute: sunset is warmer than noon
        assertTrue(warmth(at(SUNSET, 0, 0).shaftLight()) > warmth(at(NOON, 0, 0).shaftLight()));
        //Below the horizon and with bad inputs: zero, never negative or NaN
        for (float h : new float[]{0, -0.3f, -1, Float.NaN}) assertArrayEquals(new float[3], VolumetricLight.shaftColour(h, 0, 0), 0f);
        for (float f : VolumetricLight.shaftColour(0.5f, Float.NaN, 7)) assertTrue(Float.isFinite(f) && f >= 0);
    }

    @Test
    void rainAndThunderReduceTheAmountAtEverySunHeight() {
        for (int i = 1; i <= 100; i++) {
            float h = i / 100f;
            float clear = luma(VolumetricLight.shaftColour(h, 0, 0));
            float drizzle = luma(VolumetricLight.shaftColour(h, 0.3f, 0));
            float rain = luma(VolumetricLight.shaftColour(h, 1, 0));
            float storm = luma(VolumetricLight.shaftColour(h, 1, 1));
            assertTrue(clear > drizzle && drizzle > rain && rain > storm, "h " + h);
            assertTrue(rain <= 0.25f * clear, "rain leaves only weak shafts, h " + h);
            assertEquals(warmth(VolumetricLight.shaftColour(h, 0, 0)), warmth(VolumetricLight.shaftColour(h, 1, 0)), 1e-5f, "rain dims, not tints");
        }
        assertTrue(luma(at(SUNSET, 1, 0).shaftLight()) < luma(at(SUNSET, 0, 0).shaftLight()));
    }

    @Test
    void theLowSunGivesStrongerShaftsThanNoonAndTheyFadeInAtTheHorizon() {
        assertTrue(luma(at(SUNSET, 0, 0).shaftLight()) > luma(at(NOON, 0, 0).shaftLight()));
        float last = 0;
        for (int i = 1; i <= 45; i++) {
            float h = i / 1000f;//the fade's rising part (3/4 of HORIZON_FADE)
            float a = VolumetricLight.amount(h);
            assertTrue(a >= last, "h " + h);
            last = a;
        }
        assertTrue(VolumetricLight.amount(0.001f) < 0.01f * VolumetricLight.LOW_SUN_AMOUNT);
        assertEquals(VolumetricLight.NOON_AMOUNT, VolumetricLight.amount(1), 1e-6f);
    }

    // ---- 3. the programs ----

    private static VulkanShaderMeta meta(String program) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    private static VulkanShaderMeta.Kind kind(VulkanShaderMeta m, int binding) {
        for (VulkanShaderMeta.Binding b : m.bindings()) if (b.binding() == binding) return b.kind();
        return null;
    }

    private static String type(VulkanShaderMeta m, int location) {
        for (VulkanShaderMeta.PushMember p : m.push()) if (p.location() == location) return p.type();
        return null;
    }

    @Test
    void theMarchDeclaresTheShadowSlotsAndBindingsAndValuesWriteTheRest() {
        VulkanShaderMeta m = meta("shafts.VL");
        assertEquals("mat4", type(m, ShadowUniforms.NEAR));
        assertEquals("mat4", type(m, ShadowUniforms.FAR));
        assertEquals("vec4", type(m, ShadowUniforms.PARAMS));
        assertEquals("vec4", type(m, ShadowUniforms.BIAS));
        assertEquals(VulkanShaderMeta.Kind.IMAGE, kind(m, 0));
        for (int b : new int[]{2, 3, 8, 9}) assertEquals(VulkanShaderMeta.Kind.SAMPLER, kind(m, b), "binding " + b);
        //and no other texture or image (the loose uniforms may sit in a uniform buffer of their own)
        assertEquals(5, m.bindings().stream().filter(b -> b.kind() != VulkanShaderMeta.Kind.UNIFORM_BUFFER).count());
        Set<Integer> shadowSlots = Set.of(ShadowUniforms.NEAR, ShadowUniforms.FAR, ShadowUniforms.PARAMS, ShadowUniforms.BIAS);
        Matrix4f nan = new Matrix4f().set(nanArray());
        for (VolumetricLight.Values v : new VolumetricLight.Values[]{at(SUNSET, 0, 0), VolumetricLight.off()}) {
            LooseUniformWriter w = new LooseUniformWriter(m);
            for (VulkanShaderMeta.PushMember p : m.push()) {
                if (p.type().equals("mat4")) w.putMat4(p.location(), nan);
                else w.putVec4(p.location(), Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            }
            v.write(w);
            ByteBuffer b = w.buffer();
            Set<Integer> written = new HashSet<>();
            for (VulkanShaderMeta.PushMember p : m.push()) {
                int floats = p.type().equals("mat4") ? 16 : 4;
                boolean all = true;
                for (int i = 0; i < floats; i++) all &= !Float.isNaN(b.getFloat(p.offset() + 4 * i));
                if (all) written.add(p.location());
                if (!shadowSlots.contains(p.location())) assertTrue(all, "location " + p.location() + " (" + p.name() + ") not written");
            }
            //Values leaves the clean slots to ShadowUniforms, which writes them all
            for (int slot : shadowSlots) assertFalse(written.contains(slot), "Values wrote the clean slot " + slot);
            shadowOn(true).write(w);
            ShadowUniforms.off().write(w);
        }
    }

    private static float[] nanArray() {
        float[] a = new float[16];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    @Test
    void theBlurTakesTheMaskAndNoUniforms() {
        VulkanShaderMeta m = meta("shafts.VL_BLUR");
        assertEquals(0, m.pushSize());
        assertEquals(VulkanShaderMeta.Kind.IMAGE, kind(m, 0));
        assertEquals(VulkanShaderMeta.Kind.SAMPLER, kind(m, 1));
        assertEquals(2, m.bindings().size());
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        Pattern constant = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;");
        StringBuilder both = new StringBuilder();
        int found = 0;
        for (String file : new String[]{"post/volumetric.comp", "post/volumetric_blur.comp"}) {
            String text = ShaderFiles.read(file);
            both.append(text);
            Matcher m = constant.matcher(text);
            while (m.find()) {
                Field f = VolumetricMath.class.getField(m.group(2));
                assertEquals(m.group(1), f.getType().getName(), m.group(2));
                double want = Double.parseDouble(m.group(3));
                assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-7 * Math.max(1, Math.abs(want)), m.group(2));
                found++;
            }
        }
        assertEquals(7, found);
        for (Field f : VolumetricMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !(f.getType() == float.class || f.getType() == int.class)) continue;
            assertTrue(Pattern.compile("const\\s+" + f.getType().getName() + "\\s+" + f.getName() + "\\b").matcher(both).find(), f.getName() + " missing in the shaders");
        }
    }

    // ---- 4. the blur ----

    @Test
    void theBlurWeightsSumToOneAndKeepAUniformImageUniform() {
        float sum = 0;
        for (int i = -VolumetricMath.BLUR_RADIUS; i <= VolumetricMath.BLUR_RADIUS; i++) sum += VolumetricMath.blurWeight(i);
        assertEquals(1f, sum, 1e-7f);
        for (int[] size : new int[][]{{37, 23}, {64, 36}, {1, 1}, {2, 3}, {5, 4}}) {
            float[] img = new float[size[0] * size[1]];
            Arrays.fill(img, 0.73f);
            for (float f : VolumetricMath.blur(img, size[0], size[1])) assertEquals(0.73f, f, 1e-6f, size[0] + "x" + size[1]);
        }
    }

    @Test
    void theDitherIsAPermutationInEvery4x4WindowAndTheBlurRemovesIt() {
        for (int oy = 0; oy < 4; oy++) for (int ox = 0; ox < 4; ox++) {
            Set<Float> seen = new HashSet<>();
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                float d = VolumetricMath.dither(ox + x, oy + y);
                assertTrue(d > 0 && d < 1);
                seen.add(d);
            }
            assertEquals(16, seen.size());
        }
        //A shadow edge inside a ray: each texel's ray offset differs, the blur gives every interior texel the 16 offsets' mean
        VolumetricMath.Visibility v = t -> t > 300 && t < 700 ? 0 : 1;
        float dist = 1200, cos = 0.8f;
        float mean = 0;
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) mean += VolumetricMath.march(v, dist, VolumetricMath.dither(x, y), cos) / 16;
        int w = 24, h = 20;
        float[] img = new float[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img[y * w + x] = VolumetricMath.march(v, dist, VolumetricMath.dither(x, y), cos);
        float[] out = VolumetricMath.blur(img, w, h);
        for (int y = 2; y < h - 2; y++) for (int x = 2; x < w - 2; x++) assertEquals(mean, out[y * w + x], 1e-5f, x + "," + y);
        //and the dither was there to remove
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float f : img) {
            lo = Math.min(lo, f);
            hi = Math.max(hi, f);
        }
        assertTrue(hi - lo > 0.01f * mean);
    }

    // ---- the march ----

    @Test
    void aLitRayGivesTheSameAmountForEveryOffsetAndShadowOnlyRemovesLight() {
        for (float dist : new float[]{0, 3, 40, 160, 1536}) {
            for (float cos : new float[]{-1, 0, 0.7f, 1}) {
                float lit = VolumetricMath.litRay(dist, cos);
                for (int i = 0; i < 16; i++) {
                    float j = VolumetricMath.dither(i & 3, i >> 2);
                    assertEquals(lit, VolumetricMath.march(t -> 1, dist, j, cos), 1e-5f * Math.max(1, lit));
                    assertEquals(0f, VolumetricMath.march(t -> 0, dist, j, cos), 0f);
                    float part = VolumetricMath.march(t -> t < dist / 3 ? 1 : 0, dist, j, cos);
                    assertTrue(part >= 0 && part <= lit + 1e-6f);
                }
            }
        }
        //Longer lit rays scatter more; toward the sun more than away from it
        assertTrue(VolumetricMath.litRay(1000, 1) > VolumetricMath.litRay(100, 1));
        assertTrue(VolumetricMath.phase(1) > VolumetricMath.phase(0) && VolumetricMath.phase(0) > VolumetricMath.phase(-1));
        assertTrue(VolumetricMath.phase(-1) > 0);
        //Henyey-Greenstein scaled by 4 pi averages to 1 over the sphere (isotropic = 1)
        double avg = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) avg += VolumetricMath.phase(-1 + 2 * (i + 0.5f) / n) / n;
        assertEquals(1.0, avg, 1e-3);
    }

    // ---- the cascade rule (round 2) ----

    private static final VolumetricMath.ShadowMap CLEARED = (x, y) -> 1;//nothing drawn there
    private static final VolumetricMath.ShadowMap OCCLUDED = (x, y) -> 0.2f;//a caster at depth 0.2 everywhere

    private static float vis(float[] near, float[] far, float pick, boolean nearOn, boolean farOn, VolumetricMath.ShadowMap nearMap,
                             VolumetricMath.ShadowMap farMap) {
        return VolumetricMath.visibility(near, far, pick, nearOn, farOn, ShadowMath.BLEND, nearMap, 0, farMap, 0);
    }

    @Test
    void withOnlyTheNearCascadeItsBlendBandIsStillReadNotLit() {
        float[] band = {0.97f, 0, 0.5f};//inside the near cascade's outer band, behind the caster
        float[] none = {5, 5, 0.5f};
        for (int i = 0; i < 16; i++) {
            float pick = VolumetricMath.dither(i & 3, i >> 2);
            assertEquals(0f, vis(band, none, pick, true, false, OCCLUDED, CLEARED), "near only, pick " + pick);
        }
        //With the far cascade drawn the band is shared: some picks read the far map (here lit), the others the near map
        float lit = 0;
        for (int i = 0; i < 16; i++) lit += vis(band, new float[]{0.1f, 0, 0.5f}, VolumetricMath.dither(i & 3, i >> 2), true, true, OCCLUDED, CLEARED);
        assertTrue(lit > 0 && lit < 16, "lit picks " + lit);
        //Inside the band's inner edge only the near map counts
        assertEquals(0f, vis(new float[]{0.5f, 0, 0.5f}, new float[]{0.05f, 0, 0.5f}, 0.01f, true, true, OCCLUDED, CLEARED));
    }

    @Test
    void pointsOutsideACascadesDepthRangeOrMapAreLitNotShadowedByTheClearedMap() {
        //The cleared map (1) would shadow z > 1: such points are past the map's range and count as lit, as do points above it
        for (float z : new float[]{-0.3f, 1.2f, Float.POSITIVE_INFINITY}) {
            assertEquals(1f, vis(new float[]{0, 0, z}, new float[]{0, 0, z}, 0.5f, true, true, OCCLUDED, OCCLUDED), "z " + z);
            assertEquals(1f, vis(new float[]{0, 0, z}, new float[]{0, 0, z}, 0.5f, true, false, CLEARED, CLEARED), "near only, z " + z);
        }
        for (float[] xy : new float[][]{{1.2f, 0}, {0, -1.5f}, {1, 0}}) {
            float[] p = {xy[0], xy[1], 0.5f};
            assertEquals(1f, vis(p, p, 0.5f, true, true, OCCLUDED, OCCLUDED), "outside the maps " + xy[0] + "," + xy[1]);
        }
        //A point the near cascade cannot cover in depth falls to the far one when that covers it
        assertEquals(0f, vis(new float[]{0, 0, 1.3f}, new float[]{0, 0, 0.6f}, 0.5f, true, true, CLEARED, OCCLUDED));
        //Inside the range the comparison holds: in front of the caster lit, behind it shadowed
        assertEquals(1f, vis(new float[]{0, 0, 0.1f}, new float[]{0, 0, 0.1f}, 0.5f, true, true, OCCLUDED, OCCLUDED));
        assertEquals(0f, vis(new float[]{0, 0, 0.6f}, new float[]{0, 0, 0.6f}, 0.5f, true, true, OCCLUDED, OCCLUDED));
        //No cascade drawn: lit
        assertEquals(1f, vis(new float[]{0, 0, 0.6f}, new float[]{0, 0, 0.6f}, 0.5f, false, false, OCCLUDED, OCCLUDED));
    }
}
