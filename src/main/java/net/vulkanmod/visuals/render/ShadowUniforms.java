package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;

/** P7j design 3.3: lighting.comp's shadow uniforms: the cascades' view-to-shadow-clip matrices, which are on, the offsets and biases. */
public final class ShadowUniforms {
    public static final int NEAR = 6, FAR = 7, PARAMS = 8, BIAS = 9;
    /** Sun-step crossfade (2026-10-16), lighting.comp only: each cascade's second map and the second maps' weights (near, far). */
    public static final int NEAR_B = 10, FAR_B = 11, STEPS = 12;
    /** Texel crawl pass (2026-10-16), lighting.comp only: the close cascade's two maps and its on, normal offset, depth bias, second weight. */
    public static final int CLOSE = 13, CLOSE_B = 14, CLOSE_PARAMS = 15;
    /** scene_copy.comp: 1 when the maps lit this frame (the deeper sun/shadow parting, gate G7j). */
    public static final int COPY = 17;

    /**
     * bias: the near normal offset (blocks) and depth bias (depth units), then the far ones. The B matrices and weights are each
     * cascade's second map (sun-step crossfade); weight 0 when there is none (the matrix is then the primary's).
     */
    public record Values(boolean near, boolean far, Matrix4f nearMatrix, Matrix4f farMatrix, float[] bias, Matrix4f nearMatrixB,
                         Matrix4f farMatrixB, float nearWeightB, float farWeightB, boolean close, Matrix4f closeMatrix, Matrix4f closeMatrixB,
                         float[] closeParams) {
        /** The primary maps: lighting.comp and volumetric.comp. */
        public void write(LooseUniformWriter w) {
            w.putMat4(NEAR, this.nearMatrix).putMat4(FAR, this.farMatrix);
            w.putVec4(PARAMS, this.near ? 1 : 0, this.far ? 1 : 0, ShadowMath.BLEND, 0);
            w.putVec4(BIAS, this.bias[0], this.bias[1], this.bias[2], this.bias[3]);
        }

        /** The second maps: lighting.comp only. */
        public void writeSteps(LooseUniformWriter w) {
            w.putMat4(NEAR_B, this.nearMatrixB).putMat4(FAR_B, this.farMatrixB);
            w.putVec4(STEPS, this.nearWeightB, this.farWeightB, 0, 0);
        }

        /** The close cascade: lighting.comp only. */
        public void writeClose(LooseUniformWriter w) {
            w.putMat4(CLOSE, this.closeMatrix).putMat4(CLOSE_B, this.closeMatrixB);
            w.putVec4(CLOSE_PARAMS, this.close ? 1 : 0, this.closeParams[0], this.closeParams[1], this.closeParams[2]);
        }

        public void writeCopy(LooseUniformWriter w) {
            w.putVec4(COPY, this.near || this.far ? 1 : 0, 0, 0, 0);
        }
    }

    private ShadowUniforms() {
    }

    /** No shadow maps: lighting.comp keeps P7d's contact shadow. */
    public static Values off() {
        return new Values(false, false, new Matrix4f(), new Matrix4f(), new float[4], new Matrix4f(), new Matrix4f(), 0, 0, false, new Matrix4f(),
                new Matrix4f(), new float[3]);
    }

    /**
     * On with this frame's sun shading (lighting on, weight above 0), this frame's sun views (now) and valid near maps; the far
     * ones when valid. Every map is rebased to this frame's camera (they are a few frames old, drawn for their sun steps). The
     * matrices take lighting.comp's view-space positions (frame's inverse model-view).
     */
    public static Values compute(LightingUniforms.Values lighting, AtmosphereFrame frame, ShadowMath.Cascade now, VkShadowMaps.Maps near,
                                 VkShadowMaps.Maps far) {
        return compute(lighting, frame, now, near, far, null);
    }

    /**
     * Texel crawl pass (2026-10-16): with the close maps too (null: none), read only with the near ones. Their normal offset and
     * depth bias are the near cascade's in blocks, so a surface shades itself as before, only its edges are finer.
     */
    public static Values compute(LightingUniforms.Values lighting, AtmosphereFrame frame, ShadowMath.Cascade now, VkShadowMaps.Maps near,
                                 VkShadowMaps.Maps far, VkShadowMaps.Maps close) {
        if (!lighting.on() || lighting.sunWeight() <= 0 || frame == null || now == null || near == null) return off();
        Matrix4f nearMatrix = toNow(near.a(), now, frame);
        Matrix4f farMatrix = far == null ? new Matrix4f() : toNow(far.a(), now, frame);
        Matrix4f nearMatrixB = near.b() == null ? nearMatrix : toNow(near.b(), now, frame);
        Matrix4f farMatrixB = far == null || far.b() == null ? farMatrix : toNow(far.b(), now, frame);
        ShadowMath.Cascade n = near.a();
        float farTexel = far == null ? 0 : far.a().texel();
        Matrix4f closeMatrix = close == null ? new Matrix4f() : toNow(close.a(), now, frame);
        Matrix4f closeMatrixB = close == null || close.b() == null ? closeMatrix : toNow(close.b(), now, frame);
        float[] closeParams = close == null ? new float[3] : new float[]{ShadowMath.NORMAL_OFFSET_TEXELS * n.texel(),
                ShadowMath.BIAS_TEXELS * n.texel() / close.a().depthRange(), close.b() == null ? 0 : close.weightB()};
        return new Values(true, far != null, nearMatrix, farMatrix, new float[]{
                ShadowMath.NORMAL_OFFSET_TEXELS * n.texel(), ShadowMath.BIAS_TEXELS * n.texel() / n.depthRange(),
                ShadowMath.NORMAL_OFFSET_TEXELS * farTexel, far == null ? 0 : ShadowMath.BIAS_TEXELS * farTexel / far.a().depthRange()},
                nearMatrixB, farMatrixB, near.b() == null ? 0 : near.weightB(), far == null || far.b() == null ? 0 : far.weightB(),
                close != null, closeMatrix, closeMatrixB, closeParams);
    }

    private static Matrix4f toNow(ShadowMath.Cascade c, ShadowMath.Cascade now, AtmosphereFrame frame) {
        return ShadowMath.rebase(c.viewProjection(), c.cameraX(), c.cameraY(), c.cameraZ(), now.cameraX(), now.cameraY(), now.cameraZ())
                .mul(frame.invModelView());
    }
}
