package net.vulkanmod.mixin.screen;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Vanilla's GUI state keeps depth writes on and its render types only turn them off and back on. A screen that enables
 * the depth test relies on them: Pufferfish's Skills draws its effect icons at z 1 before their frames at z 0, so with
 * writes off the frames covered every icon.
 */
class ScreenDepthWritesTest {
    @Test
    void aScreenStartsWithDepthWritesOn() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("net/vulkanmod/mixin/screen/ScreenM.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode boundary = node.methods.stream()
                .filter(m -> m.name.equals("vulkanMod$beginScreenRenderStateBoundary"))
                .findFirst().orElseThrow();

        AbstractInsnNode argument = null;
        for (AbstractInsnNode insn : boundary.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("depthMask")) {
                argument = call.getPrevious();
            }
        }
        assertNotNull(argument, "the boundary sets the depth mask");
        assertEquals(Opcodes.ICONST_1, argument.getOpcode());
    }
}
