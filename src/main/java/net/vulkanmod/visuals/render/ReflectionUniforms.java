package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;

/**
 * W6: reflection.comp's uniforms (boundary 3.3). W6 owns locations 0..13 and 23..29: 0 invVanillaProj, 1 invLodProj, 2 proj (view to clip),
 * 3 depthParams (x LodClamp.CLAMP_THRESHOLD, y 1 when this frame's LOD depth is valid) with the fixed meanings; 4, 5, 6, 7, 9, 10, 23 W4's
 * struct Atmosphere in field order (AtmosphereUniforms.writeBlock); 8 the view to world matrix (camera relative); 11, 12 RippleMath.offsets;
 * 13 x the ripple strength (setting), y the reflection strength (setting), z the camera's sky exposure, w 1 for the reflective debug view;
 * 24 (2026-10-29) x the block reflections setting, y the frame number (the rough rays' noise); 25..29 reserved, written 0. The cloud block (14..18) and the light volume (19..22) are written by their owners.
 */
public final class ReflectionUniforms {
    /** Blocks; vanilla's sky fog end when it reports none. */
    public static final float DEFAULT_SKY_FOG_END = 256;

    public static final int INV_VANILLA_PROJ = 0, INV_LOD_PROJ = 1, PROJ = 2, DEPTH = 3, INV_VIEW = 8, RIPPLE_A = 11, RIPPLE_B = 12, PARAMS = 13, BLOCK = 24;
    /** Where the Atmosphere struct goes, in field order (sun, sunLight, moonLight, depth, ambient, glow, fog). */
    public static final int[] ATMOSPHERE_SLOTS = {4, 5, 6, 7, 9, 10, 23};
    public static final int RESERVED_FIRST = 25, RESERVED_LAST = 29;

    /**
     * on: the pass runs this frame. block: AtmosphereUniforms.block's 28 floats; offsets: RippleMath.offsets' 8 floats; ripples and
     * strength the settings (ratios), skyExposure 0..1, debug the reflective debug view.
     */
    public record Values(boolean on, Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f projection, boolean lodValid,
                         Matrix4f invView, float[] block, float[] offsets, float ripples, float strength, float skyExposure, boolean debug,
                         int blockReflections, int frame) {
        public void write(LooseUniformWriter w) {
            w.putMat4(INV_VANILLA_PROJ, this.invVanillaProjection).putMat4(INV_LOD_PROJ, this.invLodProjection).putMat4(PROJ, this.projection);
            w.putVec4(DEPTH, LodClamp.CLAMP_THRESHOLD, this.lodValid ? 1 : 0, 0, 0);
            w.putMat4(INV_VIEW, this.invView);
            for (int i = 0; i < ATMOSPHERE_SLOTS.length; i++) {
                w.putVec4(ATMOSPHERE_SLOTS[i], this.block[i * 4], this.block[i * 4 + 1], this.block[i * 4 + 2], this.block[i * 4 + 3]);
            }
            w.putVec4(RIPPLE_A, this.offsets[0], this.offsets[1], this.offsets[2], this.offsets[3]);
            w.putVec4(RIPPLE_B, this.offsets[4], this.offsets[5], this.offsets[6], this.offsets[7]);
            w.putVec4(PARAMS, this.ripples, this.strength, this.skyExposure, this.debug ? 1 : 0);
            //the frame number wraps well inside float's exact integers
            w.putVec4(BLOCK, this.blockReflections, this.frame & 0xFFFF, 0, 0);
            for (int slot = RESERVED_FIRST; slot <= RESERVED_LAST; slot++) w.putVec4(slot, 0, 0, 0, 0);
        }
    }

    private ReflectionUniforms() {
    }

    /**
     * On when the lighting pass runs this frame (its translucent kinds give the composite's alpha) with water reflections chosen
     * (LightingChoice: a sky light dimension, no covering environmental fog) and a frame of the same serial; off() otherwise, also
     * with the reflections setting at 0. night is not needed: the atmosphere block carries it.
     */
    public static Values compute(PostProcessSettings settings, LightingChoice choice, LightingUniforms.Values lighting, AtmosphereFrame frame,
                                 float night, float skyExposure) {
        return compute(settings, choice, lighting, frame, night, skyExposure, 0);
    }

    /** As above with the frame number for the block reflections' rough rays (2026-10-29: they also turn the pass on, LightingChoice.reflectionPass). */
    public static Values compute(PostProcessSettings settings, LightingChoice choice, LightingUniforms.Values lighting, AtmosphereFrame frame,
                                 float night, float skyExposure, int frameId) {
        if (settings == null || choice == null || lighting == null || frame == null) return off();
        if (!settings.enabled() || !LightingChoice.reflectionPass(settings) || !choice.reflections() || !lighting.on() || choice.serial() != frame.serial()) {
            return off();
        }
        float[] offsets = frame.rippleOffset() != null && frame.rippleOffset().length >= 8 ? frame.rippleOffset().clone() : new float[8];
        for (int i = 0; i < offsets.length; i++) if (!Float.isFinite(offsets[i])) offsets[i] = 0;
        float exposure = Float.isFinite(skyExposure) ? Math.max(0, Math.min(1, skyExposure)) : 1;
        return new Values(true, new Matrix4f(frame.invVanillaProjection()), new Matrix4f(frame.invLodProjection()),
                new Matrix4f(frame.invVanillaProjection()).invert(), lighting.lodValid(), new Matrix4f(frame.invModelView()),
                AtmosphereUniforms.block(settings, frame, frame.skyColour() != null), offsets, Math.max(0, settings.waterRipples()),
                Math.min(1, settings.waterReflections()), exposure, settings.lightDebug() == PostProcessSettings.REFLECTIVE_DEBUG,
                settings.blockReflections(), frameId);
    }

    /** The pass is skipped; write() still fills every owned slot with finite values. */
    public static Values off() {
        return new Values(false, new Matrix4f(), new Matrix4f(), new Matrix4f(), false, new Matrix4f(), new float[AtmosphereMath.Atmosphere.VEC4S * 4],
                new float[8], 0, 0, 0, false, 0, 0);
    }
}
