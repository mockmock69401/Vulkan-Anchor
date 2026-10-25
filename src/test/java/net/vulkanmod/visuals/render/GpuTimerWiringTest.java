package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.vulkanmod.visuals.Bytecode.calls;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7f design 3: each place that starts recording the visuals' work resets the frame's queries before its first mark. */
class GpuTimerWiringTest {
    private static final String SYSTEM = "net/vulkanmod/visuals/render/VisualsRenderer";

    private static Set<String> strings(String internalName, String method) throws Exception {
        Set<String> out = new HashSet<>();
        for (MethodNode m : Bytecode.read(internalName).methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof LdcInsnNode l && l.cst instanceof String s) out.add(s);
            }
        }
        return out;
    }

    @Test
    void theFrameAndThePostProcessResetBeforeTheirFirstMark() throws Exception {
        for (String method : new String[]{"frame", "postProcess"}) {
            List<String> c = calls(SYSTEM, method);
            int reset = c.indexOf("VkGpuTimer.reset"), mark = c.indexOf("VkGpuTimer.mark");
            assertTrue(reset >= 0 && reset < mark, method + ": " + c);
        }
    }

    @Test
    void thePostProcessComputeIsTimedAndTheSlotsAreReadAgain() throws Exception {
        assertTrue(calls("net/vulkanmod/visuals/render/VkPostProcess", "compute").contains("VkGpuTimer.mark"));
        assertTrue(calls("net/vulkanmod/visuals/vk/VkFrames", "onRecordingStarted").contains("VkGpuTimer.onRecordingStarted"),
                "a slot's results are read when it records again");
    }

    /** The carry's timer labels: the non-LOD intervals keep their names so gate G1-c compares them with 349be58. */
    @Test
    void theNonLodIntervalsKeepTheirLabels() throws Exception {
        assertTrue(strings(SYSTEM, "postProcess").containsAll(Set.of("post.upload", "post.draw", "end")), strings(SYSTEM, "postProcess").toString());
        assertTrue(strings(SYSTEM, "captureDepth").containsAll(Set.of("depth.copy", "vanilla.late")), strings(SYSTEM, "captureDepth").toString());
        assertTrue(strings(SYSTEM, "frame").containsAll(Set.of("edgeCover", "vanilla.late")), strings(SYSTEM, "frame").toString());
    }
}
