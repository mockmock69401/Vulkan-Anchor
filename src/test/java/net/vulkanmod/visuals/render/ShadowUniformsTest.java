package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7j design 3.3: what the shadow uniforms carry. */
class ShadowUniformsTest {
    private static final Matrix4f GL_PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 2048);
    private static final Vector3f MORNING = new Vector3f(0.6f, 0.8f, 0);

    private static AtmosphereFrame frame() {
        return AtmosphereFrame.of(42, GL_PROJECTION, new Matrix4f(GL_PROJECTION), new Matrix4f(), 100, 63,
                new FogParameters(0.6f, 0.7f, 0.9f, 1f, -8, 1e6f, -8, 1e6f), 512, true, 0, 0, 0, null);
    }

    /** One map per cascade (the sun-step crossfade's second one absent). */
    private static VkShadowMaps.Maps maps(ShadowMath.Cascade c) {
        return new VkShadowMaps.Maps(c, null, 0, 1, 1);
    }

    private static LightingUniforms.Values lighting(boolean on, float sunWeight) {
        return new LightingUniforms.Values(on, new Matrix4f(), new Matrix4f(), new Matrix4f(), new float[]{0, 1, 0}, true, 1, sunWeight,
                new float[]{0, 1, 0}, true, 1, 1, 0, new float[]{1, 1, 1, 0, 0, 1, 1, 1}, 0, 1, 0, new float[]{1, 0, 0}, 0, 0, 0, 0);
    }

    @Test
    void offWithoutThisFramesNearCascadeOrSunShading() {
        ShadowMath.Cascade near = ShadowMath.cascade(MORNING, 0, 64, 0, ShadowMath.NEAR_RADIUS);
        assertFalse(ShadowUniforms.compute(lighting(true, 0.5f), frame(), null, null, null).near());
        assertFalse(ShadowUniforms.compute(lighting(true, 0), frame(), near, maps(near), null).near());
        assertFalse(ShadowUniforms.compute(lighting(false, 0.5f), frame(), near, maps(near), null).near());
        assertFalse(ShadowUniforms.compute(lighting(true, 0.5f), null, near, maps(near), null).near());
        ShadowUniforms.Values v = ShadowUniforms.compute(lighting(true, 0.5f), frame(), near, maps(near), null);
        assertTrue(v.near());
        assertFalse(v.far());
    }

    @Test
    void theMatricesTakeViewPositionsAndTheFarOneIsRebased() {
        ShadowMath.Cascade near = ShadowMath.cascade(MORNING, 103.5, 71, 198, ShadowMath.NEAR_RADIUS);
        ShadowMath.Cascade far = ShadowMath.cascade(MORNING, 100, 70, 200, ShadowMath.FAR_RADIUS);
        ShadowUniforms.Values v = ShadowUniforms.compute(lighting(true, 0.5f), frame(), near, maps(near), maps(far));
        assertTrue(v.near() && v.far());
        //identity model-view: view space is camera-relative world
        Vector4f p = new Vector4f(10, -3, 25, 1);
        Vector4f a = v.nearMatrix().transform(new Vector4f(p)), b = near.viewProjection().transform(new Vector4f(p));
        assertEquals(b.x, a.x, 1e-5f);
        assertEquals(b.z, a.z, 1e-5f);
        Vector4f f = v.farMatrix().transform(new Vector4f(p));
        Vector4f expected = far.viewProjection().transform(new Vector4f(10 + 3.5f, -3 + 1, 25 - 2, 1));
        assertEquals(expected.x, f.x, 1e-5f);
        assertEquals(expected.y, f.y, 1e-5f);
        assertEquals(expected.z, f.z, 1e-5f);
        assertEquals(ShadowMath.NORMAL_OFFSET_TEXELS * near.texel(), v.bias()[0], 1e-6f);
        assertEquals(ShadowMath.BIAS_TEXELS * near.texel() / near.depthRange(), v.bias()[1], 1e-9f);
        assertEquals(ShadowMath.NORMAL_OFFSET_TEXELS * far.texel(), v.bias()[2], 1e-6f);
        assertEquals(ShadowMath.BIAS_TEXELS * far.texel() / far.depthRange(), v.bias()[3], 1e-9f);
    }

    /** P7f-2: a near cascade drawn a few frames ago is read rebased to this frame's camera, as the far one is. */
    @Test
    void anOlderNearCascadeIsRebasedToThisFramesCamera() {
        ShadowMath.Cascade now = ShadowMath.cascade(MORNING, 103.5, 71, 198, ShadowMath.NEAR_RADIUS);
        ShadowMath.Cascade drawn = ShadowMath.cascade(MORNING, 101, 70, 199, ShadowMath.NEAR_RADIUS);
        ShadowUniforms.Values v = ShadowUniforms.compute(lighting(true, 0.5f), frame(), now, maps(drawn), null);
        assertTrue(v.near());
        Vector4f p = new Vector4f(10, -3, 25, 1);
        Vector4f a = v.nearMatrix().transform(new Vector4f(p));
        Vector4f expected = drawn.viewProjection().transform(new Vector4f(10 + 2.5f, -3 + 1, 25 - 1, 1));
        assertEquals(expected.x, a.x, 1e-5f);
        assertEquals(expected.y, a.y, 1e-5f);
        assertEquals(expected.z, a.z, 1e-5f);
        assertFalse(ShadowUniforms.compute(lighting(true, 0.5f), frame(), null, maps(drawn), null).near(), "no sun views this frame: off");
    }

    /** Sun-step crossfade (2026-10-16): each cascade's second map is rebased to this frame's camera too, with its weight. */
    @Test
    void theSecondMapsAreRebasedWithTheirWeights() {
        ShadowMath.Cascade now = ShadowMath.cascade(MORNING, 103.5, 71, 198, ShadowMath.NEAR_RADIUS);
        ShadowMath.Cascade a = ShadowMath.cascade(MORNING, 101, 70, 199, ShadowMath.NEAR_RADIUS);
        ShadowMath.Cascade b = ShadowMath.cascade(new Vector3f(0.59f, 0.81f, 0), 102, 70, 199, ShadowMath.NEAR_RADIUS);
        ShadowUniforms.Values v = ShadowUniforms.compute(lighting(true, 0.5f), frame(), now, new VkShadowMaps.Maps(a, b, 0.3f, 1, 2), null);
        assertEquals(0.3f, v.nearWeightB());
        assertEquals(0f, v.farWeightB(), "no far maps");
        Vector4f p = new Vector4f(10, -3, 25, 1);
        Vector4f got = v.nearMatrixB().transform(new Vector4f(p));
        Vector4f expected = b.viewProjection().transform(new Vector4f(10 + 1.5f, -3 + 1, 25 - 1, 1));
        assertEquals(expected.x, got.x, 1e-5f);
        assertEquals(expected.z, got.z, 1e-5f);
        //Without a second map its matrix is the primary's and its weight 0
        ShadowUniforms.Values one = ShadowUniforms.compute(lighting(true, 0.5f), frame(), now, maps(a), maps(a));
        assertEquals(one.nearMatrix(), one.nearMatrixB());
        assertEquals(0f, one.nearWeightB());
        assertEquals(0f, one.farWeightB());
    }

    /**
     * Texel crawl pass (2026-10-16): the close maps are read with the near ones only, rebased like them, with the near cascade's
     * normal offset and depth bias in blocks (the surfaces shade themselves as before, only the edges are finer).
     */
    @Test
    void theCloseMapsTakeTheNearOffsetsInBlocks() {
        ShadowMath.Cascade near = ShadowMath.cascade(MORNING, 100, 70, 200, ShadowMath.NEAR_RADIUS);
        ShadowMath.Cascade a = ShadowMath.cascade(MORNING, 101, 70, 199, ShadowMath.CLOSE_RADIUS);
        ShadowMath.Cascade b = ShadowMath.cascade(MORNING, 100.5, 70, 200, ShadowMath.CLOSE_RADIUS);
        assertFalse(ShadowUniforms.compute(lighting(true, 0.5f), frame(), near, null, null, maps(a)).close(), "no near maps: no maps at all");
        assertFalse(ShadowUniforms.compute(lighting(true, 0.5f), frame(), near, maps(near), null).close());
        ShadowUniforms.Values v = ShadowUniforms.compute(lighting(true, 0.5f), frame(), near, maps(near), null,
                new VkShadowMaps.Maps(a, b, 0.4f, 1, 2));
        assertTrue(v.close());
        assertEquals(v.bias()[0], v.closeParams()[0], 1e-7f);
        assertEquals(v.bias()[1] * near.depthRange() / a.depthRange(), v.closeParams()[1], 1e-7f);
        assertEquals(0.4f, v.closeParams()[2]);
        Vector4f p = new Vector4f(4, -2, 9, 1);
        Vector4f got = v.closeMatrix().transform(new Vector4f(p));
        Vector4f expected = a.viewProjection().transform(new Vector4f(4 - 1, -2, 9 + 1, 1));
        assertEquals(expected.x, got.x, 1e-5f);
        assertEquals(expected.z, got.z, 1e-5f);
        Vector4f gotB = v.closeMatrixB().transform(new Vector4f(p));
        Vector4f expectedB = b.viewProjection().transform(new Vector4f(4 - 0.5f, -2, 9, 1));
        assertEquals(expectedB.y, gotB.y, 1e-5f);
    }
}
