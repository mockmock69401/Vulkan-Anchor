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

    /** 1.21.1: Reforged's VertexUtil.packNormal itself clamps (the fork's own code; no mixin). */
    @Test
    void reforgedsPackNormalClamps() {
        assertEquals(NormalPacking.pack(1e25f, -1e25f, 0.5f), net.vulkanmod.render.vertex.VertexUtil.packNormal(1e25f, -1e25f, 0.5f));
        assertEquals(NormalPacking.pack(1f, -1f, 0f), net.vulkanmod.render.vertex.VertexUtil.packNormal(1e25f, -1e25f, 0f));
    }
}
