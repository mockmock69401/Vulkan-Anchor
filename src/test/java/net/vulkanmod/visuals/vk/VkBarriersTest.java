package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VkBarriersTest {
    @Test
    void eachHelperRecordsOneBarrier() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("net/vulkanmod/visuals/vk/VkBarriers.class")) {
            new ClassReader(in).accept(node, 0);
        }
        int helpers = 0;
        for (MethodNode m : node.methods) {
            if (!m.name.equals("frameStart") && !m.name.equals("afterCompute") && !m.name.equals("afterTransfer")
                    && !m.name.equals("afterGraphics")) continue;
            helpers++;
            int barriers = 0;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("barrier")) barriers++;
            }
            assertEquals(1, barriers, m.name);
        }
        assertEquals(4, helpers);
    }
}
