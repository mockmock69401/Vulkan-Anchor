package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DH builds its FORWARD_Z projection the way GL does (depth -1..1 at the near and far planes, RenderUtil.setClipPlanes); the
 * LOD target is drawn with Vulkan's 0..1 depth, so DhDepth.toVulkan applies z' = 0.5 z + 0.5 w.
 */
class DhDepthTest {
    private static final float NEAR = 99.1f, FAR = 9216f;

    /** MC's projection (fov 70, 16:9) with DH's clip planes, as RenderUtil.setClipPlanes writes them (row-major, -1..1). */
    private static DhApiMat4f dhProjection(float near, float far) {
        Matrix4f gl = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f);
        DhApiMat4f m = new DhApiMat4f(new float[]{
                gl.m00(), gl.m10(), gl.m20(), gl.m30(),
                gl.m01(), gl.m11(), gl.m21(), gl.m31(),
                gl.m02(), gl.m12(), gl.m22(), gl.m32(),
                gl.m03(), gl.m13(), gl.m23(), gl.m33()});
        m.m22 = (far + near) / (near - far);
        m.m23 = (far + far) * near / (near - far);
        return m;
    }

    private static float depthAt(Matrix4f projection, float distance) {
        Vector4f clip = projection.transform(new Vector4f(3, -2, -distance, 1));
        return clip.z / clip.w;
    }

    @Test
    void dhDepthIsMinusOneToOneBeforeTheRemap() {
        Matrix4f dh = DhDepth.fromDh(dhProjection(NEAR, FAR));
        assertEquals(-1f, depthAt(dh, NEAR), 1e-5f);
        assertEquals(1f, depthAt(dh, FAR), 1e-5f);
    }

    @Test
    void theNearPlaneLandsAtZeroAndTheFarPlaneAtOne() {
        Matrix4f vk = DhDepth.toVulkan(DhDepth.fromDh(dhProjection(NEAR, FAR)));
        assertEquals(0f, depthAt(vk, NEAR), 1e-5f);
        assertEquals(1f, depthAt(vk, FAR), 1e-5f);
    }

    @Test
    void everyDepthIsHalfwayRemapped() {
        Matrix4f dh = DhDepth.fromDh(dhProjection(7.5f, 9216f));
        Matrix4f vk = DhDepth.toVulkan(dh);
        for (float d : new float[]{7.5f, 64f, 512f, 4000f, 9216f}) {
            assertEquals(0.5f * depthAt(dh, d) + 0.5f, depthAt(vk, d), 1e-5f, "at " + d);
        }
    }

    /** Only the depth row changes: the picture (x, y, w) is DH's. */
    @Test
    void xyAndWAreUntouched() {
        Matrix4f dh = DhDepth.fromDh(dhProjection(NEAR, FAR));
        Matrix4f vk = DhDepth.toVulkan(dh);
        Vector4f a = dh.transform(new Vector4f(5, 7, -300, 1));
        Vector4f b = vk.transform(new Vector4f(5, 7, -300, 1));
        assertEquals(a.x, b.x, 1e-4f);
        assertEquals(a.y, b.y, 1e-4f);
        assertEquals(a.w, b.w, 1e-4f);
    }

    /** DH's matrix names are row then column (m23 is the depth row's translation); JOML's are column then row. */
    @Test
    void dhMatricesAreReadRowMajor() {
        DhApiMat4f m = new DhApiMat4f();
        m.setIdentity();
        m.m03 = 10;
        m.m13 = 20;
        m.m23 = 30;
        Vector4f p = DhDepth.fromDh(m).transform(new Vector4f(1, 2, 3, 1));
        assertEquals(11, p.x, 0);
        assertEquals(22, p.y, 0);
        assertEquals(33, p.z, 0);
        assertEquals(1, p.w, 0);
    }
}
