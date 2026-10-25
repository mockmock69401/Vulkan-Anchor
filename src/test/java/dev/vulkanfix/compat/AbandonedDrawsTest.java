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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's BufferUploader.drawWithShader/draw overwrites release the RenderedBuffer only after recording the draw;
 * vanilla releases it first (VertexBuffer.upload's finally). When the draw threw (quick play: "state.renderPass is
 * null") and a caller swallowed the exception (FancyMenu's event bus), the buffer was never released, its
 * BufferBuilder kept a pending batch and never cleared again, and every later batch piled on until it overflowed at
 * 2 GB. vulkanfix tracks the draws in flight and releases, at the end of the frame, any that never returned.
 */
class AbandonedDrawsTest {
    private static final String MIXIN = "reforged.BufferUploaderReleaseMixin";
    private static final String FRAME_MIXIN = "reforged.RendererReleaseAbandonedMixin";
    private static final String ACCESSOR = "reforged.RenderedBufferAccessor";

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String mixin : List.of(MIXIN, FRAME_MIXIN, ACCESSOR)) {
                assertTrue(json.contains("\"" + mixin + "\""), mixin);
                assertTrue(CompatGate.shouldApply(mixin, Set.of("vulkanmod")::contains));
                assertFalse(CompatGate.shouldApply(mixin, Set.<String>of()::contains));
            }
        }
    }

    @Test
    void drawsThatReturnLeaveNothingToRelease() {
        AbandonedDraws<String> draws = new AbandonedDraws<>();
        draws.enter("gui");
        draws.exit("gui");
        draws.enter("text");
        draws.exit("text");
        assertEquals(List.of(), drain(draws));
    }

    @Test
    void releasesADrawThatThrew() {
        AbandonedDraws<String> draws = new AbandonedDraws<>();
        draws.enter("fill");
        draws.enter("blit");
        draws.exit("blit");
        assertEquals(List.of("fill"), drain(draws));
        assertEquals(List.of(), drain(draws));
    }

    @Test
    void releasesOldestFirstWhenSeveralThrew() {
        AbandonedDraws<String> draws = new AbandonedDraws<>();
        draws.enter("fill");
        draws.enter("innerBlit");
        assertEquals(List.of("fill", "innerBlit"), drain(draws));
    }

    /** A draw nested in another (ImmediatelyFast flushes its batches at the head of draw) that throws inside it. */
    @Test
    void anOuterDrawReturningDoesNotHideANestedOneThatThrew() {
        AbandonedDraws<String> draws = new AbandonedDraws<>();
        draws.enter("outer");
        draws.enter("nested");
        draws.exit("outer");
        assertEquals(List.of("nested"), drain(draws));
    }

    @Test
    void tracksDrawWithShaderAndDrawAtHeadAndReturn() throws Exception {
        ClassNode node = classNode("dev/vulkanfix/mixin/reforged/BufferUploaderReleaseMixin.class");
        AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(mixin);
        assertTrue((Integer) value(mixin, "priority") > 1000, "must apply after Reforged's @Overwrite of both methods");

        Map<String, String> points = new TreeMap<>();
        for (MethodNode handler : node.methods) {
            AnnotationNode inject = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (inject == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<AnnotationNode> at = (List<AnnotationNode>) value(inject, "at");
            for (Object method : (List<?>) value(inject, "method")) {
                points.merge((String) method, (String) value(at.get(0), "value"), (a, b) -> a + "," + b);
            }
        }
        String withShader = "m_231202_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V";
        String draw = "m_231209_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V";
        assertEquals(Set.of(withShader, draw), points.keySet());
        assertEquals(Set.of("HEAD", "RETURN"), Set.of(points.get(withShader).split(",")));
        assertEquals(Set.of("HEAD", "RETURN"), Set.of(points.get(draw).split(",")));
    }

    @Test
    void releasesAtTheStartOfReforgedsEndFrame() throws Exception {
        ClassNode node = classNode("dev/vulkanfix/mixin/reforged/RendererReleaseAbandonedMixin.class");
        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;") != null)
                .findFirst().orElseThrow();
        AnnotationNode inject = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("endFrame()V"), value(inject, "method"));
        @SuppressWarnings("unchecked")
        List<AnnotationNode> at = (List<AnnotationNode>) value(inject, "at");
        assertEquals("HEAD", value(at.get(0), "value"));
    }

    private static List<String> drain(AbandonedDraws<String> draws) {
        List<String> released = new ArrayList<>();
        draws.drain(released::add);
        return released;
    }

    private ClassNode classNode(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not compiled");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
