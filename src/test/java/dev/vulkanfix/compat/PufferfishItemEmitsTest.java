package dev.vulkanfix.compat;

import net.puffish.skillsmod.access.BuiltBufferAccess;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pufferfish's Skills bakes one item icon at the origin and draws it once per emit matrix from an inject inside
 * BufferUploader._drawWithShader. Reforged's drawWithShader overwrite never calls that method, so every icon of the
 * skill tree was drawn once at the screen's top-left corner. vulkanfix applies the emits around Reforged's draw.
 */
class PufferfishItemEmitsTest {
    private static final String MIXIN = "puffish.BufferUploaderEmitsMixin";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String DRAW_WITH_SHADER = "m_231202_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V";

    @Test
    void registeredAndGatedOnVulkanModAndPufferfish() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + MIXIN + "\""));
        }
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod", "puffish_skills")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.of("puffish_skills")::contains));
    }

    @Test
    void redirectsReforgedDrawAndUploadsTheMatrixOfEveryEmit() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/puffish/BufferUploaderEmitsMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(mixin);
        assertTrue((Integer) value(mixin, "priority") > 1000, "must apply after Reforged's @Overwrite of drawWithShader");

        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m.visibleAnnotations, REDIRECT) != null)
                .findFirst().orElseThrow();
        AnnotationNode redirect = annotation(handler.visibleAnnotations, REDIRECT);
        assertEquals(List.of(DRAW_WITH_SHADER), value(redirect, "method"));

        assertTrue(calls(handler, "net/vulkanmod/vulkan/VRenderSystem", "applyMVP"));
        assertTrue(calls(handler, "net/vulkanmod/vulkan/Renderer", "uploadAndBindUBOs"));
        assertTrue(calls(handler, "net/vulkanmod/vulkan/Drawer", "draw"));
    }

    @Test
    void readsTheEmitsOfABuiltBuffer() {
        List<Matrix4f> emits = List.of(new Matrix4f());
        assertEquals(emits, PufferfishItemEmits.of(new Built(emits)));
    }

    @Test
    void anOrdinaryDrawHasNoEmits() {
        assertNull(PufferfishItemEmits.of(new Object()));
        assertNull(PufferfishItemEmits.of(new Built(null)));
    }

    @Test
    void emitIsAppliedInsideTheModelView() {
        Matrix4f modelView = new Matrix4f().scaling(2.0F);
        Matrix4f emit = new Matrix4f().translation(10.0F, 20.0F, 0.0F);

        Vector3f icon = PufferfishItemEmits.modelView(modelView, emit).transformPosition(new Vector3f(1.0F, 0.0F, 0.0F));

        assertEquals(new Vector3f(22.0F, 40.0F, 0.0F), icon);
        assertEquals(new Matrix4f().scaling(2.0F), modelView, "the shared model view stays as it was");
        assertEquals(new Matrix4f().translation(10.0F, 20.0F, 0.0F), emit, "the emit is reused next frame");
    }

    private record Built(List<Matrix4f> emits) implements BuiltBufferAccess {
        @Override
        public void setEmits(List<Matrix4f> emits) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Matrix4f> getEmits() {
            return this.emits;
        }
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
