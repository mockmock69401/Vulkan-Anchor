package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The vanilla projection frame() receives is GL's (depth -1..1); Reforged draws its own shaders (terrain, water) with it
 * remapped to 0..1, so the main depth holds 0..1 depth. Every pass that reads or writes the main depth through vanilla's
 * projection goes through the same remap.
 */
public final class ReforgedProjection {
    private ReforgedProjection() {
    }

    /** Reforged's VRenderSystem.applyProjectionMatrix: z' = (z + w) / 2. Public for frame generation's camera (FG design 3.3). */
    public static Matrix4f vulkanDepth(Matrix4fc gl) {
        Matrix4f vk = new Matrix4f(gl);
        vk.m02((gl.m02() + gl.m03()) * 0.5f);
        vk.m12((gl.m12() + gl.m13()) * 0.5f);
        vk.m22((gl.m22() + gl.m23()) * 0.5f);
        vk.m32((gl.m32() + gl.m33()) * 0.5f);
        return vk;
    }

    /** The inverse of the remapped projection times the model view: main depth (read as 0..1 NDC) to camera-relative world. */
    static Matrix4f invMvp(Matrix4fc gl, Matrix4fc modelView) {
        return vulkanDepth(gl).mul(modelView).invert();
    }
}
