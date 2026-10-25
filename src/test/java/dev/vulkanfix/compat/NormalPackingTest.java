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
 * GUI items are drawn with pose scale(16, -16, 16). Vanilla's PoseStack.scale then builds the normal matrix with
 * Mth.fastInvCubeRoot(-1/4096), which returns ~3.5e26, so normals reach the vertex consumer as ~(0, 0, 2.2e25).
 * Vanilla survives because BufferBuilder clamps each component to [-1, 1] before *127; Reforged's
 * VertexUtil.packNormal does not, so (int) saturates and & 0xFF turns +huge into -1/127: flat items render at the
 * 0.4 ambient floor (dark hotbar icons).
 */
class NormalPackingTest {
    private static int vanilla(float x, float y, float z) {
        return component(x) | component(y) << 8 | component(z) << 16;
    }

    private static int component(float f) {
        // BufferBuilder.normalIntValue in 1.20.1
        return (byte) ((int) (Math.max(-1.0F, Math.min(1.0F, f)) * 127.0F)) & 255;
    }

    @Test
    void unitNormalsMatchVanilla() {
        assertEquals(vanilla(0, 0, 1), NormalPacking.pack(0, 0, 1));
        assertEquals(vanilla(0, 0, -1), NormalPacking.pack(0, 0, -1));
        assertEquals(vanilla(0.6F, -0.8F, 0), NormalPacking.pack(0.6F, -0.8F, 0));
    }

    @Test
    void hugeGuiNormalsClampLikeVanilla() {
        float huge = 2.1892130e25F;
        assertEquals(vanilla(0, 0, 1), NormalPacking.pack(0, 0, huge));
        assertEquals(vanilla(0, -1, 0), NormalPacking.pack(0, -huge, 0));
        assertEquals(127 << 16, NormalPacking.pack(0, 0, huge), "front face must stay +Z, not wrap to -1/127");
    }

    @Test
    void overwritesReforgedPackNormalWheneverVulkanModLoaded() throws Exception {
        String mixin = "reforged.VertexUtilMixin";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + mixin + "\""));
        }
        assertTrue(CompatGate.shouldApply(mixin, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(mixin, Set.<String>of()::contains));

        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/VertexUtilMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode target = node.invisibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        assertEquals(List.of("net.vulkanmod.render.vertex.VertexUtil"), target.values.get(target.values.indexOf("targets") + 1));
        MethodNode m = node.methods.stream().filter(x -> x.name.equals("packNormal") && x.desc.equals("(FFF)I"))
                .findFirst().orElseThrow();
        assertNotNull(m.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")).findFirst().orElse(null));
    }
}
