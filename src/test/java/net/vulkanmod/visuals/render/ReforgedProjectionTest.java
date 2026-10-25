package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * frame() gets vanilla's GL projection (depth -1..1; P7c diagnostics: m22 ~ -1, m32 = -0.1 = -2 near); Reforged draws terrain
 * with it remapped to 0..1 (VRenderSystem.applyProjectionMatrix, z' = (z + w) / 2), so its main depth holds 0..1 depth.
 */
class ReforgedProjectionTest {
    static final Matrix4f GL_PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1024);
    static final Matrix4f MODEL_VIEW = new Matrix4f().rotateX(0.2f).rotateY(0.7f);

    /** What Reforged's terrain writes for a camera-relative point: GL NDC depth mapped to 0..1, at the GL screen position. */
    static Vector4f reforgedScreen(float x, float y, float z) {
        Vector4f clip = new Matrix4f(GL_PROJECTION).mul(MODEL_VIEW).transform(new Vector4f(x, y, z, 1));
        return new Vector4f(clip.x / clip.w, clip.y / clip.w, (clip.z / clip.w + 1) / 2, 1);
    }

    @Test
    void vulkanDepthIsVRenderSystemsRemap() {
        Matrix4f vk = ReforgedProjection.vulkanDepth(GL_PROJECTION);
        for (float d : new float[]{0.5f, 8, 50, 900}) {
            Vector4f gl = GL_PROJECTION.transform(new Vector4f(0.3f, -0.2f, -d, 1), new Vector4f());
            Vector4f v = vk.transform(new Vector4f(0.3f, -0.2f, -d, 1), new Vector4f());
            assertEquals(gl.x, v.x, 1e-6f);
            assertEquals(gl.y, v.y, 1e-6f);
            assertEquals(gl.w, v.w, 1e-6f);
            assertEquals((gl.z / gl.w + 1) / 2, v.z / v.w, 1e-6f, "depth at " + d);
        }
    }

    /** vanilla_distance.comp: invVanillaMvp * (ndc xy, depth, 1) with depth read as 0..1 NDC, then length(xz / w). */
    @Test
    void invMvpGivesVanillaDistanceTheTrueHorizontalDistance() {
        Matrix4f inv = ReforgedProjection.invMvp(GL_PROJECTION, MODEL_VIEW);
        for (float[] p : new float[][]{{3, -1, -4}, {20, 5, -45}, {-120, 30, -200}, {400, -60, 350}}) {
            Vector4f screen = reforgedScreen(p[0], p[1], p[2]);
            Vector4f world = inv.transform(screen);
            float expected = (float) Math.hypot(p[0], p[2]);
            assertEquals(expected, (float) Math.hypot(world.x / world.w, world.z / world.w), expected * 2e-3f,
                    "distance of " + p[0] + "," + p[1] + "," + p[2]);
        }
    }
}
