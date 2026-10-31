package net.vulkanmod.mixin.screen;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: Relics' research screen in Tensura hid its title and text): a screen starts from vanilla's GUI state, the
 * depth test off as the HUD's last flush leaves it. Without it the screen's immediate draws (Relics' research background at z 5000)
 * wrote depth and hid the text drawn after them. The 1.20.1 branch has always set this state; the 1.21.1 port had lost it.
 */
class ScreenStateBoundaryTest {
    @Test
    void aScreenStartsWithTheDepthTestOff() throws Exception {
        List<String> calls = Bytecode.calls("net/vulkanmod/mixin/screen/ScreenM", "vulkanMod$beginScreenRenderStateBoundary");
        int off = calls.indexOf("RenderSystem.disableDepthTest");
        assertTrue(off >= 0, calls.toString());
        assertTrue(calls.contains("RenderSystem.depthMask"), calls.toString());
        assertTrue(off < calls.indexOf("Renderer.clearAttachments"), calls.toString());
    }

    /**
     * Vanilla's depth writes rest on: a render type's write mask only turns them off and back on. Starting a screen with them off
     * hid Relics' hover tooltips (bench, cursor on the experience bar), drawn after a text flush left the depth test on.
     */
    @Test
    void aScreenStartsWithDepthWritesOn() throws Exception {
        for (AbstractInsnNode insn : Bytecode.method("net/vulkanmod/mixin/screen/ScreenM", "vulkanMod$beginScreenRenderStateBoundary").instructions) {
            if (insn instanceof MethodInsnNode c && Bytecode.name(c).equals("RenderSystem.depthMask")) {
                assertEquals(Opcodes.ICONST_1, Bytecode.previous(c).getOpcode());
                return;
            }
        }
        fail("no depthMask call");
    }
}
