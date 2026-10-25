package net.vulkanmod.visuals.lod;

import net.vulkanmod.visuals.render.FogParameters;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * The LOD composite's uniforms (lod_composite.frag): the two projections that carry a LOD depth into the main depth, and the fog.
 * There is no LOD distance fade and no separate "fog covers everything" switch: the fog simply reaches full strength at its end
 * distance (a range that ends at 0 covers every LOD).
 */
public final class LodCompositeUniforms {
    // lod_composite.frag uniform locations
    public static final int LOD_CLIP_TO_VIEW = 0, VIEW_TO_MAIN_CLIP = 1, FOG_COLOUR = 2, FOG_RANGE = 3;

    public record Values(Matrix4f lodClipToView, Matrix4f viewToMainClip, Vector4f fogColour, Vector4f fogRange) {
        public void write(LooseUniformWriter w) {
            w.putMat4(LOD_CLIP_TO_VIEW, this.lodClipToView).putMat4(VIEW_TO_MAIN_CLIP, this.viewToMainClip);
            w.putVec4(FOG_COLOUR, this.fogColour.x, this.fogColour.y, this.fogColour.z, this.fogColour.w);
            w.putVec4(FOG_RANGE, this.fogRange.x, this.fogRange.y, this.fogRange.z, this.fogRange.w);
        }
    }

    private LodCompositeUniforms() {
    }

    /**
     * Both projections are view to clip with depth 0..1 (the main one is Reforged's remapped one, the LOD one as the LOD target was
     * drawn with). The fog is off (strength 0) unless its end is past its start.
     */
    public static Values compute(Matrix4fc lodProjection, Matrix4fc mainProjection, FogParameters fog) {
        Vector4f colour = new Vector4f(0, 0, 0, 0);
        Vector4f range = new Vector4f(0, 0, 0, 0);
        if (fog.alpha() > 0 && fog.environmentalEnd() > fog.environmentalStart()) {
            colour.set(fog.red(), fog.green(), fog.blue(), fog.alpha());
            range.set(fog.environmentalStart(), fog.environmentalEnd(), 0, 0);
        }
        return new Values(new Matrix4f(lodProjection).invert(), new Matrix4f(mainProjection), colour, range);
    }
}
