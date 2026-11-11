package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.Bytecode;
import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W9: the jitter (TaaJitter), the reprojection and resolve (TaaMath mirrors post_taa.comp), the history rules and the pass's uniforms. */
class TaaTest {
    private static final int W = 1920, H = 1080;

    @AfterEach
    void reset() {
        TaaJitter.resetForTest();
    }

    // ---- 1. the jitter sequence ----

    @Test
    void theJitterHasZeroMeanStaysInsideOnePixelAndRepeats() {
        int n = TaaMath.JITTER_PHASES;
        double mx = 0, my = 0;
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < n; i++) {
            float[] o = TaaJitter.offset(i);
            assertTrue(Math.abs(o[0]) < 0.5f && Math.abs(o[1]) < 0.5f, Arrays.toString(o));
            mx += o[0];
            my += o[1];
            distinct.add(o[0] + "," + o[1]);
            assertArrayEquals(o, TaaJitter.offset(i + n));
            assertArrayEquals(o, TaaJitter.offset(i - 3 * n));
        }
        assertEquals(0, mx / n, 1e-6);
        assertEquals(0, my / n, 1e-6);
        assertEquals(n, distinct.size(), "every phase is a different point");
        //the spread covers the pixel: some offsets on each side of the centre on both axes
        float minX = 1, maxX = -1, minY = 1, maxY = -1;
        for (int i = 0; i < n; i++) {
            float[] o = TaaJitter.offset(i);
            minX = Math.min(minX, o[0]);
            maxX = Math.max(maxX, o[0]);
            minY = Math.min(minY, o[1]);
            maxY = Math.max(maxY, o[1]);
        }
        //2026-10-28: the pattern spans JITTER_SCALE of a pixel (0.5: half the box, a sharper canopy)
        assertTrue(maxX - minX > 0.6f * TaaMath.JITTER_SCALE && maxY - minY > 0.6f * TaaMath.JITTER_SCALE);
        assertTrue(maxX - minX < TaaMath.JITTER_SCALE && maxY - minY < TaaMath.JITTER_SCALE, "within JITTER_SCALE of a pixel");
    }

    @Test
    void anActiveJitterWalksThePhasesAndOffsetsTheProjectionInScreenSpace() {
        TaaJitter.setActive(true);
        Matrix4f base = glProjection(70, 0.05f, 1000f);
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < 2 * TaaMath.JITTER_PHASES; k++) {
            Matrix4f p = TaaJitter.apply(new Matrix4f(base), W, H);
            float[] ndc = TaaJitter.appliedNdc();
            seen.add(ndc[0] + "," + ndc[1]);
            assertTrue(Math.abs(ndc[0]) * W / 2 < 0.5f && Math.abs(ndc[1]) * H / 2 < 0.5f);
            //a view point lands exactly (ndc) further in x and y; depth and w unchanged
            Vector4f v = new Vector4f(3, -2, -40, 1);
            Vector4f a = base.transform(new Vector4f(v)), b = p.transform(new Vector4f(v));
            assertEquals(a.x / a.w + ndc[0], b.x / b.w, 1e-5f);
            assertEquals(a.y / a.w + ndc[1], b.y / b.w, 1e-5f);
            assertEquals(a.z / a.w, b.z / b.w, 1e-6f);
            assertEquals(a.w, b.w, 0f);
        }
        assertEquals(TaaMath.JITTER_PHASES, seen.size());
    }

    /**
     * Final fix R4: the jitter is a fraction of a pixel of the framebuffer the level is drawn into (the render-scaled main target the
     * post-processing and VkTaa use), not of the window: at render scale 50 a 2560x1440 window draws the level into 1280x720, and the
     * offset must stay within one of those pixels and walk the same phases in them.
     */
    @Test
    void theJitterIsScaledByTheLevelsFramebufferNotTheWindow() {
        assertArrayEquals(new int[]{1280, 720}, TaaJitter.levelSize(1280, 720, 2560, 1440), "the scaled main target");
        assertArrayEquals(new int[]{2560, 1440}, TaaJitter.levelSize(2560, 1440, 2560, 1440), "render scale 100");
        assertArrayEquals(new int[]{2560, 1440}, TaaJitter.levelSize(0, 0, 2560, 1440), "no framebuffer bound: the window");
        assertArrayEquals(new int[]{2560, 1440}, TaaJitter.levelSize(-1, 720, 2560, 1440));
        TaaJitter.setActive(true);
        int[] level = TaaJitter.levelSize(1280, 720, 2560, 1440);
        for (int k = 0; k < TaaMath.JITTER_PHASES; k++) {
            TaaJitter.apply(new Matrix4f(), level[0], level[1]);
            float[] ndc = TaaJitter.appliedNdc();
            float[] o = TaaJitter.offset(k + 1);
            //the offset in the level's own pixels is the phase's offset: a full-pixel jitter at any render scale
            assertEquals(o[0], ndc[0] * level[0] / 2, 1e-5f);
            assertEquals(o[1], ndc[1] * level[1] / 2, 1e-5f);
        }
    }

    // ---- 2. the reprojection ----

    private static Matrix4f glProjection(float fovDeg, float near, float far) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDeg), (float) W / H, near, far);
    }

    private static Matrix4f view(float yawDeg, float pitchDeg) {
        return new Matrix4f().rotateX((float) Math.toRadians(pitchDeg)).rotateY((float) Math.toRadians(yawDeg));
    }

    /** A frame as clean code builds it: inverse projections of the jittered GL projections (0..1 depth), the inverse view. */
    private static VkTaa.Frame frame(Matrix4f glVanilla, Matrix4f glLod, Matrix4f view, double x, double y, double z) {
        return new VkTaa.Frame(ReforgedProjection.vulkanDepth(glVanilla).invert(), ReforgedProjection.vulkanDepth(glLod).invert(),
                new Matrix4f(view).invert(), x, y, z, true, 256);
    }

    /** One drawn frame: the jitter is applied to both projections as the mixin does; returns {frame, jitter}. */
    private static Object[] drawn(float fov, Matrix4f view, double x, double y, double z) {
        Matrix4f vanilla = TaaJitter.apply(glProjection(fov, 0.05f, 512f), W, H);
        float[] j = TaaJitter.appliedNdc();
        Matrix4f lod = glProjection(fov, 16f, 40000f).translateLocal(j[0], j[1], 0);
        return new Object[]{frame(vanilla, lod, view, x, y, z), j};
    }

    @Test
    void withAStillCameraEveryPixelReprojectsOntoItselfThroughTheJitter() {
        TaaJitter.setActive(true);
        Matrix4f view = view(37, 21);
        Object[] f0 = drawn(70, view, 120.25, 70.5, -3000.75);
        TaaMath.Camera prev = TaaMath.camera((VkTaa.Frame) f0[0], (float[]) f0[1]);
        for (int k = 0; k < TaaMath.JITTER_PHASES; k++) {
            Object[] f1 = drawn(70, view, 120.25, 70.5, -3000.75);
            float[] j = (float[]) f1[1];
            assertTrue(j[0] != 0 || j[1] != 0);
            TaaMath.Values v = TaaMath.compute((VkTaa.Frame) f1[0], j, prev, true);
            assertTrue(v.historyValid());
            for (int px = 0; px < W; px += 137) {
                for (int py = 0; py < H; py += 89) {
                    float x = px + 0.5f, y = py + 0.5f;
                    for (float d : new float[]{0.2f, 0.9f, 0.999f, 0.99999f}) {
                        assertAt(x, y, TaaMath.reproject(v.invVanillaProjection(), v.prevFromView(), x, y, d, W, H, false), "vanilla " + d);
                        assertAt(x, y, TaaMath.reproject(v.invLodProjection(), v.prevFromView(), x, y, d, W, H, false), "lod " + d);
                    }
                    assertAt(x, y, TaaMath.reproject(v.invVanillaProjection(), v.prevFromView(), x, y, 1f, W, H, true), "sky");
                }
            }
            prev = TaaMath.camera((VkTaa.Frame) f1[0], j);
        }
    }

    private static void assertAt(float x, float y, float[] r, String what) {
        assertNotNull(r, what);
        assertEquals(x, r[0], 5e-3f, what + " x");
        assertEquals(y, r[1], 5e-3f, what + " y");
    }

    /** Where a camera-relative world point appears in a frame without its jitter: {x, y (pixels from top-left), depth 0..1}. */
    private static float[] project(Matrix4f glUnjittered, Matrix4f view, Vector3f rel) {
        Vector4f c = ReforgedProjection.vulkanDepth(glUnjittered).mul(view).transform(new Vector4f(rel, 1));
        return new float[]{(c.x / c.w * 0.5f + 0.5f) * W, (1f - (c.y / c.w * 0.5f + 0.5f)) * H, c.z / c.w};
    }

    @Test
    void aMovingTurningCameraWithChangingFovFindsWhereAWorldPointWasLastFrame() {
        TaaJitter.setActive(true);
        Matrix4f view0 = view(10, 15), view1 = view(12.5f, 14);
        double[] c0 = {1000.5, 80.25, -2000.75}, c1 = {1003.1, 80.9, -2004.2};
        Object[] f0 = drawn(70, view0, c0[0], c0[1], c0[2]);
        Object[] f1 = drawn(74, view1, c1[0], c1[1], c1[2]);//sprinting widens the field of view
        TaaMath.Values v = TaaMath.compute((VkTaa.Frame) f1[0], (float[]) f1[1], TaaMath.camera((VkTaa.Frame) f0[0], (float[]) f0[1]), true);
        double[][] points = {{1010, 70, -2050}, {990, 60, -2030}, {1050, 85, -2100}, {1003, 75, -2014}};
        for (double[] wp : points) {
            Vector3f rel1 = new Vector3f((float) (wp[0] - c1[0]), (float) (wp[1] - c1[1]), (float) (wp[2] - c1[2]));
            Vector3f rel0 = new Vector3f((float) (wp[0] - c0[0]), (float) (wp[1] - c0[1]), (float) (wp[2] - c0[2]));
            float[] now = project(glProjection(74, 0.05f, 512f), view1, rel1);
            float[] then = project(glProjection(70, 0.05f, 512f), view0, rel0);
            float[] r = TaaMath.reproject(v.invVanillaProjection(), v.prevFromView(), now[0], now[1], now[2], W, H, false);
            assertNotNull(r);
            assertEquals(then[0], r[0], 0.05f, Arrays.toString(wp));
            assertEquals(then[1], r[1], 0.05f, Arrays.toString(wp));
            assertEquals(-view0.transform(new Vector4f(rel0, 1)).z, r[2], 1e-2f * Math.abs(r[2]), "previous clip w is the old linear depth");
        }
        //the sky follows the rotation only, whatever the camera's translation
        Vector3f dir = new Vector3f(0.3f, 0.2f, -1f).normalize();
        float[] now = project(glProjection(74, 0.05f, 512f), view1, new Vector3f(dir).mul(500f));
        float[] then = project(glProjection(70, 0.05f, 512f), view0, new Vector3f(dir).mul(500f));
        float[] r = TaaMath.reproject(v.invVanillaProjection(), v.prevFromView(), now[0], now[1], 1f, W, H, true);
        assertEquals(then[0], r[0], 0.1f);
        assertEquals(then[1], r[1], 0.1f);
    }

    // ---- 3. the history ----

    @Test
    void theHistoryIsUsableOnlyRightAfterTheFrameThatWroteItAtTheSameSize() {
        TaaMath.Camera cam = new TaaMath.Camera(new Matrix4f(), new Matrix4f(), 0, 0, 0);
        TaaHistory h = new TaaHistory();
        h.resize(W, H);
        assertFalse(h.usable(1, s -> false), "nothing written yet");
        h.written(5, cam);
        assertTrue(h.usable(6, s -> false));
        assertFalse(h.usable(7, s -> false), "a frame without TAA in between");
        assertFalse(h.usable(6, s -> s == 5), "the writing frame was dropped");
        h.resize(W, H);
        assertTrue(h.usable(6, s -> false), "the same size keeps it");
        h.resize(W, H + 1);
        assertFalse(h.usable(6, s -> false), "a new size discards it");
        assertEquals(null, h.camera());
        h.written(6, cam);
        h.discard();//VkTaa.skipped
        assertFalse(h.usable(7, s -> false));
    }

    private static float[][] neighbourhood(float r, float g, float b) {
        float[][] n = new float[9][];
        for (int i = 0; i < 9; i++) n[i] = new float[]{r, g, b};
        return n;
    }

    @Test
    void withoutUsableHistoryTheCurrentImageIsShownUnblended() {
        float[][] n = neighbourhood(0.3f, 0.5f, 0.7f);
        n[4] = new float[]{0.31f, 0.52f, 0.69f};
        float[] history = {0.9f, 0.1f, 0.2f};
        assertArrayEquals(n[4], TaaMath.resolve(n, history, false, 10, 10, 0, false, false), 0f);
        assertArrayEquals(n[4], TaaMath.resolve(n, null, true, 10, 10, 0, false, false), 0f);
        //history that showed something nearer (disocclusion), or a broken fetch
        assertArrayEquals(n[4], TaaMath.resolve(n, history, true, 5, 10, 0, false, false), 0f);
        assertArrayEquals(n[4], TaaMath.resolve(n, new float[]{Float.NaN, 0, 0}, true, 10, 10, 0, false, false), 0f);
        //and the uniforms say so after a skipped frame: no camera, no history
        TaaMath.Values v = TaaMath.compute(still(), new float[2], null, true);
        assertFalse(v.historyValid());
        LooseUniformWriter w = new LooseUniformWriter(meta());
        v.write(w);
        assertEquals(0f, w.buffer().getFloat(offsetOf(TaaMath.TAA_PARAMS)), 0f);
    }

    @Test
    void validHistoryIsBlendedAndGhostsAreClippedToTheNeighbourhood() {
        float[][] n = neighbourhood(0.4f, 0.4f, 0.4f);
        //agreeing history: unchanged
        assertArrayEquals(new float[]{0.4f, 0.4f, 0.4f}, TaaMath.resolve(n, new float[]{0.4f, 0.4f, 0.4f}, true, 10, 10, 0, false, false), 1e-5f);
        //a ghost far outside a flat neighbourhood is pulled into its box
        float[] r = TaaMath.resolve(n, new float[]{1f, 0.1f, 0.9f}, true, 10, 10, 0, false, false);
        for (float c : r) assertEquals(0.4f, c, 0.005f);
        //an edge pixel: history inside the neighbourhood's range keeps most of its weight
        float[][] e = neighbourhood(0.2f, 0.2f, 0.2f);
        for (int i = 0; i < 9; i += 3) e[i] = new float[]{0.8f, 0.8f, 0.8f};
        e[4] = new float[]{0.8f, 0.8f, 0.8f};
        float[] blended = TaaMath.resolve(e, new float[]{0.5f, 0.5f, 0.5f}, true, 10, 10, 0, false, false);
        assertEquals(0.5f + (0.8f - 0.5f) * TaaMath.CURRENT_WEIGHT, blended[1], 1e-4f);
    }

    @Test
    void theCurrentShareGrowsWithMotionAndOnTranslucentSurfacesAndTheBoxWithDistance() {
        assertEquals(TaaMath.CURRENT_WEIGHT, TaaMath.currentWeight(0, false), 0f);
        assertEquals(TaaMath.MOVING_CURRENT_WEIGHT, TaaMath.currentWeight(100, false), 0f);
        float prev = 0;
        for (float m = 0; m <= 8; m += 0.5f) {
            float w = TaaMath.currentWeight(m, false);
            assertTrue(w >= prev);
            prev = w;
            assertTrue(TaaMath.currentWeight(m, true) >= TaaMath.TRANSLUCENT_CURRENT_WEIGHT);
        }
        assertTrue(TaaMath.clipGamma(true, false) < TaaMath.clipGamma(false, false));
        assertTrue(TaaMath.clipGamma(false, true) > TaaMath.clipGamma(false, false));
        assertTrue(TaaMath.SKY_DEPTH < 65504f, "fits rgba16f");
        assertFalse(TaaMath.disoccluded(TaaMath.SKY_DEPTH, TaaMath.SKY_DEPTH), "sky after sky");
        assertTrue(TaaMath.disoccluded(300, TaaMath.SKY_DEPTH), "sky revealed behind terrain");
        assertFalse(TaaMath.disoccluded(TaaMath.SKY_DEPTH, 70000f), "an LOD past the stored range keeps its history");
        assertFalse(TaaMath.disoccluded(95, 100), "within the tolerance");
    }

    /** VkTaa follows the history rules: a size change and skipped() discard, prepare asks TaaHistory and reads the jitter it was drawn with. */
    @Test
    void vkTaaDiscardsTheHistoryOnResizeAndSkipAndUsesTheAppliedJitter() throws Exception {
        String taa = "net/vulkanmod/visuals/render/VkTaa";
        assertTrue(Bytecode.calls(taa, "ensureSize").contains("TaaHistory.resize"));
        assertTrue(Bytecode.calls(taa, "skipped").contains("TaaHistory.discard"));
        List<String> prepare = Bytecode.calls(taa, "prepare");
        assertTrue(prepare.containsAll(List.of("TaaHistory.usable", "TaaJitter.appliedNdc", "TaaMath.compute", "TaaMath$Values.write")), prepare.toString());
        List<String> compute = Bytecode.calls(taa, "compute");
        assertTrue(Bytecode.inOrder(compute, List.of("VkComputeProgram.dispatchWithPush", "TaaHistory.written")), compute.toString());
        assertTrue(compute.contains("VkGpuTimer.mark"));
    }

    // ---- 4. the shader ----

    private static VulkanShaderMeta meta() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get("postTaa");
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    private static int offsetOf(int location) {
        for (VulkanShaderMeta.PushMember m : meta().push()) if (m.location() == location) return m.offset();
        throw new AssertionError("no location " + location);
    }

    private static VulkanShaderMeta.Kind kind(VulkanShaderMeta m, int binding) {
        for (VulkanShaderMeta.Binding b : m.bindings()) if (b.binding() == binding && b.kind() != VulkanShaderMeta.Kind.UNIFORM_BUFFER) return b.kind();
        return null;
    }

    private static VkTaa.Frame still() {
        return frame(glProjection(70, 0.05f, 512f), glProjection(70, 16, 40000), view(0, 0), 0, 64, 0);
    }

    @Test
    void aParticleTakesTheCurrentColourUnblended() {
        //2026-11-11 (the user: see-through snowflakes): nearer than the depth right after the translucent terrain is a particle
        assertTrue(TaaMath.particle(0.90f, 0.99f));
        assertFalse(TaaMath.particle(0.99f, 0.99f), "the terrain, water included, is not");
        assertFalse(TaaMath.particle(0.95f, 0.95f), "without the copy the main depth stands in: nothing is");
        //Its old blend: a white flake over a grey history at the current weight showed mostly the grey
        float[][] n = new float[9][];
        for (int i = 0; i < 9; i++) n[i] = new float[]{0.5f, 0.5f, 0.5f};
        n[4] = new float[]{0.9f, 0.9f, 0.9f};
        float[] blended = TaaMath.resolve(n, new float[]{0.5f, 0.5f, 0.5f}, true, 10, 10, 1, true, false);
        assertTrue(blended[0] < 0.75f, "blended " + blended[0]);
        String comp = ShaderFiles.read("post/post_taa.comp").replace(String.valueOf((char) 13), "");
        int at = comp.indexOf("if (texelFetch(afterDepth, p, 0).r > texelFetch(mainDepth, p, 0).r) {");
        assertTrue(at > comp.indexOf("if (taaParams.x < 0.5) {") && at < comp.indexOf("vec3 m1 = vec3(0.0)"), "before the neighbourhood");
        assertTrue(comp.startsWith("        imageStore(taaOut, p, vec4(current, ownDepth));", comp.indexOf('{', at) + 2));
    }

    @Test
    void theShaderDeclaresBoundary35sBindingsAndEveryUniformIsWritten() {
        VulkanShaderMeta m = meta();
        assertEquals(VulkanShaderMeta.Kind.IMAGE, kind(m, VkTaa.OUT));
        for (int b : new int[]{VkTaa.CURRENT, VkTaa.HISTORY, VkTaa.MAIN_DEPTH, VkTaa.LOD_DEPTH, VkTaa.OPAQUE_DEPTH, VkTaa.AFTER_DEPTH}) {
            assertEquals(VulkanShaderMeta.Kind.SAMPLER, kind(m, b), "binding " + b);
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), List.of(VkTaa.OUT, VkTaa.CURRENT, VkTaa.HISTORY, VkTaa.MAIN_DEPTH, VkTaa.LOD_DEPTH, VkTaa.OPAQUE_DEPTH, VkTaa.AFTER_DEPTH));
        assertEquals(7, m.bindings().stream().filter(b -> b.kind() != VulkanShaderMeta.Kind.UNIFORM_BUFFER).count());
        TaaMath.Camera prev = TaaMath.camera(still(), new float[]{0.0005f, -0.0003f});
        for (TaaMath.Values v : new TaaMath.Values[]{TaaMath.compute(still(), new float[2], prev, true), TaaMath.compute(still(), new float[2], null, false)}) {
            LooseUniformWriter w = new LooseUniformWriter(m);
            float[] nan = new float[16];
            Arrays.fill(nan, Float.NaN);
            for (VulkanShaderMeta.PushMember p : m.push()) {
                if (p.type().equals("mat4")) w.putMat4(p.location(), new Matrix4f().set(nan));
                else w.putVec4(p.location(), Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            }
            v.write(w);
            ByteBuffer b = w.buffer();
            for (VulkanShaderMeta.PushMember p : m.push()) {
                int floats = p.type().equals("mat4") ? 16 : 4;
                for (int i = 0; i < floats; i++) assertFalse(Float.isNaN(b.getFloat(p.offset() + 4 * i)), p.name() + " (location " + p.location() + ") not written");
            }
            assertEquals(LodClamp.CLAMP_THRESHOLD, b.getFloat(offsetOf(TaaMath.DEPTH_PARAMS)), 0f);
            assertEquals(1f, b.getFloat(offsetOf(TaaMath.DEPTH_PARAMS) + 4), 0f);
            assertEquals(256f, b.getFloat(offsetOf(TaaMath.TAA_PARAMS) + 4), 0f);
        }
        Set<Integer> declared = new HashSet<>();
        for (VulkanShaderMeta.PushMember p : m.push()) declared.add(p.location());
        assertEquals(Set.of(TaaMath.INV_VANILLA_PROJ, TaaMath.INV_LOD_PROJ, TaaMath.PREV_FROM_VIEW, TaaMath.DEPTH_PARAMS, TaaMath.TAA_PARAMS), declared);
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        String text = ShaderFiles.read("post/post_taa.comp");
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
        int found = 0;
        while (m.find()) {
            Field f = TaaMath.class.getField(m.group(2));
            assertEquals(m.group(1), f.getType().getName(), m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-7 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertEquals(12, found);
        for (Field f : TaaMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !(f.getType() == float.class || f.getType() == int.class)) continue;
            if (Set.of("INV_VANILLA_PROJ", "INV_LOD_PROJ", "PREV_FROM_VIEW", "DEPTH_PARAMS", "TAA_PARAMS").contains(f.getName())) continue;//locations
            assertTrue(Pattern.compile("const\\s+" + f.getType().getName() + "\\s+" + f.getName() + "\\b").matcher(text).find(), f.getName() + " missing in post_taa.comp");
        }
    }

    // ---- 5. off means unjittered ----

    @Test
    void aSkippedPostProcessLeavesTheNextProjectionUnjittered() {
        TaaJitter.setActive(true);
        TaaJitter.apply(glProjection(70, 0.05f, 512f), W, H);
        assertTrue(TaaJitter.appliedNdc()[0] != 0 || TaaJitter.appliedNdc()[1] != 0);
        FrameFlags.resetForSkippedPost();
        assertFalse(TaaJitter.active());
        Matrix4f p = glProjection(70, 0.05f, 512f);
        Matrix4f out = TaaJitter.apply(new Matrix4f(p), W, H);
        assertEquals(p, out);
        assertArrayEquals(new float[]{0, 0}, TaaJitter.appliedNdc(), 0f);
        //and the frame drawn unjittered reprojects with no correction
        TaaMath.Values v = TaaMath.compute(still(), TaaJitter.appliedNdc(), null, false);
        assertTrue(still().invVanillaProjection().equals(v.invVanillaProjection(), 0f));
    }

    /** 2026-10-30 (audit): the weather replayed after the post-process (DeferredWeather) is drawn without the jitter TAA resolved. */
    @Test
    void theReplayedWeatherTakesTheJitterOff() {
        TaaJitter.setActive(true);
        Matrix4f p = glProjection(70, 0.05f, 512f);
        Matrix4f jittered = TaaJitter.apply(new Matrix4f(p), W, H);
        assertFalse(p.equals(jittered, 1e-7f));
        assertTrue(p.equals(TaaJitter.unjittered(jittered, TaaJitter.appliedNdc()), 1e-6f));
        String replay = readJava("visuals/render/DeferredWeather.java");
        int unjitter = replay.indexOf("TaaJitter.unjittered(RenderSystem.getProjectionMatrix(), jitter)");
        int rain = replay.indexOf("visuals$renderSnowAndRain(");
        assertTrue(unjitter > 0 && rain > unjitter && replay.indexOf("RenderSystem.restoreProjectionMatrix()") > rain);
    }

    private static String readJava(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/net/vulkanmod/" + path));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
