package net.vulkanmod.visuals;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.List;

import static net.vulkanmod.visuals.Bytecode.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review focus 5: a mod's framebuffer left bound is replaced by the main pass; a failure turns the post-process off, the game draws on. */
class PostFailureTest {
    private static final String RENDERER = "net/vulkanmod/visuals/render/VisualsRenderer";

    @Test
    void aFailureTurnsThePostOffWithoutRethrowing() throws Exception {
        MethodNode m = Bytecode.method(RENDERER, "postProcess");
        TryCatchBlockNode handler = null;
        for (TryCatchBlockNode t : m.tryCatchBlocks) {
            if ("java/lang/RuntimeException".equals(t.type)) handler = t;
        }
        assertNotNull(handler, "postProcess catches RuntimeException");
        boolean failed = false, reset = false;
        for (AbstractInsnNode insn = handler.handler; insn != null && insn.getOpcode() != Opcodes.RETURN && insn.getOpcode() != Opcodes.GOTO;
             insn = insn.getNext()) {
            assertFalse(insn.getOpcode() == Opcodes.ATHROW, "the handler rethrows");
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("postFailed")) {
                assertEquals(Opcodes.ICONST_1, Bytecode.previous(f).getOpcode(), "postFailed = true");
                failed = true;
            }
            if (insn instanceof MethodInsnNode c && Bytecode.name(c).equals("FrameFlags.resetForSkippedPost")) reset = true;
        }
        assertTrue(failed, "postFailed = true in the handler");
        assertTrue(reset, "the flags go back to vanilla in the handler");
    }

    @Test
    void theMainPassIsBoundBeforeThePostStarts() throws Exception {
        List<String> c = calls(RENDERER, "postProcess");
        int resume = c.indexOf("VkContext.resumeMainPass"), capture = c.indexOf("VkContext.captureMainTarget");
        int post = -1;
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).startsWith("VkPostProcess.")) {
                post = i;
                break;
            }
        }
        assertTrue(resume >= 0 && resume < capture && capture < post, c.toString());
        assertTrue(c.indexOf("VkContext.endMainPass") > resume, c.toString());
    }
}
