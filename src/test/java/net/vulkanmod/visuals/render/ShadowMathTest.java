package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7j design 3.1, 3.3: the sun's orthographic views around the camera. */
class ShadowMathTest {
    private static final Vector3f MORNING = new Vector3f(0.6f, 0.8f, 0);

    private static Vector3f clip(ShadowMath.Cascade c, double px, double py, double pz) {
        Vector4f v = c.viewProjection().transform(new Vector4f((float) (px - c.cameraX()), (float) (py - c.cameraY()), (float) (pz - c.cameraZ()), 1));
        return new Vector3f(v.x, v.y, v.z);
    }

    @Test
    void theSunIsBehindTheView() {
        Vector3f d = ShadowMath.lightView(MORNING).transformDirection(new Vector3f(MORNING).normalize());
        assertEquals(1f, d.z, 1e-5f);
    }

    @Test
    void theBoxSpansTheRadiusAndTheDepthRange() {
        ShadowMath.Cascade c = ShadowMath.cascade(MORNING, 0, 64, 0, ShadowMath.NEAR_RADIUS);
        Vector3f toSun = new Vector3f(MORNING).normalize();
        float far = ShadowMath.NEAR_RADIUS + ShadowMath.DEPTH_MARGIN;
        assertEquals(0f, clip(c, toSun.x * far, 64 + toSun.y * far, 0).z, 1e-4f);
        assertEquals(1f, clip(c, -toSun.x * ShadowMath.NEAR_RADIUS, 64 - toSun.y * ShadowMath.NEAR_RADIUS, 0).z, 1e-4f);
        Vector3f centre = clip(c, 0, 64, 0);
        float texelNdc = 2f / ShadowMath.MAP_SIZE;
        assertTrue(Math.abs(centre.x) <= texelNdc + 1e-5f && Math.abs(centre.y) <= texelNdc + 1e-5f, centre.toString());
        assertEquals(2 * ShadowMath.NEAR_RADIUS / ShadowMath.MAP_SIZE, c.texel(), 1e-6f);
        assertEquals(2 * ShadowMath.NEAR_RADIUS + ShadowMath.DEPTH_MARGIN, c.depthRange(), 1e-3f);
    }

    /** Design 3.1: the texel grid is fixed in the world: moving the camera shifts a world point by whole texels only. */
    @Test
    void theTexelGridStaysInTheWorld() {
        double px = 1234.56, py = 70.3, pz = -9876.5;
        ShadowMath.Cascade a = ShadowMath.cascade(MORNING, 1200.1, 70, -9850.2, ShadowMath.NEAR_RADIUS);
        for (double step : new double[]{0.01, 0.07, 0.13, 0.5, 3.3}) {
            ShadowMath.Cascade b = ShadowMath.cascade(MORNING, 1200.1 + step, 70 + step, -9850.2 - step, ShadowMath.NEAR_RADIUS);
            Vector3f ca = clip(a, px, py, pz), cb = clip(b, px, py, pz);
            float texelsX = (ca.x - cb.x) * ShadowMath.MAP_SIZE / 2, texelsY = (ca.y - cb.y) * ShadowMath.MAP_SIZE / 2;
            assertEquals(Math.round(texelsX), texelsX, 2e-3f, "x " + step);
            assertEquals(Math.round(texelsY), texelsY, 2e-3f, "y " + step);
        }
    }

    /** Wobble pass (2026-10-16): the close map, wider across the sun's path, holds its grid per axis (its texels there are 4x finer). */
    @Test
    void theCloseGridStaysInTheWorldPerAxis() {
        double px = 1234.56, py = 70.3, pz = -9876.5;
        ShadowMath.Cascade a = ShadowMath.cascade(MORNING, 1200.1, 70, -9850.2, ShadowMath.CLOSE_RADIUS, ShadowMath.CLOSE_WIDTH, ShadowMath.CLOSE_HEIGHT);
        assertEquals(2 * ShadowMath.CLOSE_RADIUS / ShadowMath.CLOSE_HEIGHT, a.texel(), 1e-6f);
        for (double step : new double[]{0.003, 0.01, 0.07, 0.13, 0.5, 3.3}) {
            ShadowMath.Cascade b = ShadowMath.cascade(MORNING, 1200.1 + step, 70 + step, -9850.2 - step, ShadowMath.CLOSE_RADIUS, ShadowMath.CLOSE_WIDTH,
                    ShadowMath.CLOSE_HEIGHT);
            Vector3f ca = clip(a, px, py, pz), cb = clip(b, px, py, pz);
            float texelsX = (ca.x - cb.x) * ShadowMath.CLOSE_WIDTH / 2, texelsY = (ca.y - cb.y) * ShadowMath.CLOSE_HEIGHT / 2;
            assertEquals(Math.round(texelsX), texelsX, 8e-3f, "x " + step);
            assertEquals(Math.round(texelsY), texelsY, 2e-3f, "y " + step);
        }
    }

