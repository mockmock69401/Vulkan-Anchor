package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's glGetTexImage into a pixel pack buffer submits its own command buffer and waits, so it reads the image
 * before this frame's draws (still unsubmitted) have run, and ignores format/type. Xaero renders each zoomed-out
 * "branch" map texture and reads it straight back into a PBO, mapping that PBO a few frames later; the stale,
 * mis-ordered bytes were then cached and drawn (noise below 0.5x zoom). vulkanfix records the copy into the frame
 * being built and fills the PBO, converted, when it is mapped.
 */
class DeferredReadbackWiringTest {
    private static final List<String> MIXINS = List.of(
            "reforged.GlTextureReadbackMixin",
            "reforged.GlBufferReadbackMixin",
            "reforged.RendererFrameSerialMixin",
            "reforged.GlBufferAccessor",
            "reforged.GlFramebufferAccessor"
    );

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        String json;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String mixin : MIXINS) {
            assertTrue(json.contains("\"" + mixin + "\""), mixin);
            assertTrue(CompatGate.shouldApply(mixin, Set.of("vulkanmod")::contains));
            assertFalse(CompatGate.shouldApply(mixin, Set.<String>of()::contains));
        }
    }

    @Test
    void interceptsGetTexImageCancellably() throws Exception {
        AnnotationNode inject = onlyInject("GlTextureReadbackMixin").get(0);
        assertEquals(List.of("getTexImage(IIIIJ)V"), value(inject, "method"));
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        assertHead(inject);
    }

    @Test
    void resolvesBeforeEveryPackBufferRead() throws Exception {
        List<Object> methods = new ArrayList<>();
        for (AnnotationNode inject : onlyInject("GlBufferReadbackMixin")) {
            assertHead(inject);
            methods.addAll((List<?>) value(inject, "method"));
        }
        assertTrue(methods.contains("glMapBuffer(II)Ljava/nio/ByteBuffer;"));
        assertTrue(methods.contains("glMapBufferRange(IJJI)Ljava/nio/ByteBuffer;"));
        assertTrue(methods.contains("glGetBufferSubData(IJLjava/nio/ByteBuffer;)V"));
    }

    @Test
    void countsFramesAtBeginFrame() throws Exception {
        AnnotationNode inject = onlyInject("RendererFrameSerialMixin").get(0);
        assertEquals(List.of("beginFrame()V"), value(inject, "method"));
        assertHead(inject);
    }

    private List<AnnotationNode> onlyInject(String simpleName) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/" + simpleName + ".class")) {
            assertNotNull(in, simpleName + " not compiled");
            new ClassReader(in).accept(node, 0);
        }
        List<AnnotationNode> injects = new ArrayList<>();
        for (MethodNode m : node.methods) {
            AnnotationNode a = annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (a != null) {
                injects.add(a);
            }
        }
        assertFalse(injects.isEmpty());
        return injects;
    }

    @SuppressWarnings("unchecked")
    private static void assertHead(AnnotationNode inject) {
        List<AnnotationNode> at = (List<AnnotationNode>) value(inject, "at");
        assertEquals("HEAD", value(at.get(0), "value"));
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        int i = a.values.indexOf(key);
        return i < 0 ? null : a.values.get(i + 1);
    }
}
