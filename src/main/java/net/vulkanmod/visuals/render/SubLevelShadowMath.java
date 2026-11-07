package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** 2026-11-07: from a sub-level draw's model-view to a sun shadow map (SubLevelShadowCasters, VkSubLevelShadows). */
final class SubLevelShadowMath {
    private static final Vector3f CENTRE = new Vector3f();//the render thread's

    private SubLevelShadowMath() {
    }

    /** The draw's vertex space to camera-relative world space: the inverse of the camera's rotation times its model-view. */
    static Matrix4f toCameraWorld(Matrix4fc invView, Matrix4fc modelView, Matrix4f out) {
        return out.set(invView).mul(modelView);
    }

    /** Whether the section at offset (ox, oy, oz) of a draw with transform toWorld can reach the map of viewProjection. */
    static boolean reaches(Matrix4f viewProjection, Matrix4fc toWorld, float ox, float oy, float oz) {
        Vector3f c = toWorld.transformPosition(ox + 8, oy + 8, oz + 8, CENTRE);
        return VanillaShadowCasters.inLightBox(viewProjection, c.x, c.y, c.z);
    }
}
