package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * 2026-11-09 (session 2b): the composite's (scene_copy.comp) underwater slots, read only with the camera in water (slot 29 .y):
 * <ul>
 * <li>PARAMS (35): x the water's open surface above the camera's eye (blocks; 0 with none: a column capped by a solid block, or the
 * camera not in water), y the underwater visibility setting (0: the composite draws no water of its own: the camera not in water, or
 * drawn() false), z the ripple strength, w the ripple density (settings, ratios)</li>
 * <li>RIPPLE_A, RIPPLE_B (36, 37): RippleMath.offsets, as the reflection's slots 11, 12</li>
 * <li>LIGHT (38): rgb the daylight entering the water at the surface (scene units; underwater.glsl dims it with depth; session 8: times
 * the picked underwater colour's tint), w 1 when the
 * depth before the translucent terrain is this frame's</li>
 * <li>AIR_SLOTS (39..45, 51..53): W4's struct Atmosphere of the air above (AtmosphereUniforms.block of the frame in the open air): the sky
 * Snell's window shows. ATMOSPHERE_NONE without sky light or when the atmosphere draws nothing (the window then shows vanilla's
 * pixel)</li>
 * </ul>
 */
public final class UnderwaterUniforms {
    public static final int PARAMS = 35, RIPPLE_A = 36, RIPPLE_B = 37, LIGHT = 38, AIR_FIRST = 39;
    /** 2026-11-10 (session 8): 51..53 the struct's colour tints. */
    public static final int[] AIR_SLOTS = {39, 40, 41, 42, 43, 44, 45, 51, 52, 53};
    /**
     * The daylight entering the water (scene units, per unit of WATER_BODY): by day, with the sun's colour (ColourMath.sunLight, its
     * TINT share) and rain dimming it (RAIN_DIM in full rain, THUNDER_DIM more in a storm); NIGHT at night (times the moonlight
     * setting). Tuned on the refworld U scenes against the references (session 2b).
     */
    public static final float DAY_LIGHT = 3.0f, NIGHT_LIGHT = 0.4f, TINT = 0.5f, RAIN_DIM = 0.45f, THUNDER_DIM = 0.35f;
    /** How far up the open surface is looked for (blocks). */
    public static final int SURFACE_SCAN = 64;

    public record Values(float surfaceUp, float visibility, float ripples, float density, float[] offsets, float[] light, boolean beforeValid,
                         float[] air) {
        public void write(LooseUniformWriter w) {
            if (w.has(PARAMS)) w.putVec4(PARAMS, this.surfaceUp, this.visibility, this.ripples, this.density);
            if (w.has(RIPPLE_A)) w.putVec4(RIPPLE_A, this.offsets[0], this.offsets[1], this.offsets[2], this.offsets[3]);
            if (w.has(RIPPLE_B)) w.putVec4(RIPPLE_B, this.offsets[4], this.offsets[5], this.offsets[6], this.offsets[7]);
            if (w.has(LIGHT)) w.putVec4(LIGHT, this.light[0], this.light[1], this.light[2], this.beforeValid ? 1 : 0);
            AtmosphereUniforms.writeBlock(w, this.air, AIR_SLOTS);
        }
    }

    private UnderwaterUniforms() {
    }

    /** The camera not in water, no frame, or not drawn(): zeros (visibility 0: the composite draws no water of its own). */
    public static Values off(PostProcessSettings s) {
        return new Values(0, 0, 0, 1, new float[8], new float[3], false, new float[AtmosphereUniforms.BLOCK_FLOATS]);
    }

    /**
     * Whether the composite draws the water under the camera itself (and UnderwaterFog pushes vanilla's fog away): with the
     * post-process on and a fog strength above 0. At fog strength 0 the water is vanilla's (every setting at 0 is vanilla's image).
     */
    public static boolean drawn(PostProcessSettings s) {
        return s != null && s.enabled() && s.fogStrength() > 0;
    }

    /**
     * frame: this frame's (serial); surfaceUp: the open surface above the eye (blocks, 0 none: surfaceAbove); skyAccess: the camera's
     * sky light 0..1, which dims the daylight where there is no open surface above (a flooded cave); beforeValid: the depth before the
     * translucent terrain was taken this frame at the main target's size.
     */
    public static Values compute(PostProcessSettings s, AtmosphereFrame frame, long serial, boolean hasSky, float surfaceUp, float skyAccess,
                                 boolean beforeValid) {
        if (!drawn(s) || frame == null || frame.serial() != serial || !frame.cameraInWater()) return off(s);
        float[] offsets = frame.rippleOffset() != null && frame.rippleOffset().length >= 8 ? frame.rippleOffset().clone() : new float[8];
        for (int i = 0; i < offsets.length; i++) if (!Float.isFinite(offsets[i])) offsets[i] = 0;
        float[] air = hasSky ? AtmosphereUniforms.block(s, frame.inOpenAir(), true) : new float[AtmosphereUniforms.BLOCK_FLOATS];
        if (hasSky && AtmosphereUniforms.drawsNothing(s)) air = AtmosphereUniforms.none(air);
        float up = surfaceUp > 0 && Float.isFinite(surfaceUp) ? surfaceUp : 0;
        float[] light = light(s, frame, hasSky);
        float access = up > 0 ? 1 : Math.max(0, Math.min(1, Float.isFinite(skyAccess) ? skyAccess : 0));
        //2026-11-10 (session 8): the picked underwater colour (ShaderColours.UNDERWATER) tints the light the water's own colour is lit by
        // (underwater.glsl uses slot 38 for nothing else), exactly 1 at its default
        float[] tint = s.colours().tint(ShaderColours.Colour.UNDERWATER);
        for (int i = 0; i < 3; i++) light[i] = light[i] * access * tint[i];
        return new Values(up, s.underwaterVisibility(), Math.max(0, s.waterRipples()), s.waterRippleDensity(), offsets, light, beforeValid, air);
    }

    /** The daylight entering the water at the surface (scene units), without the depth (see DAY_LIGHT). */
    static float[] light(PostProcessSettings s, AtmosphereFrame frame, boolean hasSky) {
        if (!hasSky) return new float[]{NIGHT_LIGHT, NIGHT_LIGHT, NIGHT_LIGHT};
        float h = AtmosphereUniforms.sunDirection(frame).y;
        float day = AtmosphereMath.smoothstep(AtmosphereUniforms.SUN_FADE_LOW, AtmosphereUniforms.SUN_FADE_HIGH, h);
        float rain = clamp01(frame.rain()), thunder = clamp01(frame.thunder());
        float dim = (1 - RAIN_DIM * rain) * (1 - THUNDER_DIM * thunder);
        float[] tint = ColourMath.sunLight(h);
        float night = NIGHT_LIGHT * clamp01(s.moonlight()) * (1 - day) * dim;
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = DAY_LIGHT * day * dim * (1 + (tint[i] - 1) * TINT) + night;
        return out;
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
