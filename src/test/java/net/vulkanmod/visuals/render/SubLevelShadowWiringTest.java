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

/** Sable's sub-levels into the sun shadows: where the draws are kept. */
class SubLevelShadowWiringTest {
    private static final String CASTERS = "SubLevelShadowCasters.";

    static List<String> calls(String clazz, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = SubLevelShadowWiringTest.class.getClassLoader().getResourceAsStream(clazz + ".class")) {
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

    @Test
    void theVboKeepsASubLevelsDrawWhileALayerRecords() throws Exception {
        List<String> draw = calls("net/vulkanmod/render/VBO", "draw");
        int asked = draw.indexOf(CASTERS + "recording"), kept = draw.indexOf(CASTERS + "record");
        assertTrue(asked >= 0 && asked < kept, draw.toString());
    }

    /** Review focus 1: only the opaque and cutout layers cast; translucent, tripwire and Aeronautics' levitite layers do not. */
    @Test
    void onlyTheOpaqueLayersOpenARecording() throws Exception {
        List<String> scope = calls("net/vulkanmod/mixin/compatibility/sable/VanillaSubLevelRenderDispatcherMixin", "vulkanmod$sectionLayerScope");
        assertTrue(scope.containsAll(List.of("RenderType.solid", "RenderType.cutoutMipped", "RenderType.cutout")), scope.toString());
        assertFalse(scope.contains("RenderType.tripwire"), scope.toString());
        int open = scope.indexOf(CASTERS + "beginLayer"), draw = scope.indexOf("Operation.call"), close = scope.lastIndexOf(CASTERS + "endLayer");
        assertTrue(open >= 0 && open < draw && draw < close, scope.toString());
    }

    /** Review focus 5: a frame without a post-process does not leave its draws to the next one. */
    @Test
    void everyLevelRenderStartsAnEmptyList() throws Exception {
        assertTrue(calls("net/vulkanmod/mixin/chunk/LevelRendererMixin", "prepareLevelRenderState").contains(CASTERS + "beginLevel"));
    }
}
