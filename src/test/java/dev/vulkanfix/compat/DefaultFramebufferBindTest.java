package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged handles glBindFramebuffer(GL_FRAMEBUFFER, 0) by calling Minecraft.getMainRenderTarget().bindWrite(true).
 * Xaero's minimap and world map temporarily swap Minecraft's main render target to their own framebuffer, so
 * "bind the window" re-bound Xaero's framebuffer instead (diag: bindFramebuffer(id=0) -> renderPass of fbo#17/#22),
 * and the final map composite never reached the screen. vulkanfix binds Reforged's real main pass instead.
 */
class DefaultFramebufferBindTest {
    private static final String MIXIN = "reforged.GlFramebufferDefaultTargetMixin";

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + MIXIN + "\""));
        }
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.<String>of()::contains));
    }

    @Test
    void redirectsMainRenderTargetBindWriteInsideBindFramebuffer() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/GlFramebufferDefaultTargetMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;") != null)
                .findFirst().orElseThrow();
        AnnotationNode redirect = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        assertEquals(List.of("bindFramebuffer(II)V"), value(redirect, "method"));
        assertEquals("Lcom/mojang/blaze3d/pipeline/RenderTarget;m_83947_(Z)V", value((AnnotationNode) value(redirect, "at"), "target"));
        assertEquals("(Lcom/mojang/blaze3d/pipeline/RenderTarget;Z)V", handler.desc);
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
