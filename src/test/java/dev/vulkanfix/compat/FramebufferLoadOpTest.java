package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged builds every GL framebuffer's render pass with loadOp CLEAR, so merely binding an FBO wipes it; GL never
 * clears on bind (glClear is emulated separately with vkCmdClearAttachments). Xaero reuses one 64x64 FBO for its
 * zoomed-out map textures and redraws only the changed quarters: binding it wiped the previous tile and the unchanged
 * quarters (readbacks came back exactly 1/4 or 3/4 empty). vulkanfix builds those passes with loadOp LOAD.
 */
class FramebufferLoadOpTest {
    private static final String MIXIN = "reforged.GlFramebufferLoadOpMixin";

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + MIXIN + "\""));
        }
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.<String>of()::contains));
    }

    @Test
    void buildsGlFramebufferPassesWithLoadOpLoad() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/GlFramebufferLoadOpMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m, "Lorg/spongepowered/asm/mixin/injection/Redirect;") != null)
                .findFirst().orElseThrow();
        AnnotationNode redirect = annotation(handler, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        assertEquals(List.of("createAndBind()V"), value(redirect, "method"));
        assertEquals("Lnet/vulkanmod/vulkan/framebuffer/RenderPass$Builder;build()Lnet/vulkanmod/vulkan/framebuffer/RenderPass;",
                value((AnnotationNode) value(redirect, "at"), "target"));

        int loadOpCalls = 0;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("setLoadOp")) {
                loadOpCalls++;
                assertEquals(Opcodes.ICONST_0, insn.getPrevious().getOpcode(), "VK_ATTACHMENT_LOAD_OP_LOAD is 0");
            }
        }
        assertEquals(2, loadOpCalls, "color and depth");
    }

    private static AnnotationNode annotation(MethodNode m, String desc) {
        return m.visibleAnnotations == null ? null
                : m.visibleAnnotations.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
