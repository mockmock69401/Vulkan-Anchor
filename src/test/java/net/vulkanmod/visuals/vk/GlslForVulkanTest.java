package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlslForVulkanTest {
    @Test
    void printfStatementsAreRemovedKeepingLineCount() {
        String src = "#version 460 core\nvoid main(){\n    printf(\"a %d (x)\", f(1, (2)));\n    int x = 1; printf(\"b\");\n}\n";
        String out = GlslForVulkan.stripPrintf(src);
        assertEquals("#version 460 core\nvoid main(){\n    \n    int x = 1; \n}\n", out);
        assertEquals(src.lines().count(), out.lines().count());
    }

    @Test
    void multiLinePrintfKeepsItsNewlines() {
        String out = GlslForVulkan.stripPrintf("a\nprintf(\"x\",\n  y);\nb\n");
        assertEquals("a\n\n\nb\n", out);
    }

    @Test
    void identifiersContainingPrintfAreKept() {
        assertEquals("myprintf(1);\nprintfOutputStruct.index;\n", GlslForVulkan.stripPrintf("myprintf(1);\nprintfOutputStruct.index;\n"));
    }

    @Test
    void vertexAndInstanceIdsKeepTheirGlMeaning() {
        //2026-10-30 (audit): no draw parameters needed (the visuals' instanced draws start at instance 0)
        String out = GlslForVulkan.vulkanBuiltins("#version 460 core\nvoid main(){ uint a = gl_VertexID + gl_InstanceID; }\n");
        assertEquals("#version 460 core\nvoid main(){ uint a = gl_VertexIndex + gl_InstanceIndex; }\n", out);
        assertEquals("#version 460 core\nvoid main(){ int b = gl_InstanceIndex; }\n",
                GlslForVulkan.vulkanBuiltins("#version 460 core\nvoid main(){ int b = gl_InstanceID + gl_BaseInstance; }\n"));
        assertEquals("#version 460 core\n#extension GL_ARB_shader_draw_parameters : require\nvoid main(){ int c = gl_BaseInstance; }\n",
                GlslForVulkan.vulkanBuiltins("#version 460 core\nvoid main(){ int c = gl_BaseInstance; }\n"));
    }

    @Test
    void drawParametersExtensionIsNotDuplicated() {
        String src = "#version 460 core\n#extension GL_ARB_shader_draw_parameters : require\nvoid main(){ uint a = gl_BaseInstance; }\n";
        String out = GlslForVulkan.vulkanBuiltins(src);
        assertEquals(1, out.split("GL_ARB_shader_draw_parameters", -1).length - 1);
    }

    @Test
    void depthRangeIsVulkansZeroToOne() {
        assertEquals("#version 460 core\nfloat d = 1.0 * x + 0.0 + 1.0;\n",
                GlslForVulkan.vulkanBuiltins("#version 460 core\nfloat d = gl_DepthRange.diff * x + gl_DepthRange.near + gl_DepthRange.far;\n"));
    }

    @Test
    void looseUniformsBecomeOnePushConstantBlockInLocationOrder() {
        String src = "#version 460 core\nlayout(location = 1) uniform vec2 b;\nlayout(location=0) uniform uint a;\nlayout(location = IDX) uniform mat4 m;\nvoid main(){}\n";
        GlslForVulkan.Converted c = GlslForVulkan.pushConstants(src, Map.of("IDX", "4"));
        assertEquals("#version 460 core\nlayout(push_constant) uniform VisualsPushConstants {\n    uint a;\n    vec2 b;\n    mat4 m;\n};\n\n\nvoid main(){}\n", c.source());
        assertEquals(List.of(
                new VulkanShaderMeta.PushMember("a", "uint", 0, 0),
                new VulkanShaderMeta.PushMember("b", "vec2", 1, 8),
                new VulkanShaderMeta.PushMember("m", "mat4", 4, 16)), c.meta().push());
        assertEquals(80, c.meta().pushSize());
    }

    @Test
    void opaqueUniformsAndBlocksAreNotPushConstants() {
        String src = "#version 460 core\nlayout(binding = 0) uniform sampler2D s;\nlayout(binding = 0, std140) uniform Scene { mat4 v; };\nvoid main(){}\n";
        GlslForVulkan.Converted c = GlslForVulkan.pushConstants(src, Map.of());
        assertEquals(src, c.source());
        assertEquals(0, c.meta().pushSize());
    }

    @Test
    void std430OffsetsForVec3AndMat3() {
        GlslForVulkan.Converted c = GlslForVulkan.pushConstants("#version 460 core\nlayout(location=0) uniform float f;\nlayout(location=1) uniform vec3 v;\nlayout(location=2) uniform mat3 r;\n", Map.of());
        assertEquals(List.of(0, 16, 32), c.meta().push().stream().map(VulkanShaderMeta.PushMember::offset).toList());
        assertEquals(80, c.meta().pushSize());
    }

    @Test
    void eachResourceKindGetsItsOwnSet() {
        String src = "#version 460 core\n"
                + "layout(binding = 0, std140) uniform SceneUniform { mat4 v; };\n"
                + "layout(binding = B, std430) restrict readonly buffer Quads { uint q[]; };\n"
                + "layout(binding = 0) uniform sampler2D tex;\n"
                + "layout(binding = 1, r32f) uniform restrict writeonly image2D mip;\n"
                + "uniform sampler2D noBinding;\n";
        GlslForVulkan.Converted c = GlslForVulkan.descriptorSets(src, Map.of("B", "0"));
        assertEquals("#version 460 core\n"
                + "layout(set = 1, binding = 0, std140) uniform SceneUniform { mat4 v; };\n"
                + "layout(set = 0, binding = B, std430) restrict readonly buffer Quads { uint q[]; };\n"
                + "layout(set = 2, binding = 0) uniform sampler2D tex;\n"
                + "layout(set = 3, binding = 1, r32f) uniform restrict writeonly image2D mip;\n"
                + "layout(set = 2, binding = 1) uniform sampler2D noBinding;\n", c.source());
        assertEquals(List.of(
                new VulkanShaderMeta.Binding(1, 0, VulkanShaderMeta.Kind.UNIFORM_BUFFER),
                new VulkanShaderMeta.Binding(0, 0, VulkanShaderMeta.Kind.STORAGE_BUFFER),
                new VulkanShaderMeta.Binding(2, 0, VulkanShaderMeta.Kind.SAMPLER),
                new VulkanShaderMeta.Binding(3, 1, VulkanShaderMeta.Kind.IMAGE),
                new VulkanShaderMeta.Binding(2, 1, VulkanShaderMeta.Kind.SAMPLER)), c.meta().bindings());
    }

    @Test
    void pushConstantBlockAndExplicitSetsAreLeftAlone() {
        String src = "#version 460 core\nlayout(push_constant) uniform VisualsPushConstants {\n    uint a;\n};\nlayout(set = 5, binding = 2) uniform sampler2D s;\n";
        assertEquals(src, GlslForVulkan.descriptorSets(src, Map.of()).source());
    }

    @Test
    void unresolvableBindingsAreLeftOutOfTheMetadata() {
        GlslForVulkan.Converted c = GlslForVulkan.descriptorSets("#version 460 core\n#ifdef X\nlayout(binding = X_BINDING, std430) buffer B { uint b[]; };\n#endif\n", Map.of());
        assertEquals(List.of(), c.meta().bindings());
        assertTrue(c.source().contains("layout(set = 0, binding = X_BINDING, std430) buffer B"));
    }

    /** Found by the Vulkan gate: "buffer" in a comment was taken for a declaration (sort_visibility.comp). */
    @Test
    void commentsAreBlankedKeepingLines() {
        assertEquals("a \nb \n c\n", GlslForVulkan.stripComments("a // buffer x\nb /* uniform\n y */ c\n"));
    }

    /** Found by the Vulkan gate: declarations in inactive #ifdef branches (ssao.comp, cmdgen.comp) were converted too. */
    @Test
    void inactiveBranchesAreBlankedKeepingLines() {
        String src = "#version 460 core\n#define A\n#ifdef A\nint a;\n#else\nint b;\n#endif\n#ifndef A\nint c;\n#endif\n#undef A\n#ifdef A\nint d;\n#endif\n";
        assertEquals("#version 460 core\n#define A\n\nint a;\n\n\n\n\n\n\n#undef A\n\n\n\n", GlslForVulkan.resolveConditionals(src));
    }

    @Test
    void nestedAndInactiveDefinesFollowThePreprocessor() {
        String src = "#ifdef X\n#define Y\n#ifdef Z\nint z;\n#else\nint q;\n#endif\n#endif\n#ifdef Y\nint y;\n#endif\nint w;\n";
        assertEquals("\n\n\n\n\n\n\n\n\n\n\nint w;\n", GlslForVulkan.resolveConditionals(src));
    }

    /** Found by the Vulkan gate: a macro qualifier between layout(...) and buffer hid the layout (cmdgen.comp VISIBILITY_ACCESS). */
    @Test
    void macroQualifierBetweenLayoutAndBufferKeepsTheLayout() {
        GlslForVulkan.Converted c = GlslForVulkan.descriptorSets(
                "#version 460 core\n#define ACCESS readonly\nlayout(binding = 6, std430) ACCESS restrict buffer Vis { uint v[]; };\n", Map.of());
        assertEquals("#version 460 core\n#define ACCESS readonly\nlayout(set = 0, binding = 6, std430) ACCESS restrict buffer Vis { uint v[]; };\n", c.source());
        assertEquals(List.of(new VulkanShaderMeta.Binding(0, 6, VulkanShaderMeta.Kind.STORAGE_BUFFER)), c.meta().bindings());
    }

    @Test
    void implicitBindingsSkipExplicitOnesDeclaredLater() {
        GlslForVulkan.Converted c = GlslForVulkan.descriptorSets("#version 460 core\nuniform sampler2D a;\nlayout(binding = 0) uniform sampler2D b;\n", Map.of());
        assertEquals("#version 460 core\nlayout(set = 2, binding = 1) uniform sampler2D a;\nlayout(set = 2, binding = 0) uniform sampler2D b;\n", c.source());
    }

    /** Found by the Vulkan gate: SPIR-V needs a location on every user in/out (fullscreen.vert, cull/raster.*). */
    @Test
    void implicitVaryingsGetTheNextFreeLocation() {
        String src = "#version 460 core\nlayout(location = 0) in vec3 pos;\nflat out uint id;\nout vec2 uv;\nlayout(location = 0) out vec4 c;\n";
        assertEquals("#version 460 core\nlayout(location = 0) in vec3 pos;\nlayout(location = 1) flat out uint id;\nlayout(location = 2) out vec2 uv;\nlayout(location = 0) out vec4 c;\n",
                GlslForVulkan.varyingLocations(src));
    }

    /** Found by the Vulkan gate: finalBlit needs 160 bytes, above Vulkan's guaranteed 128 for push constants. */
    @Test
    void oversizedLooseUniformsGoToAUniformBlockInstead() {
        GlslForVulkan.Converted c = GlslForVulkan.pushConstants(
                "#version 460 core\nlayout(location = 0) uniform mat4 a;\nlayout(location = 1) uniform mat4 b;\nlayout(location = 2) uniform vec4 d;\n", Map.of());
        assertTrue(c.source().startsWith("#version 460 core\nlayout(std140) uniform VisualsLooseUniforms {\n    mat4 a;\n    mat4 b;\n    vec4 d;\n};"), c.source());
        assertTrue(c.meta().pushInUniformBuffer());
        assertEquals(144, c.meta().pushSize());
    }

    /** Review I5: only #ifdef/#ifndef/#else/#endif are evaluated; anything else must fail loudly, not mis-nest silently. */
    @Test
    void unsupportedOrUnbalancedConditionalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GlslForVulkan.resolveConditionals("#if 1\nint a;\n#endif\n"));
        assertThrows(IllegalArgumentException.class, () -> GlslForVulkan.resolveConditionals("#ifdef A\n#elif B\n#endif\n"));
        assertThrows(IllegalArgumentException.class, () -> GlslForVulkan.resolveConditionals("int a;\n#endif\n"));
    }

    /** Review I5: GL drivers predefine their extension macros; quads3.vert picks its 64-bit quad path from one. */
    @Test
    void predefinedMacrosTakeTheirBranch() {
        assertEquals("\n#define Q\n\n", GlslForVulkan.resolveConditionals("#ifdef GL_ARB_gpu_shader_int64\n#define Q\n#endif\n", Set.of("GL_ARB_gpu_shader_int64")));
    }
    /** The final draws' vertex shader (a strip of four vertices) uses Vulkan's vertex index and passes nothing to the fragment stage. */
    @Test
    void theFullScreenVertexShaderConvertsToTheVertexIndex() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get("postFinal");
        GlslForVulkan.Converted c = GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines());
        assertTrue(c.source().contains("gl_VertexIndex"), c.source());
        assertFalse(c.source().contains("gl_VertexID"), c.source());
        assertEquals(0, c.meta().pushConstantBytes());
    }
}
