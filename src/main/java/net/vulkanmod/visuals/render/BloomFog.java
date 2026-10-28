package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * W3: the per-frame values of the bloom prefilter (bloom.comp, locations 1 and 2).
 *
 * <p>Besides the glow of bright areas, far terrain takes a gentle glow that grows with distance, which softens the horizon
 * (the glow image holds the scene times (1 + gain), so the final pass's lerp leaves plain areas unchanged and brightens far
 * pixels by w*gain, blurred into their neighbourhood). How much depends on the light that can be scattered: less at night, more in rain, none under a roof or
 * in a dimension without sky. Physical idea: aerial perspective / in-scattering over distance (Nishita et al. 1993, "Display
 * of the Earth taking into account atmospheric scattering"), here only as a weight, as W4 owns the fog colour.
 */
public final class BloomFog {
    /**
     * Scene units: the soft threshold. With the knee (BloomMath.KNEE) the glow begins at THRESHOLD - KNEE = 0.5. 2026-10-21 (CTE2: the
     * bloom did not show): it began at 1 (a white surface in full daylight), so by day only the sun's disc glowed; now the bright
     * horizon sky (about 0.65) and pale sunlit surfaces glow softly too, quadratically from the knee's start. 2026-10-24 (CTE2: still no
     * haze at bloom 100): 0.7, so the glow begins at 0.2 and the sunlit ground and the sky behind the trees bleed too. 2026-10-28
     * (DeceasedCraft: snow glared, 1.7% of a snowy street above 240 against Complementary Reimagined's 0.2%): 0.85, from 0.35.
     */
    public static final float THRESHOLD = 0.85f;
    /** Extra gain of the glow image at full distance in clear noon light: P = c*(1 + gain). The final mix w = 0.45 at bloom 100 turns it into a brightening of w*gain = 9%, at most 13.5% in rain. */
    public static final float MAX_AMOUNT = 0.2f;
    /** At night (no sun) the glow keeps this share of its daytime amount. */
    public static final float NIGHT_FLOOR = 0.3f;
    /** Full rain raises the amount by this share and brings the glow nearer by RAIN_NEAR. */
    public static final float RAIN_BOOST = 0.2f, RAIN_NEAR = 0.25f;//2026-10-28: 0.2 (was 0.5): rain and thunder bloomed too much
    /** The glow begins at this share of the sky fog end and is full 1.5 fog ends later (blocks, clear weather). */
    public static final float START_SHARE = 0.5f, SPAN_SHARE = 1.5f;

    /**
     * start: distance (blocks) where the distance glow begins; invSpan: 1 / the length of its ramp; amount: its value at the end
     * (0 = none); threshold: the bright threshold; depth*: view distance = (A*d + B) / (C*d + E) for the main depth d.
     */
    public record Values(float start, float invSpan, float amount, float threshold, float depthA, float depthB, float depthC, float depthE) {
        /** The share of its own colour a pixel at this view distance adds to the glow (bloom.comp distanceGlow). */
        public float glowAt(float viewDistance) {
            float t = Math.max(0f, Math.min(1f, (viewDistance - this.start) * this.invSpan));
            return this.amount * t * t * (3f - 2f * t);//Hermite smoothstep
        }

        public void write(LooseUniformWriter w) {
            w.putVec4(1, this.start, this.invSpan, this.amount, this.threshold);
            w.putVec4(2, this.depthA, this.depthB, this.depthC, this.depthE);
        }
    }

    private BloomFog() {
    }

    private static float unit(float x) {
        return x > 0 ? Math.min(1f, x) : 0f;//NaN gives 0
    }

    /** Once per frame. A null or stale frame, or no sky, gives no distance glow (the bright glow still works). */
    public static Values compute(PostProcessSettings settings, AtmosphereFrame frame, long serial, boolean hasSky, float skyExposure) {
        if (!hasSky || frame == null || frame.serial() != serial || frame.invVanillaProjection() == null) {
            return new Values(0, 1, 0, THRESHOLD, 0, 0, 0, 1);
        }
        float rain = unit(frame.rain());
        float day = SunMath.dayFactor(SunMath.sunDirection(frame.timeOfDay()).y, 0);
        float amount = MAX_AMOUNT * (NIGHT_FLOOR + (1f - NIGHT_FLOOR) * day) * (1f + RAIN_BOOST * rain) * unit(skyExposure);
        float end = frame.skyFogEnd() > 0 && Float.isFinite(frame.skyFogEnd()) ? frame.skyFogEnd() : ReflectionUniforms.DEFAULT_SKY_FOG_END;
        float near = 1f - RAIN_NEAR * rain;
        float start = START_SHARE * end * near, span = SPAN_SHARE * end * near;
        //The vanilla depth is 0..1 and the inverse projection is remapped to it (ReforgedProjection): the view distance of
        //(0, 0, d, 1) is (-z / w) of the inverse projection's product
        org.joml.Matrix4f m = frame.invVanillaProjection();
        return new Values(start, 1f / span, amount, THRESHOLD, -m.m22(), -m.m32(), m.m23(), m.m33());
    }
}
