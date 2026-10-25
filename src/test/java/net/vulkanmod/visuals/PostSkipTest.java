package net.vulkanmod.visuals;

import net.vulkanmod.visuals.render.PostGate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static net.vulkanmod.visuals.Bytecode.calls;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review focus 2: on the title screen, a FancyMenu menu or a minimised window the post-process records nothing. */
class PostSkipTest {
    private static final String RENDERER = "net/vulkanmod/visuals/render/VisualsRenderer";

    @Test
    void theGateRunsOnlyWithEverythingInPlace() {
        assertTrue(PostGate.shouldRun(true, 1920, 1080, true, true, false));
        assertFalse(PostGate.shouldRun(false, 1920, 1080, true, true, false), "no level");
        assertFalse(PostGate.shouldRun(true, 0, 1080, true, true, false), "width 0");
        assertFalse(PostGate.shouldRun(true, 1920, 0, true, true, false), "height 0");
        assertFalse(PostGate.shouldRun(true, -1, -1, true, true, false), "negative size");
        assertFalse(PostGate.shouldRun(true, 1920, 1080, false, true, false), "off");
        assertFalse(PostGate.shouldRun(true, 1920, 1080, true, false, false), "not recording");
        assertFalse(PostGate.shouldRun(true, 1920, 1080, true, true, true), "failed");
    }

    /** The gate comes before anything that touches the pass, and its false branch returns without recording. */
    @Test
    void postProcessReturnsThroughTheGateFirst() throws Exception {
        List<String> c = calls(RENDERER, "postProcess");
        int gate = c.indexOf("PostGate.shouldRun");
        assertTrue(gate >= 0, c.toString());
        for (String call : c.subList(0, gate)) {
            assertFalse(call.startsWith("VkPostProcess.") || call.startsWith("VkGpuTimer.") || call.startsWith("VkBarriers.")
                    || (call.startsWith("VkContext.") && !call.equals("VkContext.recordingFrame")), "before the gate: " + c);
        }
        MethodNode m = Bytecode.method(RENDERER, "postProcess");
        MethodInsnNode gateCall = null;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("shouldRun")) gateCall = call;
        }
        assertNotNull(gateCall);
        AbstractInsnNode branch = gateCall.getNext();
        while (branch.getOpcode() < 0) branch = branch.getNext();
        assertTrue(branch instanceof JumpInsnNode, "the gate's result is tested right away");
        //The skipped path: straight on to its return, calling nothing but the volume drop and the flag reset
        List<String> skipped = new ArrayList<>();
        AbstractInsnNode insn = branch.getNext();
        while (insn != null && insn.getOpcode() != Opcodes.RETURN) {
            if (insn instanceof MethodInsnNode call) skipped.add(Bytecode.name(call));
            insn = insn.getNext();
        }
        assertNotNull(insn, "the skipped path returns");
        assertTrue(skipped.contains("FrameFlags.resetForSkippedPost"), skipped.toString());
        assertTrue(Set.of("FrameFlags.resetForSkippedPost", "LightVolumeSystem.update").containsAll(skipped), skipped.toString());
    }
}