    /** Final review: the grid depends on the camera's place in its ANCHOR cell only, so far from the origin the turning sun does not slide it. */
    @Test
    void theGridIsAnchoredAtTheCamerasCell() {
        ShadowMath.Cascade home = ShadowMath.cascade(MORNING, 3.3, 70.2, 5.5, ShadowMath.NEAR_RADIUS);
        ShadowMath.Cascade far = ShadowMath.cascade(MORNING, 3.3 + 625 * ShadowMath.ANCHOR, 70.2, 5.5 - 400 * ShadowMath.ANCHOR, ShadowMath.NEAR_RADIUS);
        assertTrue(home.projection().equals(far.projection(), 1e-5f), home.projection() + "\n" + far.projection());
    }

    @Test
    void theCascadesBlendAtTheNearEdge() {
        assertEquals(0f, ShadowMath.blendWeight(0.5f));
        assertEquals(0f, ShadowMath.blendWeight(1 - ShadowMath.BLEND));
        assertEquals(1f, ShadowMath.blendWeight(1f));
        assertEquals(0.5f, ShadowMath.blendWeight(1 - ShadowMath.BLEND / 2), 1e-6f);
    }

    /** Fork design 4.3: vanilla casters reach 64 blocks in the close and near cascades, the render distance (capped) in the far one. */
    @Test
    void vanillaRadius() {
        assertEquals(64f, ShadowMath.vanillaRadius(ShadowMath.Which.CLOSE, 32));
        assertEquals(64f, ShadowMath.vanillaRadius(ShadowMath.Which.NEAR, 32));
        assertEquals(192f, ShadowMath.vanillaRadius(ShadowMath.Which.FAR, 12));
        assertEquals(512f, ShadowMath.vanillaRadius(ShadowMath.Which.FAR, 32));
        assertEquals(512f, ShadowMath.FAR_VANILLA_MAX);
    }

    /** 2026-10-29: the shadow distance (chunks) moves the close and near cascades' reach, kept within 2 chunks and the near box; not the far one's. */
    @Test
    void theShadowDistanceSetsTheNearReach() {
        assertEquals(64f, ShadowMath.nearReach(new net.vulkanmod.visuals.config.VisualsConfig().shadowDistance));
        assertEquals(ShadowMath.NEAR_RADIUS, ShadowMath.nearReach(12));
        assertEquals(32f, ShadowMath.nearReach(0));
        assertEquals(128f, ShadowMath.vanillaRadius(ShadowMath.Which.CLOSE, 32, ShadowMath.nearReach(8)));
        assertEquals(512f, ShadowMath.vanillaRadius(ShadowMath.Which.FAR, 32, ShadowMath.nearReach(8)));
        //2026-10-29: with LODs casting past it, the far cascade's vanilla terrain ends at the shadow distance as well
        assertEquals(128f, ShadowMath.vanillaRadius(ShadowMath.Which.FAR, 32, ShadowMath.nearReach(8), true));
        assertEquals(64f, ShadowMath.vanillaRadius(ShadowMath.Which.NEAR, 12, ShadowMath.nearReach(4), true));
    }

    /** Design 3.2: the far cascade drawn frames ago is read relative to this frame's camera. */
    @Test
    void anOldCascadeIsRebasedToThisCamera() {
        ShadowMath.Cascade then = ShadowMath.cascade(MORNING, 100, 70, 200, ShadowMath.FAR_RADIUS);
        Matrix4f now = ShadowMath.rebase(then.viewProjection(), 100, 70, 200, 103.5, 71, 198);
        Vector4f expected = then.viewProjection().transform(new Vector4f(150 - 100, 90 - 70, 260 - 200, 1));
        Vector4f actual = now.transform(new Vector4f((float) (150 - 103.5), 90 - 71, 260 - 198, 1));
        assertEquals(expected.x, actual.x, 1e-5f);
        assertEquals(expected.y, actual.y, 1e-5f);
        assertEquals(expected.z, actual.z, 1e-5f);
    }
}
