package dev.vulkanfix.compat;

import org.joml.Matrix4f;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ProjMat uniforms of Reforged "legacy" (GLSL-converted) shaders. Reforged fills them with RenderSystem's GL
 * projection (depth -1..1) while Vulkan clips depth below 0; they get the same z remap Reforged applies to its own
 * shaders (VRenderSystem.applyProjectionMatrix): z' = (z + w) / 2.
 */
public final class LegacyProjection {
    private static final Set<Object> UNIFORMS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private LegacyProjection() {
    }

    public static void register(Object projMatUniform) {
        UNIFORMS.add(projMatUniform);
    }

    public static boolean isLegacyProjection(Object uniform) {
        return uniform != null && UNIFORMS.contains(uniform);
    }

    public static Matrix4f toVulkanDepth(Matrix4f gl) {
        Matrix4f vk = new Matrix4f(gl);
        vk.m02((gl.m02() + gl.m03()) * 0.5F);
        vk.m12((gl.m12() + gl.m13()) * 0.5F);
        vk.m22((gl.m22() + gl.m23()) * 0.5F);
        vk.m32((gl.m32() + gl.m33()) * 0.5F);
        return vk;
    }
}
