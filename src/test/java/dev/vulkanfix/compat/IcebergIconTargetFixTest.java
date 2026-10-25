package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
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
 * Iceberg renders GUI item icons (Advancement Plaques) into a 96x96 offscreen buffer it creates as
 * {@code new MainTarget(96, 96)}. Reforged's MainTargetMixin turns every MainTarget into the window's swapchain pass,
 * so the 96x-scaled icon is drawn straight onto the screen and covers it. vulkanfix swaps that field for a TextureTarget.
 */
class IcebergIconTargetFixTest {
    private static final String MIXIN = "iceberg.CustomItemRendererMixin";

    @Test
    void registeredAsClientMixin() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"" + MIXIN + "\""), "missing from vulkanfix.mixins.json: " + MIXIN);
        }
    }

    @Test
    void appliesOnlyWithVulkanModAndIceberg() {
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod", "iceberg")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.of("iceberg")::contains));
    }

    @Test
    void shadowsIconBufferAndHooksConstructorReturn() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/iceberg/CustomItemRendererMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of("com.anthonyhilyard.iceberg.renderer.CustomItemRenderer"), value(mixin, "targets"));

        FieldNode field = node.fields.stream().filter(f -> f.name.equals("iconFrameBuffer")).findFirst().orElseThrow();
        assertEquals("Lcom/mojang/blaze3d/pipeline/RenderTarget;", field.desc);
        assertNotNull(annotation(field.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;"));

        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;") != null)
                .findFirst().orElseThrow();
        AnnotationNode inject = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("<init>"), value(inject, "method"));
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
