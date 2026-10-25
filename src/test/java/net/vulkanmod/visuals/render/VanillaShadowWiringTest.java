package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P7o design 4.3: the vanilla terrain goes into the near cascade's pass after the LODs, by the setting. */
class VanillaShadowWiringTest {
    private static List<String> calls(String clazz, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = VanillaShadowWiringTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/render/" + clazz + ".class")) {
            assertNotNull(in, clazz);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) calls.add(c.owner.substring(c.owner.lastIndexOf('/') + 1) + "." + c.name);
            }
        }
        return calls;
    }

    /** Fork design 4.3: the vanilla terrain goes into each cascade's pass after the extension's casters (the LODs), before the pass ends. */
    @Test
    void theVanillaTerrainIsDrawnInsideTheCascadesPassAfterTheLods() throws Exception {
        List<String> draw = calls("VkShadowMaps", "draw");
        int lods = draw.indexOf("VisualsExtension.drawShadowCasters"), vanilla = draw.indexOf("VkVanillaShadows.draw"), end = draw.indexOf("VK10.vkCmdEndRenderPass");
        assertTrue(lods >= 0 && lods < vanilla && vanilla < end, draw.toString());
    }

    /** Review focus 5: the setting reaches the map with the draw (an argument now, a separate setter before it). */
    @Test
    void theSettingIsSetBeforeTheMapDraws() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = VanillaShadowWiringTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/render/VkShadowMaps.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        MethodNode draw = node.methods.stream().filter(m -> m.name.equals("draw")).findFirst().orElseThrow();
        assertEquals("(Lorg/lwjgl/vulkan/VkCommandBuffer;Lnet/vulkanmod/visuals/vk/VkUploadStream;Lnet/vulkanmod/visuals/vk/VkDownloadStream;ZILjava/util/List;)V", draw.desc);
        int read = -1, vanilla = -1, i = 0;
        for (AbstractInsnNode insn : draw.instructions) {
            if (read < 0 && insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ILOAD && v.var == 4) read = i;//this, cmd, upload, download, vanillaOn
            if (vanilla < 0 && insn instanceof MethodInsnNode c && c.owner.endsWith("VkVanillaShadows") && c.name.equals("draw")) vanilla = i;
            i++;
        }
        assertTrue(read >= 0 && read < vanilla, "vanillaOn is read before the vanilla draw");
    }

    /** Review focus 1: a missing or freed area buffer and too few shared quad indices are skipped, not drawn. */
    @Test
    void theDrawLoopGuardsVulkanModsBuffers() throws Exception {
        List<String> draw = calls("VkVanillaShadows", "draw");
        assertTrue(draw.contains("AreaBuffer.getId") && draw.contains("DrawBuffers.isAllocated") && draw.contains("IndexBuffer.getBufferSize"), draw.toString());
        assertTrue(draw.contains("VkGraphicsProgram.pushConstants"), "the area's offset per area");
    }

    /** Gate G7o: VulkanMod's COMPRESSED_TERRAIN is 20 bytes (short4, ubyte4, ushort2, short2); the attributes sit at 0, 8 and 12. */
    @Test
    void theVertexLayoutIsVulkanModsCompressedTerrain() {
        assertEquals(20, VkVanillaShadows.TERRAIN.stride());
        assertArrayEquals(new int[]{0, 8, 12}, VkVanillaShadows.TERRAIN.offsets());
    }
}
