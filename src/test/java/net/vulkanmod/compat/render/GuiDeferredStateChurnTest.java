package net.vulkanmod.compat.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 2026-11-19 (Tensura, 1% lows with modded mobs): BufferSourceM asks the GUI states for a deferred draw and restores them around every
 * batch the level ends. ThreadLocal.remove() there dropped the entry, and the next get() made a new one: an allocation per batch,
 * thousands a frame. The states keep their entries (set, not remove).
 */
class GuiDeferredStateChurnTest {
    @Test
    void guiStatesNeverRemoveTheirThreadLocals() throws Exception {
        for (String resource : new String[]{"net/vulkanmod/compat/render/GuiEntityRenderState.class",
                "net/vulkanmod/compat/render/GuiItemRenderState.class"}) {
            for (MethodNode m : read(resource).methods) {
                for (AbstractInsnNode insn : m.instructions) {
                    assertFalse(insn instanceof MethodInsnNode call && call.owner.equals("java/lang/ThreadLocal") && call.name.equals("remove"),
                            resource + "." + m.name + " calls ThreadLocal.remove");
                }
            }
        }
    }

    private static ClassNode read(String resource) throws Exception {
        try (InputStream in = GuiDeferredStateChurnTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
