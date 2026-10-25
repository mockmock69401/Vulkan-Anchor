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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Xaero's multi-texture renderer draws the first batch with BufferUploader.drawWithShader and every following batch
 * with GlStateManager._bindTexture + BufferUploader.draw. Reforged's BufferUploader.draw only records the draw, so the
 * descriptor set keeps the first batch's texture and every map tile repeats it. vulkanfix re-binds the bound
 * pipeline's descriptor sets (which picks up the texture bound since) before that draw, as GL would sample it.
 */
class DrawRebindsTexturesTest {
    private static final String MIXIN = "reforged.BufferUploaderDrawMixin";

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + MIXIN + "\""));
        }
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.<String>of()::contains));
    }

    @Test
    void rebindsBoundPipelineDescriptorsAtHeadOfDraw() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/BufferUploaderDrawMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(mixin);
        assertTrue((Integer) value(mixin, "priority") > 1000, "must apply after Reforged's @Overwrite of draw");

        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;") != null)
                .findFirst().orElseThrow();
        AnnotationNode inject = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("m_231209_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V"), value(inject, "method"));
        @SuppressWarnings("unchecked")
        List<AnnotationNode> at = (List<AnnotationNode>) value(inject, "at");
        assertEquals("HEAD", value(at.get(0), "value"));

        assertTrue(calls(handler, "net/vulkanmod/vulkan/Renderer", "getBoundPipeline"));
        assertTrue(calls(handler, "net/vulkanmod/vulkan/Renderer", "uploadAndBindUBOs"));
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
