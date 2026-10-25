package net.vulkanmod.visuals;

import net.vulkanmod.visuals.render.VkDepthCapture;
import net.vulkanmod.visuals.render.VisualsRenderer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.visuals.Bytecode.calls;
import static net.vulkanmod.visuals.Bytecode.count;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review focus 4: a world change frees everything in setLevel and builds it again in allChanged; Reforged's frame runs the visuals. */
class LifecycleWiringTest {
    private static final String WORLD = "net/vulkanmod/render/chunk/WorldRenderer";

    @Test
    void setLevelShutsTheVisualsDownFirst() throws Exception {
        List<String> c = calls(WORLD, "setLevel");
        assertEquals("Visuals.shutdown", c.get(0), c.toString());
        assertEquals(1, count(c, "Visuals.shutdown"), c.toString());
    }

    @Test
    void allChangedRecreatesTheVisualsAtItsEnd() throws Exception {
        List<String> c = calls(WORLD, "allChanged");
        assertEquals(1, count(c, "Visuals.recreate"), c.toString());
        assertEquals("Visuals.recreate", c.get(c.size() - 1), "the last call, after the grid and graph are rebuilt: " + c);
        for (AbstractInsnNode insn : Bytecode.method(WORLD, "allChanged").instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("recreate")) {
                assertEquals("(Lnet/minecraft/client/multiplayer/ClientLevel;)V", m.desc);
            }
        }
    }

    /** The cutout layer runs the frame; the main depth is copied right before and right after the translucent layer, each once. */
    @Test
    void renderSectionLayerRunsTheFrameAndTheDepthCopies() throws Exception {
        MethodNode m = Bytecode.method(WORLD, "renderSectionLayer");
        List<String> c = calls(WORLD, "renderSectionLayer");
        assertEquals(1, count(c, "Visuals.frame"), c.toString());
        List<Integer> slots = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("net/vulkanmod/visuals/Visuals") && call.name.equals("captureDepth")) {
                AbstractInsnNode arg = Bytecode.previous(call);
                slots.add(arg.getOpcode() - Opcodes.ICONST_0);
            }
            if (insn instanceof MethodInsnNode call && call.name.equals("frame") && call.owner.equals("net/vulkanmod/visuals/Visuals")) {
                assertEquals("(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;DDD)V", call.desc);
            }
        }
        assertEquals(List.of(VkDepthCapture.BEFORE, VkDepthCapture.AFTER), slots, c.toString());
        int before = c.indexOf("Visuals.captureDepth"), after = c.lastIndexOf("Visuals.captureDepth");
        assertTrue(before < c.indexOf("RenderType.setupRenderState"), "BEFORE ahead of the layer's draws: " + c);
        assertTrue(c.lastIndexOf("RenderType.clearRenderState") < c.indexOf("Visuals.frame"), "the frame after the cutout layer: " + c);
        assertTrue(c.lastIndexOf("RenderType.clearRenderState") < after, "AFTER behind the translucent layer: " + c);
    }

    @Test
    void shuttingDownTwiceIsSafe() {
        VisualsRenderer empty = VisualsRenderer.empty();
        assertDoesNotThrow(() -> {
            empty.shutdown();
            empty.shutdown();
        });
        assertDoesNotThrow(() -> {
            Visuals.shutdown();
            Visuals.shutdown();
        });
    }
}
