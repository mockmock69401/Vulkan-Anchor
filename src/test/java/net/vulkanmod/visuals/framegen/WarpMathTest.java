package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.render.TaaMath;
import net.vulkanmod.visuals.render.VkTaa;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WarpMathTest {
    static final int W = 2560, H = 1440;

    static Matrix4f proj() {
        return new Matrix4f().setPerspective((float) Math.toRadians(70), (float) W / H, 0.05f, 512f, true);
    }

    static TaaMath.Camera cam(float yaw, float pitch, double x, double y, double z) {
        return new TaaMath.Camera(proj(), CameraPrediction.modelView(yaw, pitch), x, y, z);
    }

    static void assertMat(Matrix4f a, Matrix4f b, float eps) {
        float[] fa = new float[16], fb = new float[16];
        a.get(fa);
        b.get(fb);
        for (int i = 0; i < 16; i++) assertEquals(fb[i], fa[i], eps, "element " + i);
    }

    @Test
    void viewPosIsExactWithATranslationInTheProjection() {
        Matrix4f bobbed = new Matrix4f().translation(0.05f, -0.08f, 0).mul(proj());
        Matrix4f inv = new Matrix4f(bobbed).invert();
        org.joml.Vector4f p = new org.joml.Vector4f(0.7f, -0.4f, -2.5f, 1f);
        org.joml.Vector4f clip = bobbed.transform(new org.joml.Vector4f(p));
        float u = (clip.x / clip.w * 0.5f + 0.5f), v = (clip.y / clip.w * 0.5f + 0.5f);
        float[] got = WarpMath.viewPos(inv, u, v, -p.z);
        assertEquals(p.x, got[0], 1e-3f);
        assertEquals(p.y, got[1], 1e-3f);
        assertEquals(p.z, got[2], 1e-3f);
    }

    @Test
    void targetFromViewIsPrevFromView() {
        TaaMath.Camera prev = cam(10f, -5f, 100.5, 64.25, -30.75);
        TaaMath.Camera now = cam(14f, 3f, 101.25, 64.5, -31.5);
        Matrix4f invMv = new Matrix4f(now.modelView()).invert();
        VkTaa.Frame f = new VkTaa.Frame(new Matrix4f(now.projection()).invert(), new Matrix4f(now.projection()).invert(), invMv,
                now.x(), now.y(), now.z(), false, 128f);
        assertMat(TaaMath.targetFromView(prev, invMv, now.x(), now.y(), now.z()), TaaMath.prevFromView(prev, f), 1e-6f);
    }

    @Test
    void unjitterUndoesTheJitter() {
        float jx = 0.0003f, jy = -0.0007f;
        Matrix4f p = proj();
        Matrix4f pj = new Matrix4f().translation(jx, jy, 0).mul(p);
        Matrix4f a = TaaMath.unjitter(pj, jx, jy).invert();
        Matrix4f b = TaaMath.unjitterInverse(new Matrix4f(pj).invert(), jx, jy);
        assertMat(a, b, 1e-5f);
    }

    @Test
    void anUnmovedCameraSplatsInPlace() {
        TaaMath.Camera c = cam(30f, 10f, 5, 70, 5);
        Matrix4f inv = new Matrix4f(c.projection()).invert();
        Matrix4f g = WarpMath.genFromReal(c, c);
        float[][] px = {{0.5f, 0.5f}, {W - 0.5f, 0.5f}, {0.5f, H - 0.5f}, {W - 0.5f, H - 0.5f}, {1000.5f, 300.5f}};
        for (float[] p : px) {
            float[] r = WarpMath.splatTarget(inv, g, p[0], p[1], 12f, W, H);
            assertNotNull(r);
            assertEquals(p[0], r[0], 1e-3f * Math.max(1, p[0] / 100));
            assertEquals(p[1], r[1], 1e-3f * Math.max(1, p[1] / 100));
            assertEquals(12f, r[2], 1e-3f);
        }
    }

    @Test
    void oneDegreeOfYawMovesTheCentre() {
        TaaMath.Camera real = cam(0f, 0f, 0, 0, 0), gen = cam(1f, 0f, 0, 0, 0);
        float[] r = WarpMath.splatTarget(new Matrix4f(real.projection()).invert(), WarpMath.genFromReal(gen, real), W / 2f, H / 2f, 10f, W, H);
        assertNotNull(r);
        assertEquals(-17.95f, r[0] - W / 2f, 0.1f);
        assertEquals(0f, r[1] - H / 2f, 0.1f);
    }

    @Test
    void theDepthToleranceIsTenPercent() {
        assertTrue(WarpMath.accept(11f, 10f));
        assertTrue(WarpMath.accept(9f, 10f));
        assertFalse(WarpMath.accept(11.2f, 10f));
        assertFalse(WarpMath.accept(8.8f, 10f));
    }

    @Test
    void edgesClampInside() {
        assertArrayEquals(new int[]{0, 700}, WarpMath.edgeClamp(-5, 700, W, H));
        assertArrayEquals(new int[]{2559, 0}, WarpMath.edgeClamp(2600, -3, W, H));
    }

    @Test
    void depthBitsOrder() {
        assertTrue(Integer.compareUnsigned(WarpMath.depthBits(1f), WarpMath.depthBits(2f)) < 0);
        assertTrue(Integer.compareUnsigned(WarpMath.depthBits(2f), WarpMath.depthBits(WarpMath.SKY_DEPTH)) < 0);
    }
}
