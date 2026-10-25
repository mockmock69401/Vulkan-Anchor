package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7e-1 design 3.2, 3.6: vanilla's depth copies, valid only as a pair taken in this frame at this size. */
class VkDepthCaptureTest {
    private static final String CAPTURE = "net/vulkanmod/visuals/render/VkDepthCapture";

    private static List<String> calls(String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = VkDepthCaptureTest.class.getClassLoader().getResourceAsStream(CAPTURE + ".class")) {
            assertNotNull(in, CAPTURE);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }

    @Test
    void bothCopiesOfThisFrameCount() {
        VkDepthCapture.Serials s = new VkDepthCapture.Serials();
        s.taken(VkDepthCapture.BEFORE, 9);
        assertFalse(s.both(9), "only one copy");
        s.taken(VkDepthCapture.AFTER, 9);
        assertTrue(s.both(9));
    }

    /** Review focus 5: a copy left from an earlier frame (this frame's failed or was skipped) is not used. */
    @Test
    void aCopyFromAnotherFrameDoesNotCount() {
        VkDepthCapture.Serials s = new VkDepthCapture.Serials();
        s.taken(VkDepthCapture.BEFORE, 8);
        s.taken(VkDepthCapture.AFTER, 9);
        assertFalse(s.both(9));
        assertFalse(s.both(8));
    }

    /** Review focus 5: new images hold nothing, so a resize forgets the copies. */
    @Test
    void aResizeForgetsTheCopies() throws Exception {
        VkDepthCapture.Serials s = new VkDepthCapture.Serials();
        s.taken(VkDepthCapture.BEFORE, 9);
        s.taken(VkDepthCapture.AFTER, 9);
        s.clear();
        assertFalse(s.both(9));
        assertTrue(calls("ensureSize").contains("VkDepthCapture$Serials.clear"), calls("ensureSize").toString());
        assertTrue(calls("ensureSize").contains("DeferredFree.retire"), "old images freed after their frames");
    }

    @Test
    void theCopyRewritesTheImageAndLeavesItReadable() throws Exception {
        List<String> calls = calls("capture");
        int undefined = calls.indexOf("VkImage2D.transitionFromUndefined");
        int dispatch = calls.indexOf("VkComputeProgram.dispatchWithPush");
        int read = calls.lastIndexOf("VkImage2D.transition");
        int taken = calls.indexOf("VkDepthCapture$Serials.taken");
        assertTrue(undefined >= 0 && undefined < dispatch && dispatch < read && read < taken, calls.toString());
    }

    @Test
    void aFailedConstructorFreesWhatItBuilt() throws Exception {
        List<String> calls = calls("<init>");
        assertTrue(calls.contains("VkComputeProgram.free") && calls.contains("VkSamplers.free"), calls.toString());
    }
}
