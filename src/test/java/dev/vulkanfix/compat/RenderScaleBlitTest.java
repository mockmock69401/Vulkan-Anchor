package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged resolves its render-scale framebuffer onto the swapchain with DrawUtil.blitRenderScaleToScreen, which just
 * calls the generic fast blit. That pipeline's vertex shader uses INVERTED_UV (v = 0 at the bottom of clip space), so
 * under Reforged's top-down viewport the scaled image (row 0 = top) reached the screen upside down whenever render
 * scale was below 100%. Reforged ships an upright render_scale_blit pipeline for exactly this and never binds it.
 */
class RenderScaleBlitTest {
    @Test
    void replacesTheRenderScaleBlitWithReforgedsUprightPipeline() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"reforged.RenderScaleBlitMixin\""));
        }
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/RenderScaleBlitMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode handler = node.methods.stream()
                .filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
                        .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")))
                .findFirst().orElseThrow();
        AnnotationNode inject = handler.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("blitRenderScaleToScreen()V"), value(inject, "method"));
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        assertEquals("HEAD", value((AnnotationNode) ((List<?>) value(inject, "at")).get(0), "value"));

        assertTrue(calls(handler, "net/vulkanmod/render/PipelineManager", "getRenderScaleBlitPipeline"));
        assertFalse(calls(handler, "net/vulkanmod/render/PipelineManager", "getFastBlitPipeline"));
        assertTrue(calls(handler, "org/lwjgl/vulkan/VK11", "vkCmdDraw"));
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
