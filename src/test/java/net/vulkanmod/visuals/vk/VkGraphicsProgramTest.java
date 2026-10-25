package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.*;

class VkGraphicsProgramTest {
    /** Task 5b: culling stays off unless a state asks for back faces culled (GL's front face in a positive-viewport pass: clockwise). */
    @Test
    void backFaceCullingIsOffUnlessAsked() {
        VkGraphicsProgram.State plain = new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkGraphicsProgram.RGBA, false,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        assertArrayEquals(new int[]{VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE}, VkGraphicsProgram.rasterCull(plain));
        assertArrayEquals(new int[]{VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE},
                VkGraphicsProgram.rasterCull(VkGraphicsProgram.State.depthOnly(VK_COMPARE_OP_LESS_OR_EQUAL, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)));
        VkGraphicsProgram.State culled = plain.withBackFaceCulling();
        assertArrayEquals(new int[]{VK_CULL_MODE_BACK_BIT, VK_FRONT_FACE_CLOCKWISE}, VkGraphicsProgram.rasterCull(culled));
        assertTrue(culled.withLight().cullBackFaces(), "kept by withLight");
        assertTrue(culled.withDepthBoundsAtFar().cullBackFaces(), "kept by withDepthBoundsAtFar");
        assertFalse(plain.withLight().cullBackFaces());
    }

    @Test
    void rejectsABindingWhoseKindDiffersBetweenStages() {
        VulkanShaderMeta vert = new VulkanShaderMeta(List.of(), 0, List.of(new VulkanShaderMeta.Binding(0, 1, VulkanShaderMeta.Kind.STORAGE_BUFFER)));
        VulkanShaderMeta frag = new VulkanShaderMeta(List.of(), 0, List.of(new VulkanShaderMeta.Binding(0, 1, VulkanShaderMeta.Kind.UNIFORM_BUFFER)));
        assertThrows(IllegalArgumentException.class, () -> VkGraphicsProgram.mergeBindings(vert, frag));
    }

    @Test
    void rejectsLooseUniformsInBothStages() {
        VulkanShaderMeta vert = new VulkanShaderMeta(List.of(new VulkanShaderMeta.PushMember("a", "float", 1, 0)), 4, List.of());
        VulkanShaderMeta frag = new VulkanShaderMeta(List.of(new VulkanShaderMeta.PushMember("b", "float", 2, 0)), 4, List.of());
        assertThrows(IllegalArgumentException.class, () -> VkGraphicsProgram.looseUniformStage(vert, frag));
    }

    private static VulkanShaderMeta[] metas(String program) {
        GlslForVulkan.Converted[] c = VkGraphicsProgram.convert(ShaderCatalog.visuals().get(program));
        return new VulkanShaderMeta[]{c[0].meta(), c[1].meta()};
    }

    private static Set<String> merged(String program) {
        VulkanShaderMeta[] m = metas(program);
        return VkGraphicsProgram.mergeBindings(m[0], m[1]).stream()
                .map(b -> b.set() + ":" + b.binding() + " " + b.kind() + " " + stages(b.stages()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String stages(int flags) {
        return ((flags & VK_SHADER_STAGE_VERTEX_BIT) != 0 ? "V" : "") + ((flags & VK_SHADER_STAGE_FRAGMENT_BIT) != 0 ? "F" : "");
    }

    /** The final draws read their images by gl_FragCoord, so only the fragment stage binds and holds the loose uniforms. */
    @Test
    void theFinalDrawsKeepTheirLooseUniformsInTheFragmentStage() {
        assertEquals(new TreeSet<>(Set.of("2:0 SAMPLER F", "2:1 SAMPLER F", "2:2 SAMPLER F")), merged("postFinal"));
        assertEquals(new TreeSet<>(Set.of("2:0 SAMPLER F")), merged("postBlit"));
        assertEquals(VK_SHADER_STAGE_FRAGMENT_BIT, VkGraphicsProgram.looseUniformStage(metas("postFinal")[0], metas("postFinal")[1]));
        assertEquals(VK_SHADER_STAGE_FRAGMENT_BIT, VkGraphicsProgram.looseUniformStage(metas("postBlit")[0], metas("postBlit")[1]));
    }

    /** The shadow caster and the edge cover are vertex-driven: their loose uniforms are the vertex stage's. */
    @Test
    void theCasterAndTheEdgeCoverKeepTheirLooseUniformsInTheVertexStage() {
        assertEquals(new TreeSet<>(Set.of("2:0 SAMPLER F")), merged("vanillaShadow"));
        assertEquals(new TreeSet<>(Set.of("0:2 STORAGE_BUFFER V", "2:0 SAMPLER F", "2:1 SAMPLER F", "2:3 SAMPLER F")), merged("edgeCover"));
        assertEquals(VK_SHADER_STAGE_VERTEX_BIT, VkGraphicsProgram.looseUniformStage(metas("vanillaShadow")[0], metas("vanillaShadow")[1]));
        assertEquals(VK_SHADER_STAGE_VERTEX_BIT, VkGraphicsProgram.looseUniformStage(metas("edgeCover")[0], metas("edgeCover")[1]));
    }

    /** Review focus 2: a static one would leave that state undefined for Reforged's next draws in the same command buffer. */
    @Test
    void keepsReforgedDynamicStatesDynamic() {
        Set<Integer> states = new TreeSet<>();
        for (int s : VkGraphicsProgram.DYNAMIC_STATES) states.add(s);
        assertEquals(new TreeSet<>(Set.of(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_LINE_WIDTH)), states);
    }

    /** Review focus 2: the dynamic depth bias and line width our pipelines declare are set after binding, whatever Reforged bound before. */
    @Test
    void bindDefinesDepthBiasAndLineWidth() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("net/vulkanmod/visuals/vk/VkGraphicsProgram.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals("bind")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.name);
            }
        }
        int bindPipeline = calls.indexOf("vkCmdBindPipeline");
        assertTrue(bindPipeline >= 0 && calls.indexOf("vkCmdSetDepthBias") > bindPipeline && calls.indexOf("vkCmdSetLineWidth") > bindPipeline, calls.toString());
    }

    /** P7l design 3.2: the LOD passes' second attachment (light) is written where colour is, never blended. */
    @Test
    void theLightAttachmentFollowsTheColourMask() {
        VkGraphicsProgram.State plain = new VkGraphicsProgram.State(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkGraphicsProgram.RGBA, true,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        assertFalse(plain.light());
        assertArrayEquals(new int[]{VkGraphicsProgram.RGBA}, VkGraphicsProgram.attachmentMasks(plain));
        VkGraphicsProgram.State lit = plain.withLight();
        assertTrue(lit.light() && lit.blend() && lit.depthTest());
        assertArrayEquals(new int[]{VkGraphicsProgram.RGBA, VK_COLOR_COMPONENT_R_BIT}, VkGraphicsProgram.attachmentMasks(lit));
        VkGraphicsProgram.State cull = new VkGraphicsProgram.State(true, false, VK_COMPARE_OP_LESS_OR_EQUAL, 0, false,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).withLight();
        assertArrayEquals(new int[]{0, 0}, VkGraphicsProgram.attachmentMasks(cull));
        assertArrayEquals(new int[0], VkGraphicsProgram.attachmentMasks(VkGraphicsProgram.State.depthOnly(VK_COMPARE_OP_LESS_OR_EQUAL,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)));
    }
}
