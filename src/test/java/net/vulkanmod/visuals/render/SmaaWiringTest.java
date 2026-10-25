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

import static org.junit.jupiter.api.Assertions.*;

/** P7i: SMAA runs after the post-process compute and before the main pass resumes; the final draw then blits its result. */
class SmaaWiringTest {
    private static List<String> calls(String clazz, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = SmaaWiringTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/render/" + clazz + ".class")) {
            assertNotNull(in, clazz);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            //The post-process commands are recorded in a lambda inside the method
            if (!m.name.equals(method) && !m.name.startsWith("lambda$" + method + "$")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) calls.add(c.owner.substring(c.owner.lastIndexOf('/') + 1) + "." + c.name);
            }
        }
        return calls;
    }

    @Test
    void smaaRunsBetweenTheComputeAndTheResumedPass() throws Exception {
        List<String> record = calls("VisualsRenderer", "postProcess");
        int compute = record.indexOf("VkPostProcess.compute"), aa = record.indexOf("VkPostProcess.antiAlias"),
                resume = record.subList(Math.max(aa, 0), record.size()).indexOf("VkContext.resumeMainPass") + Math.max(aa, 0),
                draw = record.indexOf("VkPostProcess.draw");
        assertTrue(compute >= 0 && compute < aa && aa < resume && resume < draw, record.toString());
    }

    @Test
    void theDrawBlitsWhenSmaaRan() throws Exception {
        List<String> draw = calls("VkPostProcess", "draw");
        assertTrue(draw.indexOf("VkSmaa.draw") >= 0 && draw.indexOf("VkSmaa.draw") < draw.indexOf("VkGraphicsProgram.bind"), draw.toString());
        //TAA pass (2026-10-15): the final colour is its own step (TAA reads it), then the three SMAA passes
        assertEquals(1, calls("VkSmaa", "finalColour").stream().filter("VkComputeProgram.dispatchWithPush"::equals).count(), "final colour");
        assertEquals(3, calls("VkSmaa", "antiAlias").stream().filter("VkComputeProgram.dispatchWithPush"::equals).count(), "edges, weights, blend");
    }

    @Test
    void theLookupTexturesWidenToRgba() {
        byte[] rg = {1, 2, 3, 4};
        assertArrayEquals(new byte[]{1, 2, 0, 0, 3, 4, 0, 0}, VkSmaa.rgba(rg, 2));
        assertArrayEquals(new byte[]{9, 0, 0, 0, 8, 0, 0, 0}, VkSmaa.rgba(new byte[]{9, 8}, 1));
        assertNull(VkSmaa.PRESETS[0], "0 is off");
        assertEquals(PostProcessSettings.ANTI_ALIASING_MAX + 1, VkSmaa.PRESETS.length);
        assertEquals(32f, VkSmaa.PRESETS[4][1], "ultra searches 32 steps");
        //Corner detection is compiled in: upstream LOW/MEDIUM have none, which is rounding 100 here (0 would cut corners hardest)
        assertEquals(100f, VkSmaa.PRESETS[1][3]);
        assertEquals(100f, VkSmaa.PRESETS[2][3]);
    }
}
