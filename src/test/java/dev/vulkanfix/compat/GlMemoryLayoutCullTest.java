package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's pipelines fix frontFace to counter-clockwise, which matches GL only under its top-down (negative-height)
 * viewport. Framebuffers drawn with Vulkan's native viewport (GL's memory layout) mirror the winding, so with culling
 * on every front face was culled: the minimap's tiles and its rotation pass drew nothing. Swap the culled face there.
 */
class GlMemoryLayoutCullTest {
    @Test
    void swapsFrontAndBackOnly() {
        assertEquals(0, CullFaces.forGlMemoryLayout(0), "none");
        assertEquals(2, CullFaces.forGlMemoryLayout(1), "front -> back");
        assertEquals(1, CullFaces.forGlMemoryLayout(2), "back -> front");
        assertEquals(3, CullFaces.forGlMemoryLayout(3), "front and back");
    }

    @Test
    void redirectsTheCullFaceReadOfThePipelineState() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"reforged.PipelineCullFaceMixin\""));
        }
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/PipelineCullFaceMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode handler = node.methods.stream()
                .filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
                        .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")))
                .findFirst().orElseThrow();
        AnnotationNode redirect = handler.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")).findFirst().orElseThrow();
        assertEquals(List.of("getAssemblyRasterState()I"), value(redirect, "method"));
        AnnotationNode at = (AnnotationNode) value(redirect, "at");
        assertEquals("FIELD", value(at, "value"));
        assertEquals("Lnet/vulkanmod/vulkan/VRenderSystem;cullFace:I", value(at, "target"));
        assertEquals("()I", handler.desc);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
