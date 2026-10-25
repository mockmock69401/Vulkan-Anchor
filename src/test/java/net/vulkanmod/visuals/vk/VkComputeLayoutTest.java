package net.vulkanmod.visuals.vk;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VkComputeLayoutTest {
    @Test
    void layoutSizesCoverTheHighestBindingPerSet() {
        VulkanShaderMeta meta = new VulkanShaderMeta(List.of(), 0, List.of(
                new VulkanShaderMeta.Binding(0, 2, VulkanShaderMeta.Kind.STORAGE_BUFFER),
                new VulkanShaderMeta.Binding(0, 0, VulkanShaderMeta.Kind.STORAGE_BUFFER),
                new VulkanShaderMeta.Binding(2, 0, VulkanShaderMeta.Kind.SAMPLER)));
        assertArrayEquals(new int[]{3, 0, 1, 0}, VkComputeProgram.setLayoutSizes(meta));
    }

    /** Review I4: at runtime the program is built with this machine's IS_INTEL / IS_WINDOWS, like Shader.Builder.compile. */
    @Test
    void runtimeProgramsUseTheRunningPlatformsDefines() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("net/vulkanmod/visuals/vk/VkComputeProgram.class")) {
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals("of")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.name);
            }
        }
        assertTrue(calls.contains("shaderPlatform") && calls.contains("withPlatform"), calls.toString());
    }
}
