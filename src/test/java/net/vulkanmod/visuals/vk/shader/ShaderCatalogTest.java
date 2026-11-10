package net.vulkanmod.visuals.vk.shader;

import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderCatalogTest {
    private static final Set<String> USED_STAGES = Set.of(
            "vulkanmod:visuals/post/fullscreen.vert", "vulkanmod:visuals/post/depth_capture.comp", "vulkanmod:visuals/post/reflection.comp",
            "vulkanmod:visuals/post/lighting.comp", "vulkanmod:visuals/post/lighting_blur.comp", "vulkanmod:visuals/post/scene_copy.comp",
            "vulkanmod:visuals/post/ao_depth.comp", "vulkanmod:visuals/post/ao.comp", "vulkanmod:visuals/post/ao_blur.comp",
            "vulkanmod:visuals/post/bloom.comp", "vulkanmod:visuals/post/post_final.frag", "vulkanmod:visuals/post/shafts.comp",
            "vulkanmod:visuals/post/volumetric.comp", "vulkanmod:visuals/post/volumetric_blur.comp",
            "vulkanmod:visuals/shadow/vanilla.vert", "vulkanmod:visuals/shadow/vanilla.frag", "vulkanmod:visuals/shadow/entity.vert", "vulkanmod:visuals/shadow/entity.frag", "vulkanmod:visuals/shadow/sublevel.vert", "vulkanmod:visuals/post/colour_capture.comp", "vulkanmod:visuals/post/edge_cover.vert", "vulkanmod:visuals/post/edge_cover.frag",
            "vulkanmod:visuals/post/post_ldr.comp", "vulkanmod:visuals/post/smaa_edges.comp", "vulkanmod:visuals/post/smaa_weights.comp",
            "vulkanmod:visuals/post/smaa_blend.comp", "vulkanmod:visuals/post/post_blit.frag", "vulkanmod:visuals/post/weather.comp", "vulkanmod:visuals/post/post_taa.comp",
            "vulkanmod:visuals/lod/lod_composite.vert", "vulkanmod:visuals/lod/lod_composite.frag",
            "vulkanmod:visuals/dh/terrain.vert", "vulkanmod:visuals/dh/terrain.frag",
            "vulkanmod:visuals/dh/shadow.vert", "vulkanmod:visuals/dh/shadow.frag",
            "vulkanmod:visuals/fg/fg_capture.comp", "vulkanmod:visuals/fg/fg_splat.comp", "vulkanmod:visuals/fg/fg_resolve.comp",
            "vulkanmod:visuals/fg/fg_fill.comp", "vulkanmod:visuals/fg/fg_compose.frag",
            "vulkanmod:visuals/fsr/fsr_inputs.comp", "vulkanmod:visuals/fsr/fsr_hudless.comp");

    @Test
    void coversEveryStageTheVisualsUse() {
        Set<String> covered = new TreeSet<>();
        ShaderCatalog.visuals().all().forEach(p -> p.stages().forEach(s -> covered.add(s.id())));
        assertEquals(new TreeSet<>(USED_STAGES), covered);
        assertEquals(48, ShaderCatalog.visuals().all().size());//2026-11-10 (session 9): the composite's two and the reflection's four PBR builds; (session 8): the composite's and the reflection's TINTED builds. 2026-11-10 (session 7): the reflection's LEAN build. 2026-11-09 (session 6): the shader weather. 2026-11-07: the sub-level shadow caster. 2026-10-29: the entity shadow caster and the colour copy; FSR's inputs and HUD-less copy (2), frame generation (5), the post-process (W10's three AO passes included), SMAA, the shadow caster, the edge cover, the LOD composite, DH's terrain and DH's shadow casters
    }


    /**
     * 2026-10-30 (audit): Vulkan matches varyings by location, which GlslForVulkan gives in declaration order, so every program's
     * fragment stage takes its implicit inputs in the order its vertex stage declares the outputs (GL matched them by name).
     */
    @Test
    void everyProgramsVaryingsMatchInOrder() {
        int checked = 0;
        for (ShaderCatalog.Program p : ShaderCatalog.visuals().all()) {
            ShaderCatalog.Stage vert = null, frag = null;
            for (ShaderCatalog.Stage s : p.stages()) {
                if (s.kind() == ShaderCatalog.StageKind.VERTEX) vert = s;
                if (s.kind() == ShaderCatalog.StageKind.FRAGMENT) frag = s;
            }
            if (vert == null || frag == null) continue;
            List<String> outs = GlslForVulkan.implicitVaryings(GlslForVulkan.stripComments(p.source(vert, ShaderSource.classpath())), "out");
            List<String> ins = GlslForVulkan.implicitVaryings(GlslForVulkan.stripComments(p.source(frag, ShaderSource.classpath())), "in");
            //the fragment stage's own outputs are its colours; only the inputs pair with the vertex outputs
            assertEquals(outs.subList(0, Math.min(outs.size(), ins.size())), ins, p.name());
            checked++;
        }
        assertTrue(checked >= 5, "graphics programs checked: " + checked);
    }

    @Test
    void matricesAndArraysTakeAsManyLocationsAsTheyFill() {
        String out = GlslForVulkan.varyingLocations("out mat3 m;\nout vec2 a[2];\nout vec4 c;\n");
        assertTrue(out.contains("layout(location = 0) out mat3 m;"), out);
        assertTrue(out.contains("layout(location = 3) out vec2 a[2];"), out);
        assertTrue(out.contains("layout(location = 5) out vec4 c;"), out);
    }

    /** Review I4: Shader.Builder.compile always adds IS_INTEL / IS_WINDOWS for the running machine. */
    @Test
    void platformDefinesFollowShaderBuilderCompile() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get("postLighting");
        assertTrue(p.withPlatform(new ShaderCatalog.Platform(true, false)).defines().containsKey("IS_INTEL"));
        assertFalse(p.withPlatform(new ShaderCatalog.Platform(true, false)).defines().containsKey("IS_WINDOWS"));
        assertTrue(p.withPlatform(new ShaderCatalog.Platform(false, true)).defines().containsKey("IS_WINDOWS"));
        assertEquals(4, ShaderCatalog.Platform.ALL.size());
    }


    /**
     * shaderc's SPIR-V-for-OpenGL target has no gl_DepthRange (the GL driver compiler does); stand in a constant with
     * GL's default range so the rest of the source is still checked.
     */
    private static String withDepthRangeStandIn(String source) {
        if (!source.contains("gl_DepthRange")) return source;
        int firstLine = source.indexOf('\n') + 1;
        return source.substring(0, firstLine)
                + "struct TestDepthRange { float near; float far; float diff; };\n"
                + "const TestDepthRange testDepthRange = TestDepthRange(0.0, 1.0, 1.0);\n"
                + source.substring(firstLine).replace("gl_DepthRange", "testDepthRange");
    }

    /** The catalog reproduces the GL path: every program compiles for OpenGL, as it does in game. */
    @Test
    void everyProgramCompilesForOpenGl() {
        List<String> failures = new ArrayList<>();
        for (ShaderCatalog.Program p : ShaderCatalog.visuals().allVariants()) {
            for (ShaderCatalog.Stage s : p.stages()) {
                String err = ShadercTestUtil.compile(p.name() + ":" + s.id(), withDepthRangeStandIn(p.source(s, ShaderSource.classpath())), ShadercTestUtil.kind(s.kind()), false);
                if (err != null) failures.add(p.name() + " " + s.id() + "\n" + err.lines().limit(5).reduce("", (a, b) -> a + "\n  " + b));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n\n", failures));
    }



    /** P7e-1 design 3.2: the depth copy moves the value unchanged, so the lighting pass can compare it exactly. */
    @Test
    void theDepthCaptureCopiesTheValueAsItIs() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get("postDepthCapture");
        String source = p.source(p.stages().get(0), ShaderSource.classpath());
        assertTrue(source.contains("layout(binding = 0, r32f) uniform writeonly restrict image2D depthOut;"), source);
        assertTrue(source.contains("imageStore(depthOut, p, vec4(texelFetch(depthIn, p, 0).r));"), source);
    }

    /** P7h: clouds.glsl's constants are CloudMath's. */
    @Test
    void cloudConstantsMatchTheJavaMirror() {
        String clouds = ShaderFiles.read("post/clouds.glsl");
        java.util.Map<String, Float> expected = new java.util.HashMap<>(java.util.Map.of("CLOUD_CELL", CloudMath.CLOUD_CELL, "CLOUD_ROUND", CloudMath.CLOUD_ROUND,
                "CLOUD_EDGE", CloudMath.CLOUD_EDGE, "CLOUD_SOFT", CloudMath.CLOUD_SOFT, "CLOUD_STEP", CloudMath.CLOUD_STEP, "CLOUD_DENSITY", CloudMath.CLOUD_DENSITY,
                "CLOUD_LIGHT_NEAR", CloudMath.CLOUD_LIGHT_NEAR, "CLOUD_LIGHT_FAR", CloudMath.CLOUD_LIGHT_FAR, "CLOUD_SHADE_K", CloudMath.CLOUD_SHADE_K,
                "CLOUD_SHADE_MIN", CloudMath.CLOUD_SHADE_MIN));
        expected.putAll(java.util.Map.of("CLOUD_FADE_START", CloudMath.CLOUD_FADE_START, "CLOUD_MIN_HORIZONTAL", CloudMath.CLOUD_MIN_HORIZONTAL,
                "CLOUD_MAP", (float) CloudMath.MAP, "CLOUD_SHADE_LEVEL", CloudMath.CLOUD_SHADE_LEVEL, "CLOUD_ALBEDO", CloudMath.CLOUD_ALBEDO));
        for (var e : expected.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("const float " + e.getKey() + " = ([0-9.]+);").matcher(clouds);
            assertTrue(m.find(), e.getKey());
            assertEquals(e.getValue(), Float.parseFloat(m.group(1)), 1e-7f, e.getKey());
        }
        assertTrue(clouds.contains("const int CLOUD_MIN_SAMPLES = " + CloudMath.CLOUD_MIN_SAMPLES + ";"), clouds);
        assertTrue(clouds.contains("const int CLOUD_MAX_SAMPLES = " + CloudMath.CLOUD_MAX_SAMPLES + ";"), clouds);
        for (String k : java.util.List.of("vec2 u = (cloudOffset.xy + xz)/CLOUD_CELL - 0.5;", "float v = smoothstep(0.0, CLOUD_EDGE, min(h, cloudShape.y - h));",
                "t1 = min(t1, cloudShape.z/max(length(dir.xz), CLOUD_MIN_HORIZONTAL));", "float a = (1.0 - exp(-d*CLOUD_DENSITY*dt))*fade;",
                "light += w*mix(shadeMin, 1.0, shade);",
                "float shadeMin = mix(1.0, CLOUD_SHADE_MIN, smoothstep(0.0, CLOUD_SHADE_LEVEL, abs(lightDir.y)));")) {
            assertTrue(clouds.contains(k), k);
        }
    }
}
