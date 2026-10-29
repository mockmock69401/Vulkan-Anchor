package net.vulkanmod.visuals.lod;

import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.*;

class LodCompositeTest {
    /** Alpha blend over the frame, depth tested against and written into the main depth. */
    @Test
    void stateBlendsAndTestsAgainstTheMainDepth() {
        VkGraphicsProgram.State s = LodComposite.STATE;
        assertTrue(s.depthTest() && s.depthWrite() && s.blend());
        assertEquals(VK_COMPARE_OP_LESS_OR_EQUAL, s.depthCompare());
        assertEquals(VkGraphicsProgram.RGBA, s.colourWriteMask());
        assertEquals(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, s.topology());
    }

    /** No LOD over a pixel vanilla drew: the bounded state requests depth bounds [1, 1] and otherwise equals the plain one. */
    @Test
    void theBoundedStateRequestsDepthBoundsAtFar() {
        VkGraphicsProgram.State b = LodComposite.BOUNDED_STATE;
        assertTrue(b.depthBoundsAtFar());
        assertFalse(LodComposite.STATE.depthBoundsAtFar());
        assertEquals(LodComposite.STATE, new VkGraphicsProgram.State(b.depthTest(), b.depthWrite(), b.depthCompare(), b.colourWriteMask(), b.blend(),
                b.topology(), b.depthOnly(), b.light(), false));
        assertTrue(b.depthWrite(), "later passes still see the LOD depth");
        assertEquals(VK_COMPARE_OP_LESS_OR_EQUAL, b.depthCompare());
    }

    /** The pipeline writes the bounds [1, 1] when the state asks. */
    @Test
    void thePipelineSetsTheBounds() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java/net/vulkanmod/visuals/vk/VkGraphicsProgram.java");
        String code = java.nio.file.Files.readString(src);
        assertTrue(code.contains(".depthBoundsTestEnable(this.state.depthBoundsAtFar()).minDepthBounds(1.0f).maxDepthBounds(1.0f)"));
    }

    /** Every other program keeps the default: no depth bounds. */
    @Test
    void otherStatesDefaultToNoDepthBounds() {
        assertFalse(new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkGraphicsProgram.RGBA, true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP).depthBoundsAtFar());
        assertFalse(VkGraphicsProgram.State.depthOnly(VK_COMPARE_OP_LESS_OR_EQUAL, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).depthBoundsAtFar());
        assertFalse(new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkGraphicsProgram.RGBA, true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP).withLight().depthBoundsAtFar());
        assertTrue(LodComposite.BOUNDED_STATE.withLight().depthBoundsAtFar(), "withLight keeps the request");
    }

    /** Without the device feature the composite falls back to the depth test alone. */
    @Test
    void theFallbackIsDepthTestOnlyWithoutTheFeature() {
        assertEquals(LodComposite.BOUNDED_STATE, LodComposite.stateFor(true));
        assertEquals(LodComposite.STATE, LodComposite.stateFor(false));
    }

    @Test
    void theProgramIsRegisteredWithItsTwoStages() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(LodComposite.PROGRAM);
        assertEquals("lodComposite", p.name());
        assertEquals(2, p.stages().size());
        assertTrue(p.stages().get(0).id().endsWith("lod/lod_composite.vert"));
        assertTrue(p.stages().get(1).id().endsWith("lod/lod_composite.frag"));
    }

    /** The fragment stage declares the colour and depth samplers at the bindings the Java side binds, and the four uniforms. */
    @Test
    void theShaderMatchesTheBindingsAndLocations() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(LodComposite.PROGRAM);
        VulkanShaderMeta meta = GlslForVulkan.toVulkan(p.source(p.stages().get(1), ShaderSource.classpath()), p.defines()).meta();
        LooseUniformWriter w = new LooseUniformWriter(meta);
        //Two mat4 and two vec4 are more than the push constant budget: a uniform block, which the composite uploads
        assertTrue(w.inUniformBuffer());
        for (int location : List.of(LodCompositeUniforms.LOD_CLIP_TO_VIEW, LodCompositeUniforms.VIEW_TO_MAIN_CLIP, LodCompositeUniforms.FOG_COLOUR,
                LodCompositeUniforms.FOG_RANGE)) {
            assertTrue(w.has(location), "location " + location);
        }
        String frag = ShaderFiles.read("lod/lod_composite.frag");
        assertTrue(frag.contains("layout(binding = " + LodComposite.DEPTH_TEX + ") uniform sampler2D lodDepth;"));
        assertTrue(frag.contains("layout(binding = " + LodComposite.COLOUR_TEX + ") uniform sampler2D lodColour;"));
    }

    /** An empty pixel (the target's cleared depth) shows the frame; no other input is read. */
    @Test
    void theShaderSkipsEmptyPixelsAndHasNoBandOrFadeInputs() {
        String frag = ShaderFiles.read("lod/lod_composite.frag");
        assertTrue(frag.contains("if (depth >= 1.0) {"));
        assertTrue(frag.contains("gl_FragDepth = min(mainDepth, FAR_LIMIT);"));
        //2026-10-30 (audit): the clamp the shader writes is LodClamp's, and the post passes' threshold is steps below it, not equal
        assertTrue(frag.contains("const float FAR_LIMIT = 1.0 - 3.0/16777215.0;"));
        assertEquals(LodClamp.LOD_CLAMP_DEPTH, (float) (1.0 - 3.0 / 16777215.0), 0f);
        float d24ReadBack = 16777212f * (1f / 16777215f);
        assertTrue(d24ReadBack >= LodClamp.CLAMP_THRESHOLD && LodClamp.LOD_CLAMP_DEPTH - LodClamp.CLAMP_THRESHOLD >= 2.5f / 16777215f);
        for (String gone : List.of("vanillaDistance", "fadeGrid", "vanillaFade", "bandParams", "FadeGrid", "fadeParams")) {
            assertFalse(frag.contains(gone), gone);
        }
    }

    /** One draw only: colour and depth of the target, nothing of the band. */
    @Test
    void drawBindsTheTargetsDepthAndColour() throws Exception {
        List<String> getters = new ArrayList<>();
        for (MethodNode m : node().methods) {
            if (!m.name.equals("draw")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith("LodTarget")) getters.add(call.name);
            }
        }
        assertEquals(List.of("depth", "color"), getters);
    }

    /** The composite binds by render pass compatibility, not by the pass handle Reforged replaces on resize. */
    @Test
    void compositeBindsByRenderPassCompatibility() throws Exception {
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node().methods) {
            if (!m.name.equals("draw")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.name + call.desc);
            }
        }
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("boundRenderPassKey")), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("bind(Lorg/lwjgl/vulkan/VkCommandBuffer;JJ")), calls.toString());
    }

    /** A failure after the program is built frees the program and the buffer before rethrowing, and the sampler is created last. */
    @Test
    void aFailedBuildFreesWhatItHolds() throws Exception {
        for (MethodNode m : node().methods) {
            if (!m.name.equals("<init>")) continue;
            assertFalse(m.tryCatchBlocks.isEmpty(), "the buffer and sampler are built inside a try");
            List<String> calls = new ArrayList<>();
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
            assertTrue(calls.contains("VkGraphicsProgram.free"), calls.toString());
            assertTrue(calls.indexOf("VkSamplers.nearestClamp") > calls.indexOf("VkGpuBuffer.device"), calls.toString());
        }
    }

    private static ClassNode node() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = LodCompositeTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/lod/LodComposite.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }
}
