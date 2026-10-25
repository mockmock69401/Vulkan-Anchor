package dev.vulkanfix.compat;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged converts the projection to Vulkan's 0..1 depth range for its own shaders, but hands GLSL "legacy" shaders
 * (mod shaders such as xaerolib/*) RenderSystem's GL projection unchanged. Their geometry then lands at negative
 * depth and Vulkan clips it: every minimap draw came out at NDC z -0.9 / -0.802 and nothing reached the framebuffer.
 */
class LegacyProjectionTest {
    @Test
    void mapsGlDepthRangeOntoVulkans() {
        Matrix4f gl = new Matrix4f().setOrtho(0, 512, 512, 0, 1000, 21000);
        Matrix4f vk = LegacyProjection.toVulkanDepth(gl);
        for (float zEye : new float[]{-1000, -2000, -2980, -21000}) {
            Vector4f g = gl.transform(new Vector4f(100, 50, zEye, 1));
            Vector4f v = vk.transform(new Vector4f(100, 50, zEye, 1));
            assertEquals(g.x, v.x, 1e-5);
            assertEquals(g.y, v.y, 1e-5);
            assertEquals((g.z + g.w) / 2, v.z, 1e-5, "z' = (z + w) / 2");
        }
        Vector4f minimapTile = vk.transform(new Vector4f(0, 0, -2000, 1));
        assertTrue(minimapTile.z / minimapTile.w >= 0, "no longer clipped");
        assertEquals(new Matrix4f().setOrtho(0, 512, 512, 0, 1000, 21000), gl, "input untouched");
    }

    @Test
    void onlyRegisteredUniformsAreLegacyProjections() {
        Object legacyProjMat = new Object();
        LegacyProjection.register(legacyProjMat);
        assertTrue(LegacyProjection.isLegacyProjection(legacyProjMat));
        assertFalse(LegacyProjection.isLegacyProjection(new Object()));
        assertFalse(LegacyProjection.isLegacyProjection(null));
    }

    @Test
    void wiredIntoShaderCreationAndUniformSet() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"reforged.LegacyShaderRegisterMixin\""));
            assertTrue(json.contains("\"reforged.LegacyProjectionUniformMixin\""));
        }
        ClassNode register = read("dev/vulkanfix/mixin/reforged/LegacyShaderRegisterMixin.class");
        AnnotationNode mixin = annotation(register.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertTrue((Integer) value(mixin, "priority") > 1000, "must run after Reforged builds the legacy pipeline");
        AnnotationNode inject = handler(register, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals("RETURN", value(((List<AnnotationNode>) value(inject, "at")).get(0), "value"));

        ClassNode uniform = read("dev/vulkanfix/mixin/reforged/LegacyProjectionUniformMixin.class");
        AnnotationNode modify = handler(uniform, "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        assertEquals(List.of("m_5679_(Lorg/joml/Matrix4f;)V"), value(modify, "method"));
        assertEquals(Boolean.TRUE, value(modify, "argsOnly"));
    }

    private ClassNode read(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not compiled");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static AnnotationNode handler(ClassNode node, String desc) {
        for (MethodNode m : node.methods) {
            AnnotationNode a = annotation(m.visibleAnnotations, desc);
            if (a != null) {
                return a;
            }
        }
        throw new AssertionError("no " + desc + " handler in " + node.name);
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(AnnotationNode a, String key) {
        int i = a.values.indexOf(key);
        return i < 0 ? null : (T) a.values.get(i + 1);
    }
}
