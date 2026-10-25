package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's Screenshot.takeScreenshot sizes the NativeImage from the main target's size (the window, 2048x1152)
 * but copies the bound colour image, which under render scale is the scaled framebuffer (1638x922 at 80%). The tightly
 * packed 1638-wide rows were read as 2048-wide ones: diagonal streaks, and a black band where no bytes were copied.
 */
class ScaledScreenshotTest {
    @Test
    void sizesTheScreenshotToTheImageItReads() {
        assertArrayEquals(new int[]{1638, 922}, ScaledScreenshot.size(2048, 1152, 1638, 922));
    }

    @Test
    void leavesReforgedAloneWhenTheImageMatchesTheTarget() {
        assertNull(ScaledScreenshot.size(2048, 1152, 2048, 1152));
    }

    @Test
    void leavesReforgedAloneWithoutAnImage() {
        assertNull(ScaledScreenshot.size(2048, 1152, 0, 0));
    }

    @Test
    void injectsAheadOfReforgedsTakeScreenshotOverwrite() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"reforged.ScreenshotSizeMixin\""));
        }
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/ScreenshotSizeMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode mixin = node.invisibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        assertTrue((Integer) value(mixin, "priority") > 1000, "must apply after Reforged's @Overwrite of takeScreenshot");
        MethodNode handler = node.methods.stream()
                .filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
                        .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")))
                .findFirst().orElseThrow();
        AnnotationNode inject = handler.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("m_92279_(Lcom/mojang/blaze3d/pipeline/RenderTarget;)Lcom/mojang/blaze3d/platform/NativeImage;"),
                value(inject, "method"));
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        assertEquals("(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V",
                handler.desc);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
