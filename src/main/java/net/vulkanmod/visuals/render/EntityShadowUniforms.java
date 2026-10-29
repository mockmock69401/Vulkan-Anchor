package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;

/**
 * 2026-10-29: lighting.comp's entity shadow map uniforms: 17 view space to the entity map's clip (VkEntityShadows.lightingMatrix),
 * 18 x 1 when this frame drew the map, y the normal offset (blocks), z the depth bias (map depth units).
 */
public final class EntityShadowUniforms {
    public static final int MATRIX = 17, PARAMS = 18;

    public record Values(boolean on, Matrix4f matrix, float normalOffset, float bias) {
        public void write(LooseUniformWriter w) {
            w.putMat4(MATRIX, this.matrix);
            w.putVec4(PARAMS, this.on ? 1 : 0, this.normalOffset, this.bias, 0);
        }
    }

    private EntityShadowUniforms() {
    }

    public static Values off() {
        return new Values(false, new Matrix4f(), 0, 0);
    }

    /**
     * On when this frame drew the map (drawn, the cascade it was drawn for) and the cascades' sun visibility is in use (shadow on);
     * read by frame's inverse model-view from this frame's sun view now. The offsets are the cascades' in texels of this map.
     */
    public static Values compute(ShadowMath.Cascade drawn, ShadowMath.Cascade now, AtmosphereFrame frame, boolean shadowOn) {
        if (drawn == null || now == null || frame == null || !shadowOn) return off();
        return new Values(true, VkEntityShadows.lightingMatrix(drawn, now, frame), ShadowMath.NORMAL_OFFSET_TEXELS * drawn.texel(),
                ShadowMath.BIAS_TEXELS * drawn.texel() / drawn.depthRange());
    }
}
