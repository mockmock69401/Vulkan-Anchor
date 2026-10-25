package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;

/**
 * W8: light shafts through the sun's shadow cascades. volumetric.comp marches each half-resolution texel's view ray through the
 * near and far cascades and writes the in-scattered amount (VolumetricMath) to the mask image; volumetric_blur.comp removes the
 * ray offsets' ordered dither into the shaft image. The final pass (W2) adds {@code shaftLight * light-shafts setting * shaft}.
 *
 * <p>volumetric.comp's loose uniforms, besides the clean ShadowUniforms.write at 6..9:
 * <ul>
 * <li>0 invVanillaProj, 1 invLodProj (mat4), 2 depthParams (x LodClamp.CLAMP_THRESHOLD, y 1 when the LOD depth is valid): viewpos.glsl</li>
 * <li>3: xyz the direction toward the sun in view space</li>
 * <li>4: x the longest ray in blocks (the drawn cascades' reach, at most the view distance)</li>
 * </ul>
 */
public final class VolumetricLight {
    public static final int INV_VANILLA_PROJ = 0, INV_LOD_PROJ = 1, DEPTH = 2, SUN = 3, MARCH = 4;

    // ---- the shaft colour (Java only; each is one tuning knob) ----

    /** Scene units per unit of mask with the sun low (just above HORIZON_FADE) and at noon; in between by (1 - height)^LOW_SUN_POWER. */
    public static final float LOW_SUN_AMOUNT = 0.08f, NOON_AMOUNT = 0.03f;
    public static final float LOW_SUN_POWER = 2f;
    /** The shafts fade in over this sun height above the horizon (none at sunrise/sunset's first instant, no pop). */
    public static final float HORIZON_FADE = 0.06f;
    /** Full rain keeps 1 - RAIN_CUT of the amount, a thunderstorm a further 1 - THUNDER_CUT (overcast: little direct sun). */
    public static final float RAIN_CUT = 0.8f, THUNDER_CUT = 0.5f;

    public record Values(boolean on, float[] colour, Matrix4f invVanillaProjection, Matrix4f invLodProjection, boolean lodValid, float[] sunView,
                         float maxDistance) {
        /** W2: the shaft colour at full strength in scene units per unit of the shaft image; (0, 0, 0) when off. */
        public float[] shaftLight() {
            return this.on ? this.colour.clone() : new float[3];
        }

        public void write(LooseUniformWriter w) {
            w.putMat4(INV_VANILLA_PROJ, this.invVanillaProjection).putMat4(INV_LOD_PROJ, this.invLodProjection);
            w.putVec4(DEPTH, LodClamp.CLAMP_THRESHOLD, this.lodValid ? 1 : 0, 0, 0);
            w.putVec4(SUN, this.sunView[0], this.sunView[1], this.sunView[2], 0);
            w.putVec4(MARCH, this.maxDistance, 0, 0, 0);
        }
    }

    private VolumetricLight() {
    }

    /**
     * Off without a frame, without the lighting pass (no sky light, fog covering everything, post-processing off), with the sun at or
     * below the horizon, the light-shafts setting at 0, the camera's sky exposure at 0, or no cascade drawn. skyFogEnd and
     * lodDistanceBlocks bound the ray: it never reaches past what the cascades cover or what is drawn.
     */
    public static Values compute(PostProcessSettings settings, AtmosphereFrame frame, LightingUniforms.Values lighting, ShadowUniforms.Values shadow,
                                 float skyExposure, float skyFogEnd, float lodDistanceBlocks) {
        if (settings == null || !settings.enabled() || !(settings.lightShafts() > 0)) return off();
        if (frame == null || lighting == null || !lighting.on() || shadow == null || !(shadow.near() || shadow.far())) return off();
        if (!(skyExposure > 0)) return off();
        float sunHeight = SunMath.sunDirection(frame.timeOfDay()).y;
        if (!(sunHeight > 0)) return off();
        float reach = shadow.far() ? ShadowMath.FAR_RADIUS : ShadowMath.NEAR_RADIUS;
        float drawn = Math.max(finiteOr0(skyFogEnd), finiteOr0(lodDistanceBlocks));
        float maxDistance = drawn > 0 ? Math.min(reach, drawn) : reach;
        float[] sun = lighting.sunView();
        return new Values(true, shaftColour(sunHeight, frame.rain(), frame.thunder()), new Matrix4f(lighting.invVanillaProjection()),
                new Matrix4f(lighting.invLodProjection()), lighting.lodValid(), new float[]{sun[0], sun[1], sun[2]}, maxDistance);
    }

    public static Values off() {
        return new Values(false, new float[3], new Matrix4f(), new Matrix4f(), false, new float[]{0, 1, 0}, 0);
    }

    /**
     * The shaft colour at full strength for a sun height (sine of elevation) and the weather: W4's direct sunlight colour
     * (ColourMath.sunLight, luminance 1: the Rayleigh transmittance through part of the Kasten and Young 1989 air mass, the same colour
     * the screen-space shafts take, final fix) times the amount. Never negative; (0, 0, 0) at or below the horizon.
     */
    public static float[] shaftColour(float sunHeight, float rain, float thunder) {
        if (!(sunHeight > 0)) return new float[3];
        float h = Math.min(1, sunHeight);
        float[] c = ColourMath.sunLight(h);
        float amount = amount(h) * (1 - RAIN_CUT * clamp01(rain)) * (1 - THUNDER_CUT * clamp01(thunder));
        for (int i = 0; i < 3; i++) c[i] = Math.max(0, c[i] * amount);
        return c;
    }

    /** Scene units per unit of mask for a sun height in (0, 1], clear weather. */
    static float amount(float sunHeight) {
        float t = clamp01(sunHeight / HORIZON_FADE);
        float fade = t * t * (3 - 2 * t);
        float low = (float) Math.pow(1 - clamp01(sunHeight), LOW_SUN_POWER);
        return fade * (NOON_AMOUNT + (LOW_SUN_AMOUNT - NOON_AMOUNT) * low);
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private static float finiteOr0(float v) {
        return Float.isFinite(v) ? v : 0;
    }
}
