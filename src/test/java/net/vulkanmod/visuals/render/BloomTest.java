package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W3: the bloom pyramid (BloomMath mirrors bloom.comp) and the per-frame distance glow (BloomFog). */
class BloomTest {
    private static final PostProcessSettings SETTINGS = PostProcessSettings.from(new VisualsConfig());
    private static final long SERIAL = 7;
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f, true);

    private static AtmosphereFrame frame(float timeOfDay, float rain, float skyFogEnd) {
        return new AtmosphereFrame(SERIAL, PROJ.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(), new Matrix4f(), 70, 63, 0.6f, 0.7f,
                0.9f, false, false, timeOfDay, rain, 0, null, null, skyFogEnd, null, null);
    }

    private static BloomFog.Values values(float timeOfDay, float rain) {
        return BloomFog.compute(SETTINGS, frame(timeOfDay, rain, 256), SERIAL, true, 1);
    }

    private static float[] depthOf(int w, int h, float d) {
        float[] depth = new float[w * h];
        Arrays.fill(depth, d);
        return depth;
    }

    private static final BloomFog.Values NO_FOG = new BloomFog.Values(0, 1, 0, BloomFog.THRESHOLD, 0, 0, 0, 1);

    // ---- 1. the glow follows W2's contract ----

    /** W2's final mix at a full-resolution pixel: mix(scene, level0 / levels, w) (postfinal.glsl), nearest level-0 texel. */
    private static float w2Mix(BloomMath.Img scene, BloomMath.Img[] pyramid, float w, int x, int y, int c) {
        float glow = pyramid[0].get(x / 2, y / 2, c) / pyramid.length;
        float s = scene.get(x, y, c);
        return s + (glow - s) * w;
    }

    private static final float W_100 = PostMath.BLOOM_MIX;//the mix weight at bloom 100

    @Test
    void level0HoldsTheSceneAndItsAdditionsSummedOverTheLevels() {
        int w = 256, h = 144;
        int levels = PostMath.bloomSizes(w, h).length;
        //Bright uniform scene, no distance gain: P = v + (v - threshold), the same in every level, so level 0 is levels * P
        float v = 2f;
        BloomMath.Img[] pyramid = BloomMath.chain(BloomMath.Img.uniform(w, h, v, v, v), depthOf(w, h, 0.5f), NO_FOG);
        assertEquals(levels, pyramid.length);
        for (float f : pyramid[0].d) assertEquals(levels * (v + (v - BloomFog.THRESHOLD)), f, 1e-3f * levels);
        //Sky pixels with the full gain: P = scene * (1 + amount)
        BloomFog.Values far = new BloomFog.Values(0, 1, 0.5f, BloomFog.THRESHOLD, 0, 0, 0, 1);
        pyramid = BloomMath.chain(BloomMath.Img.uniform(w, h, 0.2f, 0.2f, 0.2f), depthOf(w, h, 1f), far);
        for (float f : pyramid[0].d) assertEquals(levels * 0.2f * 1.5f, f, 1e-4f * levels);
    }

    @Test
    void aUniformSubThresholdSceneIsUnchangedByW2sMixAtEveryStrength() {
        int w = 256, h = 144;
        //Below the knee's start (THRESHOLD - KNEE: 0.2 since 2026-10-24)
        for (float v : new float[]{0.003f, 0.1f, 0.15f, 0.19f}) {
            BloomMath.Img scene = BloomMath.Img.uniform(w, h, v, v * 0.8f, v * 0.5f);
            BloomMath.Img[] pyramid = BloomMath.chain(scene, depthOf(w, h, 0.5f), values(0, 0));//near depth: no distance gain
            for (float mix : new float[]{0.018f, W_100}) {
                for (int y : new int[]{0, 17, 143}) for (int x : new int[]{0, 100, 255}) for (int c = 0; c < 3; c++) {
                    assertEquals(scene.get(x, y, c), w2Mix(scene, pyramid, mix, x, y, c), 1e-5f * Math.max(1, v), "v " + v + " mix " + mix);
                }
            }
        }
    }

    @Test
    void farTerrainBrightensByAtMostAFewPercentAtBloom100() {
        //2026-10-24: the haze brightens far terrain by up to 9% (13.5% in rain) at bloom 100; a pale surface (0.4, above the knee's
        // start at 0.2) adds its soft-threshold part on top
        for (BloomFog.Values v : new BloomFog.Values[]{values(0, 0), values(0, 1), values(0.235f, 0.5f)}) {
            assertTrue(W_100 * v.amount() <= 0.14f, "gain " + W_100 * v.amount());
        }
        int w = 256, h = 144;
        BloomMath.Img scene = BloomMath.Img.uniform(w, h, 0.4f, 0.4f, 0.4f);
        BloomMath.Img[] pyramid = BloomMath.chain(scene, depthOf(w, h, 1f), values(0, 1));
        float out = w2Mix(scene, pyramid, W_100, 100, 70, 0);
        assertTrue(out > 0.4f && out <= 0.4f * 1.17f, "out " + out);
    }

    @Test
    void aVeryBrightPixelGainsLightAroundItAfterW2sMix() {
        int w = 128, h = 72;
        BloomMath.Img scene = new BloomMath.Img(w, h);
        for (int c = 0; c < 3; c++) scene.set(64, 36, c, 500f);
        BloomMath.Img[] pyramid = BloomMath.chain(scene, depthOf(w, h, 0.5f), NO_FOG);
        //Around the pixel the black scene gains light that falls off with distance
        float last = Float.MAX_VALUE;
        for (int d = 4; d <= 24; d += 2) {
            float f = w2Mix(scene, pyramid, W_100, 64 + d, 36, 1);
            assertTrue(f > 0f, "no light " + d + " pixels away");
            assertTrue(f <= last + 1e-7f, "light grows away from the pixel at " + d);
            last = f;
        }
        assertTrue(w2Mix(scene, pyramid, W_100, 64, 40, 0) > 0f && w2Mix(scene, pyramid, W_100, 64, 30, 0) > 0f && w2Mix(scene, pyramid, W_100, 50, 36, 0) > 0f);
        //Far from it, and at a black pixel of the next row of texels, the scene is not darkened below black
        for (float f : BloomMath.chain(scene, depthOf(w, h, 0.5f), NO_FOG)[0].d) assertTrue(f >= 0);
    }


    @Test
    void anAllBlackSceneGivesAnAllBlackGlowAndBadInputsGiveNoNaN() {
        int w = 64, h = 36;
        for (float depth : new float[]{0f, 0.5f, 1f}) {
            for (BloomMath.Img img : BloomMath.chain(BloomMath.Img.uniform(w, h, 0, 0, 0), depthOf(w, h, depth), values(0, 0))) {
                for (float f : img.d) assertEquals(0f, f, 0f);
            }
        }
        BloomMath.Img bad = BloomMath.Img.uniform(w, h, 0.3f, 0.3f, 0.3f);
        bad.set(10, 10, 0, Float.NaN);
        bad.set(20, 12, 1, Float.POSITIVE_INFINITY);
        bad.set(30, 14, 2, -5f);
        for (BloomMath.Img img : BloomMath.chain(bad, depthOf(w, h, 1f), values(0, 0))) {
            for (float f : img.d) assertTrue(Float.isFinite(f) && f >= 0, "value " + f);
        }
    }

    // ---- 2. sampling offsets ----

    @Test
    void auniformImageStaysUniformThroughDownAndUpAtOddSizes() {
        for (int[] size : new int[][]{{37, 23}, {64, 36}, {9, 9}, {1, 1}, {2, 3}}) {
            BloomMath.Img img = BloomMath.Img.uniform(size[0], size[1], 0.7f, 1.3f, 5f);
            BloomMath.Img lower = BloomMath.down(img);
            assertEquals((size[0] + 1) / 2, lower.w);
            assertEquals((size[1] + 1) / 2, lower.h);
            for (int i = 0; i < lower.d.length; i++) assertEquals(img.d[i % 3], lower.d[i], 1e-5f, "down " + size[0] + "x" + size[1]);
            BloomMath.Img base = BloomMath.Img.uniform(size[0], size[1], 0.25f, 0.5f, 1f);
            BloomMath.Img sum = BloomMath.up(base, lower);
            for (int i = 0; i < sum.d.length; i++) assertEquals(base.d[i % 3] + img.d[i % 3], sum.d[i], 1e-5f, "up " + size[0] + "x" + size[1]);
        }
    }

    @Test
    void theStepsKeepTheirCentreOfMassAndTheirEnergy() {
        //A 2x2 block at pixels 12..13 (centre 13.0 in texel units); level i-1 texel q covers pixels 2q and 2q+1 (centre 2q+1)
        BloomMath.Img img = new BloomMath.Img(32, 32);
        for (int y = 12; y <= 13; y++) for (int x = 12; x <= 13; x++) img.set(x, y, 0, 1f);
        BloomMath.Img lower = BloomMath.down(img);
        double sum = 0, mx = 0, my = 0;
        for (int y = 0; y < lower.h; y++) for (int x = 0; x < lower.w; x++) {
            float f = lower.get(x, y, 0);
            sum += f;
            mx += f * (2 * (x + 0.5));
            my += f * (2 * (y + 0.5));
        }
        assertEquals(13.0, mx / sum, 1e-4);
        assertEquals(13.0, my / sum, 1e-4);
        assertEquals(1.0, sum, 1e-4);//a 2x2 block of ones is one texel of ones at the lower level
        //UP: a single texel j=5 of a 16-wide level covers pixels 10 and 11 of the level above (centre 11.0)
        BloomMath.Img small = new BloomMath.Img(16, 16);
        small.set(5, 5, 0, 1f);
        BloomMath.Img big = BloomMath.up(new BloomMath.Img(32, 32), small);
        sum = mx = my = 0;
        for (int y = 0; y < big.h; y++) for (int x = 0; x < big.w; x++) {
            float f = big.get(x, y, 0);
            sum += f;
            mx += f * (x + 0.5);
            my += f * (y + 0.5);
        }
        assertEquals(11.0, mx / sum, 1e-4);
        assertEquals(11.0, my / sum, 1e-4);
        assertEquals(4.0, sum, 1e-3);//one source texel feeds four texels of the level above: the area ratio
    }

    // ---- 3. distance glow ----

    @Test
    void theDistanceGlowNeverDecreasesWithDistance() {
        for (BloomFog.Values v : new BloomFog.Values[]{values(0, 0), values(0, 1), values(0.5f, 0), values(0.235f, 0.4f)}) {
            float last = -1;
            for (float z = 0; z <= 5000; z += 0.5f) {
                float g = v.glowAt(z);
                assertTrue(g >= last && g >= 0, "z " + z);
                last = g;
            }
            assertTrue(v.glowAt(5000) > 0, "far terrain glows");
            assertEquals(0f, v.glowAt(0), 0f);
            //The same through the depth: a larger depth value is never a smaller distance
            last = -1;
            for (int i = 0; i <= 100000; i++) {
                float g = BloomMath.distanceGlow(i / 100000f, v);
                assertTrue(g >= last, "depth " + i / 100000f);
                last = g;
            }
            assertEquals(v.amount(), BloomMath.distanceGlow(1f, v), 1e-6f);//sky
        }
    }

    @Test
    void thereIsNoDistanceGlowWithoutSkyOrWithoutAFrame() {
        BloomFog.Values[] none = {BloomFog.compute(SETTINGS, frame(0, 0, 256), SERIAL, false, 1), BloomFog.compute(SETTINGS, null, SERIAL, true, 1),
                BloomFog.compute(SETTINGS, null, SERIAL, false, 1), BloomFog.compute(SETTINGS, frame(0, 0, 256), SERIAL + 1, true, 1)};
        for (BloomFog.Values v : none) {
            for (float z : new float[]{0, 100, 1000, 100000}) assertEquals(0f, v.glowAt(z), 0f);
            assertEquals(0f, BloomMath.distanceGlow(1f, v), 0f);
            assertTrue(v.threshold() > 0 && Float.isFinite(v.threshold()));
        }
    }

    @Test
    void theFrameValuesReactToRainNightAndTheRoof() {
        float z = 400;
        BloomFog.Values clear = values(0, 0), rain = values(0, 1), night = values(0.5f, 0);
        assertTrue(clear.glowAt(z) > 0);
        //Expectation: rain scatters the light, so the distance glow is stronger and begins earlier
        assertTrue(rain.glowAt(z) > clear.glowAt(z), "rain");
        assertTrue(rain.start() < clear.start());
        //Expectation: at night there is little light to scatter, so the glow is weaker, though not gone (moonlight, lamps)
        assertTrue(night.glowAt(5000) < clear.glowAt(5000), "night");
        assertTrue(night.glowAt(5000) > 0);
        //Expectation: under a roof (sky exposure 0) there is no distance haze
        assertEquals(0f, BloomFog.compute(SETTINGS, frame(0, 0, 256), SERIAL, true, 0).glowAt(5000), 0f);
        //The depth mapping gives the view distance of the vanilla depth buffer
        for (float dist : new float[]{1, 10, 100, 500}) {
            float depth = PROJ.transformProject(new Vector4f(0, 0, -dist, 1)).z;
            float back = (clear.depthA() * depth + clear.depthB()) / (clear.depthC() * depth + clear.depthE());
            assertEquals(dist, back, dist * 2e-3f);
        }
    }

    @Test
    void thePrefilterSlotsAreWrittenAtLocations1And2() {
        BloomFog.Values v = values(0, 0);
        LooseUniformWriter w = new LooseUniformWriter(meta("bloom.PREFILTER"));
        v.write(w);
        ByteBuffer b = w.buffer();
        int o1 = offsetOf("bloom.PREFILTER", 1), o2 = offsetOf("bloom.PREFILTER", 2);
        assertEquals(v.start(), b.getFloat(o1), 0f);
        assertEquals(v.invSpan(), b.getFloat(o1 + 4), 0f);
        assertEquals(v.amount(), b.getFloat(o1 + 8), 0f);
        assertEquals(v.threshold(), b.getFloat(o1 + 12), 0f);
        assertEquals(v.depthA(), b.getFloat(o2), 0f);
        assertEquals(v.depthB(), b.getFloat(o2 + 4), 0f);
        assertEquals(v.depthC(), b.getFloat(o2 + 8), 0f);
        assertEquals(v.depthE(), b.getFloat(o2 + 12), 0f);
    }

    // ---- 4. the programs ----

    private static VulkanShaderMeta meta(String program) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    private static int offsetOf(String program, int location) {
        for (VulkanShaderMeta.PushMember m : meta(program).push()) if (m.location() == location) return m.offset();
        throw new AssertionError(program + " has no location " + location);
    }

    private static VulkanShaderMeta.Kind kind(VulkanShaderMeta m, int binding) {
        for (VulkanShaderMeta.Binding b : m.bindings()) if (b.binding() == binding) return b.kind();
        return null;
    }

    @Test
    void everyProgramDeclaresItsPushConstantsAndBindings() {
        for (String program : List.of("bloom.PREFILTER", "bloom.DOWN", "bloom.UP")) {
            VulkanShaderMeta m = meta(program);
            assertFalse(m.pushInUniformBuffer(), program);
            LooseUniformWriter w = new LooseUniformWriter(m);
            assertTrue(w.has(PostUniforms.BLOOM_PARAMS), program);
            assertEquals(0, PostUniforms.BLOOM_PARAMS);
            boolean prefilter = program.endsWith("PREFILTER");
            assertEquals(prefilter, w.has(1), program);
            assertEquals(prefilter, w.has(2), program);
            assertTrue(m.pushSize() <= 128, program);
            assertEquals(VulkanShaderMeta.Kind.IMAGE, kind(m, 0), program);
            assertEquals(VulkanShaderMeta.Kind.SAMPLER, kind(m, 1), program);
            if (prefilter) assertEquals(VulkanShaderMeta.Kind.SAMPLER, kind(m, 2), program);
            else assertNull(kind(m, 2), program + " has no binding 2");
        }
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        String text = ShaderFiles.read("post/bloom.comp");
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
        int found = 0;
        while (m.find()) {
            Field f = BloomMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 20, "constants found: " + found);
        //and every mirror constant is in the shader
        for (Field f : BloomMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != float.class) continue;
            assertTrue(Pattern.compile("const\\s+float\\s+" + f.getName() + "\\b").matcher(text).find(), f.getName() + " missing in bloom.comp");
        }
    }
}
