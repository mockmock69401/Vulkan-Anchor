package net.vulkanmod.visuals.render;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.vulkanmod.visuals.Bytecode;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
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

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** W10: the ambient occlusion (ao.glsl and its three passes, mirrored by AmbientOcclusion): the brief's tests 1..4 and the wiring. */
class AmbientOcclusionTest {
    private static final String GLSL = ShaderFiles.read("post/ao.glsl");
    private static final String DEPTH = ShaderFiles.read("post/ao_depth.comp");
    private static final String OCCLUSION = ShaderFiles.read("post/ao.comp");
    private static final String BLUR = ShaderFiles.read("post/ao_blur.comp");
    private static final String COMPOSITE = ShaderFiles.read("post/scene_copy.comp");

    // ---- a test camera at 640x360 (half resolution 320x180), zero-to-one depth ----

    private static final int W = 640, H = 360;
    private static final int HW = AmbientOcclusion.halfSize(W), HH = AmbientOcclusion.halfSize(H);
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 1000f, true);
    private static final Matrix4f INV = new Matrix4f(PROJ).invert();
    private static final float TPB = AmbientOcclusion.texelsPerBlock(PROJ, H);

    /** A plane dot(x, n) = c in view space, seen only where keep says so. */
    private record Plane(Vector3f n, float c, java.util.function.Predicate<Vector3f> keep) {
    }

    /** A scene of planes, ray cast through each texel's centre as the depth pass places it. */
    private static final class Planes implements AmbientOcclusion.Scene {
        final List<Plane> planes;
        final float[][] depth = new float[HH][HW];
        final Vector3f[][] normal = new Vector3f[HH][HW];

        Planes(List<Plane> planes) {
            this.planes = planes;
            for (int y = 0; y < HH; y++) {
                for (int x = 0; x < HW; x++) {
                    Vector3f r = AmbientOcclusion.ray(INV, x, y, W, H);
                    float best = Float.MAX_VALUE;
                    Vector3f bestN = new Vector3f();
                    for (Plane p : planes) {
                        float dn = r.dot(p.n);
                        if (Math.abs(dn) < 1e-6f) continue;
                        float t = p.c / dn;
                        if (!(t > 0) || t >= best || !p.keep.test(new Vector3f(r).mul(t))) continue;
                        best = t;
                        bestN = p.n;
                    }
                    this.depth[y][x] = best == Float.MAX_VALUE ? 0 : AmbientOcclusion.depth(new Vector3f(r).mul(best), false);
                    this.normal[y][x] = bestN;
                }
            }
        }

        public int width() { return HW; }

        public int height() { return HH; }

        public float depth(int qx, int qy) { return this.depth[qy][qx]; }

        public Vector3f normal(int qx, int qy) { return this.normal[qy][qx]; }

        public float kind(int qx, int qy) { return 0; }

        float ao(int qx, int qy) {
            return AmbientOcclusion.occlusion(this, INV, W, H, TPB, qx, qy);
        }

        Vector3f point(int qx, int qy) {
            return AmbientOcclusion.ray(INV, qx, qy, W, H).mul(this.depth[qy][qx]);
        }
    }

    /** View-space directions of a camera pitched down by pitch: world up and the horizontal forward. */
    private static Vector3f up(float pitch) {
        return new Vector3f(0, (float) Math.cos(pitch), (float) Math.sin(pitch));
    }

    private static Vector3f forward(float pitch) {
        return new Vector3f(0, (float) Math.sin(pitch), (float) -Math.cos(pitch));
    }

    private static final float PITCH = (float) Math.toRadians(35), EYE = 3, WALL = 6;

    private static Planes floor() {
        Vector3f up = up(PITCH);
        return new Planes(List.of(new Plane(up, -EYE, x -> true)));
    }

    /** The floor EYE below the camera meeting a wall WALL ahead that faces the camera: an inner corner along the screen. */
    private static Planes corner() {
        Vector3f up = up(PITCH), fwd = forward(PITCH);
        return new Planes(List.of(new Plane(up, -EYE, x -> x.dot(fwd) <= WALL + 1e-4f),
                new Plane(new Vector3f(fwd).negate(), -WALL, x -> x.dot(up) >= -EYE - 1e-4f)));
    }

    // ---- brief test 1 ----

    @Test
    void anOpenPlaneHasNoOcclusionAnInnerCornerHasAndStrengthZeroHasNone() {
        Planes plane = floor();
        int seen = 0;
        for (int y = 0; y < HH; y++) {
            for (int x = 0; x < HW; x++) {
                if (plane.depth(x, y) <= 0) continue;
                assertTrue(plane.ao(x, y) > 0.99f, "plane at " + x + "," + y + ": " + plane.ao(x, y));
                seen++;
            }
        }
        assertTrue(seen > HW * HH / 3, "floor texels " + seen);

        Planes corner = corner();
        Vector3f fwd = forward(PITCH);
        float sum = 0;
        int crease = 0;
        for (int y = 0; y < HH; y++) {
            for (int x = 4; x < HW - 4; x++) {
                if (corner.depth(x, y) <= 0) continue;
                Vector3f p = corner.point(x, y);
                boolean onFloor = corner.normal(x, y).equals(up(PITCH));
                float fromWall = WALL - p.dot(fwd);
                if (!onFloor || fromWall > 0.3f) continue;
                float ao = corner.ao(x, y);
                assertTrue(ao < 0.85f, "crease at " + x + "," + y + ": " + ao);
                sum += ao;
                crease++;
            }
        }
        assertTrue(crease > 50, "crease texels " + crease);
        assertTrue(sum / crease < 0.75f, "mean at the crease " + sum / crease);
        //Beyond the search radius from the wall the floor is open again
        for (int x = 4; x < HW - 4; x += 7) {
            for (int y = 0; y < HH; y++) {
                if (corner.depth(x, y) <= 0 || !corner.normal(x, y).equals(up(PITCH))) continue;
                float reach = AmbientOcclusion.worldRadius(corner.depth(x, y));
                if (WALL - corner.point(x, y).dot(fwd) > reach) assertTrue(corner.ao(x, y) > 0.99f);
            }
        }
        //Strength 0 gives none: the factor on the light is exactly 1 whatever the pass left
        float[] perLight = {0.8f, 0.7f, 0.6f}, occludable = {0.5f, 0.4f, 0.3f};
        float none = AmbientOcclusion.strengthApplied(0.2f, 0);
        assertEquals(1f, none);
        assertArrayEquals(new float[]{1, 1, 1}, AmbientOcclusion.occludedLight(perLight, occludable, none));
        //Half strength is between
        float half = AmbientOcclusion.strengthApplied(0.2f, 0.5f);
        assertEquals(0.6f, half, 1e-6f);
        //Only the occludable part goes: direct sun and block light (not occludable) stay whatever the occlusion
        assertArrayEquals(new float[]{1, 1, 1}, AmbientOcclusion.occludedLight(perLight, new float[3], 0));
        float[] f = AmbientOcclusion.occludedLight(perLight, occludable, 0);
        for (int c = 0; c < 3; c++) assertEquals((perLight[c] - occludable[c]) / perLight[c], f[c], 1e-6f);
    }

    /**
     * Tuning round 1 (screen check: far too subtle): at strength 100 a sky-lit inner corner is clearly darker over a band, not a sliver:
     * about half its open value at the crease, still darkened a block out, open beyond the radius; a one-block gap's floor about as dark
     * as its view factor (sin 26.6 degrees = 0.45).
     */
    @Test
    void theOcclusionIsClearlyVisibleAtFullStrength() {
        Planes corner = corner();
        Vector3f fwd = forward(PITCH), up = up(PITCH);
        float creaseSum = 0, blockSum = 0;
        int creaseN = 0, blockN = 0;
        for (int y = 0; y < HH; y++) {
            for (int x = 20; x < HW - 20; x++) {
                if (corner.depth(x, y) <= 0 || !corner.normal(x, y).equals(up)) continue;
                float d = WALL - corner.point(x, y).dot(fwd);
                if (d < 0.25f) {
                    creaseSum += corner.ao(x, y);
                    creaseN++;
                } else if (d >= 1 && d < 1.5f) {
                    blockSum += corner.ao(x, y);
                    blockN++;
                }
            }
        }
        float crease = creaseSum / creaseN, block = blockSum / blockN;
        assertTrue(crease > 0.35f && crease < 0.6f, "crease " + crease);
        assertTrue(block < 0.9f, "a block from the wall " + block);
        //A one-block-wide, one-block-deep gap (between leaf blocks, a block step) seen from 10 blocks above
        float eye = 10;
        Vector3f ex = new Vector3f(1, 0, 0);
        Planes gap = new Planes(List.of(new Plane(up, -eye, p -> Math.abs(p.x) >= 0.5f), new Plane(up, -eye - 1, p -> Math.abs(p.x) < 0.5f),
                new Plane(ex, -0.5f, p -> p.dot(up) < -eye && p.dot(up) > -eye - 1),
                new Plane(new Vector3f(ex).negate(), -0.5f, p -> p.dot(up) < -eye && p.dot(up) > -eye - 1)));
        float sum = 0;
        int n = 0;
        for (int y = 0; y < HH; y++) {
            for (int x = 0; x < HW; x++) {
                if (gap.depth(x, y) <= 0 || !gap.normal(x, y).equals(up) || Math.abs(gap.point(x, y).x) >= 0.5f) continue;
                if (Math.abs(gap.point(x, y).dot(fwd)) > 30) continue;
                sum += gap.ao(x, y);
                n++;
            }
        }
        assertTrue(n > 100);
        assertTrue(sum / n > 0.3f && sum / n < 0.6f, "gap floor " + sum / n);
    }

    /** Review round 1: depth noise of about 1e-3 of the distance (the depth's precision far out) raises no horizon on a plane. */
    @Test
    void aPlaneWithNoisyDepthStaysOpen() {
        Planes plane = floor();
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < HH; y++) for (int x = 0; x < HW; x++) plane.depth[y][x] *= 1 + 1e-3f * (2 * random.nextFloat() - 1);
        for (int y = 0; y < HH; y++) {
            for (int x = 0; x < HW; x++) {
                if (plane.depth(x, y) <= 0) continue;
                assertTrue(plane.ao(x, y) > 0.97f, "noisy plane at " + x + "," + y + ": " + plane.ao(x, y));
            }
        }
        //The bias is a share of the distance, mirrored
        assertTrue(OCCLUSION.contains("max(dot(delta, n) - AO_DEPTH_BIAS*d, 0.0)/len*aoFalloff(len, radius)"));
        //Water, ice, glass and slime hide nothing (W5's kind in the depth pass), and ao_blur normalises W5's half-precision normals
        assertTrue(DEPTH.contains("|| texelFetch(lightTex, q, 0).a != 0.0) {"));
        assertTrue(BLUR.contains("normalize(texelFetch(sunTex, q, 0).gba)") && BLUR.contains("normalize(texelFetch(sunTex, t, 0).gba)"));
    }

    /** No halos against what lies far behind: a foreground panel in front of a far wall, and in front of the sky. */
    @Test
    void edgesInFrontOfFarSurfacesAndTheSkyCastNoHalo() {
        Vector3f facing = new Vector3f(0, 0, 1);
        java.util.function.Predicate<Vector3f> panel = x -> Math.abs(x.x) < 1 && Math.abs(x.y) < 1;
        for (boolean sky : new boolean[]{false, true}) {
            List<Plane> planes = sky ? List.of(new Plane(facing, -5, panel))
                    : List.of(new Plane(facing, -5, panel), new Plane(facing, -30, x -> true));
            Planes s = new Planes(planes);
            int checked = 0;
            for (int y = 0; y < HH; y++) {
                for (int x = 0; x < HW; x++) {
                    if (s.depth(x, y) <= 0) continue;
                    assertTrue(s.ao(x, y) > 0.99f, (sky ? "sky " : "far wall ") + x + "," + y + ": " + s.ao(x, y));
                    checked++;
                }
            }
            assertTrue(checked > 100);
        }
    }

    /** No crawling: the blur window holds every pattern index once and the step offsets are a permutation, so nothing is left over. */
    @Test
    void theBlurWindowAveragesTheWholePattern() {
        int n = AmbientOcclusion.AO_PATTERN * AmbientOcclusion.AO_PATTERN;
        assertEquals(AmbientOcclusion.AO_PATTERN, 2 * AmbientOcclusion.AO_BLUR_RADIUS + 1);
        for (int qy = 1; qy < 9; qy++) {
            for (int qx = 1; qx < 9; qx++) {
                Set<Integer> seen = new HashSet<>();
                for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) seen.add(AmbientOcclusion.patternIndex(qx + i, qy + j));
                assertEquals(n, seen.size());
            }
        }
        Set<Float> rot = new HashSet<>(), jit = new HashSet<>();
        for (int k = 0; k < n; k++) {
            rot.add(AmbientOcclusion.rotation(k));
            jit.add(AmbientOcclusion.jitter(k));
            assertTrue(AmbientOcclusion.jitter(k) > 0 && AmbientOcclusion.jitter(k) < 1);
        }
        assertEquals(n, rot.size());
        assertEquals(n, jit.size());
        //On the corner the blurred crease is smooth: neighbours along the crease differ little
        Planes corner = corner();
        float[][] raw = new float[HH][HW];
        for (int y = 0; y < HH; y++) for (int x = 0; x < HW; x++) raw[y][x] = corner.ao(x, y);
        Vector3f fwd = forward(PITCH), up = up(PITCH);
        float worst = 0;
        for (int y = 1; y < HH - 1; y++) {
            for (int x = 20; x < HW - 21; x++) {
                if (corner.depth(x, y) <= 0 || !corner.normal(x, y).equals(up) || !corner.normal(x + 1, y).equals(up)) continue;
                if (WALL - corner.point(x, y).dot(fwd) > 1) continue;
                worst = Math.max(worst, Math.abs(AmbientOcclusion.blur(corner, raw, x, y)[0] - AmbientOcclusion.blur(corner, raw, x + 1, y)[0]));
            }
        }
        assertTrue(worst < 0.05f, "along the crease " + worst);
    }

    /** Sky and translucent texels are 1 and no neighbour; plant texels take the ground around them. */
    @Test
    void theBlurKeepsSkyWaterAndPlantsApart() {
        Planes plane = floor();
        float[][] raw = new float[HH][HW];
        for (float[] row : raw) java.util.Arrays.fill(row, 0.5f);
        int x = HW / 2, y = HH / 2;
        raw[y][x] = AmbientOcclusion.AO_NONE;
        assertArrayEquals(new float[]{1, plane.depth(x, y)}, AmbientOcclusion.blur(plane, raw, x, y), 1e-6f);
        assertEquals(0.5f, AmbientOcclusion.blur(plane, raw, x + 1, y)[0], 1e-6f, "the 1 does not lighten its neighbour");
        raw[y][x] = AmbientOcclusion.AO_TAKE_NEIGHBOURS;
        assertEquals(0.5f, AmbientOcclusion.blur(plane, raw, x, y)[0], 1e-6f);
        assertEquals(-3f, AmbientOcclusion.depth(new Vector3f(0, 0, -3), true));
        assertEquals(0f, AmbientOcclusion.depth(null, false));
        assertTrue(AmbientOcclusion.AO_TAKE_NEIGHBOURS < 0 && AmbientOcclusion.AO_NONE < AmbientOcclusion.AO_TAKE_NEIGHBOURS);
    }

    // ---- brief test 2 ----

    @Test
    void oddSizesAreCoveredToTheLastRowAndColumn() throws Exception {
        int[][] sizes = {{1, 1}, {1, 2}, {3, 1}, {5, 7}, {2561, 3}, {3, 1441}, {2559, 1439}, {2560, 1440}, {1921, 1081}};
        for (int[] s : sizes) {
            for (int axis = 0; axis < 2; axis++) {
                int full = s[axis], half = AmbientOcclusion.halfSize(full);
                assertEquals((full + 1) / 2, half, "boundary 4: ceil(full / 2)");
                assertTrue(AmbientOcclusion.groups(half) * 8 >= half, "dispatch covers " + half);
                //Every texel stands for a pixel inside the image
                assertTrue(2 * (half - 1) <= full - 1);
                //Every full pixel reads texels inside the half image, the last row and column included
                boolean[] read = new boolean[half];
                for (int p = 0; p < full; p++) {
                    int q0 = p >> 1;
                    assertTrue(q0 < half, full + ": pixel " + p);
                    for (int i = 0; i <= (p & 1); i++) read[Math.min(q0 + i, half - 1)] = true;
                }
                for (int q = 0; q < half; q++) assertTrue(read[q], full + ": texel " + q);
            }
            //The upsampling of an image whose every texel is geometry returns the texels' values for every pixel
            int hw = AmbientOcclusion.halfSize(s[0]), hh = AmbientOcclusion.halfSize(s[1]);
            AmbientOcclusion.Texels t = new AmbientOcclusion.Texels() {
                public int width() { return hw; }

                public int height() { return hh; }

                public float[] at(int qx, int qy) {
                    assertTrue(qx >= 0 && qy >= 0 && qx < hw && qy < hh);
                    return new float[]{0.5f, 10};
                }
            };
            for (int py = 0; py < s[1]; py += Math.max(1, s[1] / 50)) {
                for (int px = 0; px < s[0]; px++) assertEquals(0.5f, AmbientOcclusion.at(t, px, py, 10), 1e-6f);
            }
            assertEquals(0.5f, AmbientOcclusion.at(t, s[0] - 1, s[1] - 1, 10), 1e-6f);
        }
        //The passes stop outside their image and the dispatcher sizes the images and dispatches with the same functions
        for (String comp : new String[]{DEPTH, OCCLUSION, BLUR}) assertTrue(comp.contains("if (any(greaterThanEqual(q, size))) {\n        return;"));
        assertTrue(DEPTH.contains("ivec2 p = min(2*q, full - 1);"));
        List<String> size = Bytecode.calls("net/vulkanmod/visuals/render/VkAmbientOcclusion", "ensureSize");
        assertEquals(2, Bytecode.count(size, "AmbientOcclusion.halfSize"), size.toString());
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/render/VkAmbientOcclusion", "compute").contains("AmbientOcclusion.groups"));
        assertTrue(GLSL.contains("vec4 t = texelFetch(aoTex, min(q0 + ivec2(i, j), size - 1), 0);"));
    }

    // ---- brief test 3 ----

    @Test
    void theOptionItemExistsOnceWithBothLabelsAndReachesSlot10X() throws Exception {
        int found = 0;
        VisualsOptionLayout.Item item = null;
        for (var page : VisualsOptionLayout.PAGES) {
            for (var block : page.blocks()) {
                for (var i : block.items()) {
                    if (i.key().equals("ao_strength")) {
                        found++;
                        item = i;
                        assertEquals("lighting", page.key());
                    }
                }
            }
        }
        assertEquals(1, found);
        assertEquals(VisualsOptionLayout.Kind.PERCENT, item.kind());
        assertEquals(0, item.min());
        assertEquals(100, item.max());
        for (String lang : List.of("en_us", "ko_kr")) {
            JsonObject json;
            try (InputStream in = AmbientOcclusionTest.class.getResourceAsStream("/assets/vulkanmod/lang/" + lang + ".json")) {
                assertNotNull(in);
                json = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            }
            assertFalse(json.get(PostProcessSettings.KEY_PREFIX + "ao_strength").getAsString().isBlank(), lang);
            assertFalse(json.get(PostProcessSettings.KEY_PREFIX + "ao_strength.tooltip").getAsString().isBlank(), lang);
        }
        //The setting through the item, the settings, the lighting values and the clean writer to the composite's slot 10 .x
        VisualsConfig c = new VisualsConfig();
        VisualsOptionLayout.set(c, item, 40);
        assertEquals(40, c.aoStrength);
        PostProcessSettings s = PostProcessSettings.from(c);
        LightingUniforms.Values v = LightingUniforms.compute(s, LightingChoice.of(SERIAL, s, true, true, false, true), frame(), SERIAL, false, 1, true);
        assertTrue(AmbientOcclusion.runs(v));
        VulkanShaderMeta meta = meta("postSceneCopy");
        LooseUniformWriter w = new LooseUniformWriter(meta);
        v.writeCopy(w);
        int offset = meta.push().stream().filter(m -> m.location() == LightingUniforms.COPY_LIGHTING).findFirst().orElseThrow().offset();
        ByteBuffer b = w.buffer();
        assertEquals(0.4f, b.getFloat(offset), 1e-6f);
        //and the composite uses that .x as the strength
        assertTrue(COMPOSITE.contains("float occlusion = aoStrengthApplied(aoAt(aoTex, p, dist), lighting.x);"));
    }

    // ---- brief test 4 ----

    @Test
    void offOrAtZeroThePassesDoNotRunAndTheImageIsUnchanged() throws Exception {
        VisualsConfig c = new VisualsConfig();
        PostProcessSettings on = PostProcessSettings.from(c);
        assertTrue(AmbientOcclusion.runs(LightingUniforms.compute(on, LightingChoice.of(SERIAL, on, true, true, false, true), frame(), SERIAL, false, 1, true)));
        c.aoStrength = 0;
        PostProcessSettings zero = PostProcessSettings.from(c);
        assertFalse(AmbientOcclusion.runs(LightingUniforms.compute(zero, LightingChoice.of(SERIAL, zero, true, true, false, true), frame(), SERIAL, false, 1, true)));
        c.aoStrength = 100;
        c.enabled = false;
        PostProcessSettings off = PostProcessSettings.from(c);
        assertFalse(AmbientOcclusion.runs(LightingUniforms.compute(off, LightingChoice.of(SERIAL, off, true, true, false, true), frame(), SERIAL, false, 1, true)));
        assertFalse(AmbientOcclusion.runs(LightingUniforms.off()));
        assertFalse(AmbientOcclusion.runs(null));
        //The dispatcher decides in prepare with runs(); compute dispatches only then (else it only moves the image to the read layout)
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/render/VkAmbientOcclusion", "prepare").contains("AmbientOcclusion.runs"));
        List<String> post = Bytecode.calls("net/vulkanmod/visuals/render/VkPostProcess", "compute");
        assertTrue(Bytecode.inOrder(post, List.of("VkPostProcess.computeLighting", "VkGpuTimer.mark", "VkAmbientOcclusion.compute", "VkGpuTimer.mark")), post.toString());
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/render/VkPostProcess", "prepare").contains("VkAmbientOcclusion.prepare"));
        //The composite reads the image only when it ran and leaves the light as it was otherwise (ao = 1)
        //Session 9: the PBR build also takes the material AO (pbrAo); every other build as before
        assertTrue(COMPOSITE.contains("vec3 ao = vec3(1.0);\n#ifdef PBR\n") && COMPOSITE.contains("#else\n        if (passRan && lighting.x > 0.0) {"));
        assertEquals(2, count(COMPOSITE, "aoAt(aoTex"));//session 9: once in the PBR build, once in every other
        assertTrue(COMPOSITE.contains("lit = relit*perLight*ao;"));//2026-10-23: relit = scene*gain, or the albedo at the new light
        assertFalse(COMPOSITE.contains("lt.x"), "W5's AO channel is no longer read");
    }

    // ---- what the composite darkens ----

    @Test
    void theCompositeOccludesOnlyTheSkyLightsAmbientPartAndTheFloor() {
        assertTrue(COMPOSITE.contains("vec3 occludable = share*sunShadingAt(0.0, skyLight, sunWeight, lighting.w)*faceUnshade(shade, sunWeight)*tint*se\n"
                + "                        + blockShare*floorShare;"));
        assertTrue(COMPOSITE.contains("ao = aoOccludedLight(perLight, occludable, occlusion);"));
        //The ambient part of W5's factor is the factor without direct sun; the direct part is never taken
        float ambient = LightingMath.sunShadingAt(0, 1, 1, 1), sunlit = LightingMath.sunShadingAt(1, 1, 1, 1);
        float[] f = AmbientOcclusion.occludedLight(new float[]{sunlit, sunlit, sunlit}, new float[]{ambient, ambient, ambient}, 0);
        assertEquals((sunlit - ambient) / sunlit, f[0], 1e-6f);
        assertTrue(f[0] > 0.6f, "full sun keeps its direct part");
        //Torch light: a block-lit pixel only loses its share of the lightmap floor
        float[] floor = AmbientOcclusion.floorShare(new float[]{0.02f, 0.02f, 0.02f}, new float[]{0.6f, 0.5f, 0.3f}, new float[]{0.02f, 0.02f, 0.02f});
        for (float v : floor) assertTrue(v > 0 && v < 0.07f);
        assertArrayEquals(new float[]{0, 0, 0}, AmbientOcclusion.floorShare(new float[3], new float[3], new float[3]));
        //The sky is never darkened: sky pixels return before the lighting
        assertTrue(COMPOSITE.indexOf("if (!scenePositionFrom(p, size, view, fromLod)) {") < COMPOSITE.indexOf("aoAt(aoTex"));
    }

    // ---- wiring ----

    private static VulkanShaderMeta meta(String program) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    @Test
    void theBindingsAndUniformsMatchTheDispatcher() throws Exception {
        ClassNode post = Bytecode.read("net/vulkanmod/visuals/render/VkPostProcess");
        Integer copyAo = null;
        for (FieldNode f : post.fields) if (f.name.equals("COPY_AO")) copyAo = (Integer) f.value;
        assertEquals(13, copyAo);
        assertTrue(meta("postSceneCopy").bindings().stream().anyMatch(b -> b.binding() == 13 && b.kind() == VulkanShaderMeta.Kind.SAMPLER));
        assertTrue(COMPOSITE.contains("layout(binding = 13) uniform sampler2D aoTex;"));
        //Each pass: the storage image at DST and samplers at the dispatcher's bindings
        int[][] samplers = {{VkAmbientOcclusion.DEPTH_MAIN_COLOUR, VkAmbientOcclusion.DEPTH_MAIN_DEPTH, VkAmbientOcclusion.DEPTH_LOD_DEPTH, VkAmbientOcclusion.DEPTH_LIGHT},
                {VkAmbientOcclusion.OCCLUSION_DEPTH, VkAmbientOcclusion.OCCLUSION_SUN, VkAmbientOcclusion.OCCLUSION_LIGHT},
                {VkAmbientOcclusion.BLUR_RAW, VkAmbientOcclusion.BLUR_DEPTH, VkAmbientOcclusion.BLUR_SUN}};
        String[] programs = {"postAoDepth", "postAo", "postAoBlur"};
        for (int i = 0; i < 3; i++) {
            Set<String> have = new HashSet<>(), want = new HashSet<>();
            for (VulkanShaderMeta.Binding b : meta(programs[i]).bindings()) {
                if (b.kind() != VulkanShaderMeta.Kind.UNIFORM_BUFFER) have.add(b.binding() + ":" + b.kind());
            }
            want.add(VkAmbientOcclusion.DST + ":" + VulkanShaderMeta.Kind.IMAGE);
            for (int s : samplers[i]) want.add(s + ":" + VulkanShaderMeta.Kind.SAMPLER);
            assertEquals(want, have, programs[i]);
        }
        assertEquals(0, meta("postAoBlur").pushSize());
        LightingUniforms.Values v = LightingUniforms.compute(PostProcessSettings.from(new VisualsConfig()),
                LightingChoice.of(SERIAL, PostProcessSettings.from(new VisualsConfig()), true, true, false, true), frame(), SERIAL, true, 1, true);
        LooseUniformWriter depth = new LooseUniformWriter(meta("postAoDepth"));
        for (int loc = 0; loc <= AmbientOcclusion.DEPTH_SIZE; loc++) assertTrue(depth.has(loc), "postAoDepth location " + loc);
        AmbientOcclusion.writeDepth(depth, v, W, H);
        LooseUniformWriter occlusion = new LooseUniformWriter(meta("postAo"));
        for (int loc = 0; loc <= AmbientOcclusion.OCCLUSION_SIZE; loc++) assertTrue(occlusion.has(loc), "postAo location " + loc);
        assertFalse(occlusion.inUniformBuffer(), "fits in push constants");
        AmbientOcclusion.writeOcclusion(occlusion, v, W, H);
        //The texels a block spans at distance 1 at half resolution: the projection's y scale x height / 4
        assertEquals((float) (1 / Math.tan(Math.toRadians(35))) * H / 4, AmbientOcclusion.texelsPerBlock(PROJ, H), 1e-3f);
        //The interval the dispatcher measures
        assertTrue(Bytecode.read("net/vulkanmod/visuals/render/VkPostProcess").methods.stream().filter(m -> m.name.equals("compute"))
                .anyMatch(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
                        .anyMatch(insn -> insn instanceof org.objectweb.asm.tree.LdcInsnNode l && "post.ao".equals(l.cst))));
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        int found = 0;
        String all = GLSL + DEPTH + OCCLUSION + BLUR;
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(all);
        while (m.find()) {
            Field f = AmbientOcclusion.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 23, "constants found: " + found);
        for (Field f : AmbientOcclusion.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class) || !f.getName().startsWith("AO_")) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(all).find(), f.getName() + " missing in the shaders");
        }
        //The passes and the composite share ao.glsl's functions and define none of them again
        for (String text : new String[]{DEPTH, OCCLUSION, BLUR, COMPOSITE}) assertTrue(text.contains("#import <vulkanmod:visuals/post/ao.glsl>"));
        for (String own : new String[]{"float aoAt(", "vec3 aoRay(", "float aoArc(", "vec3 aoOccludedLight("}) {
            assertEquals(1, count(GLSL + DEPTH + OCCLUSION + BLUR + COMPOSITE, own), own);
        }
    }

    // ---- helpers ----

    private static final long SERIAL = 11;

    private static AtmosphereFrame frame() {
        Matrix4f gl = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 2048);
        return AtmosphereFrame.of(SERIAL, gl, new Matrix4f(gl), new Matrix4f(), 100, 63,
                new FogParameters(0.6f, 0.7f, 0.9f, 1f, -8, 1e6f, -8, 1e6f), 512, true, 0, 0, 0, null);
    }

    private static int count(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }
}
