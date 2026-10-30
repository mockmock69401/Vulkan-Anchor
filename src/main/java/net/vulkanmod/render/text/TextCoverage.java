package net.vulkanmod.render.text;

/** 2026-10-30: text_aa.glsl's coverage curve (the shader is the authority; change both together). */
public final class TextCoverage {
    /** The most a minified glyph's coverage is thickened: the footprint's cap. */
    public static final float MAX_BOOST = 3f;
    /** With the option on, only texels below this alpha are discarded. */
    public static final float DISCARD_ALPHA = 1f / 255f;
    /** Vanilla's cut, kept with the option off. */
    public static final float LEGACY_DISCARD = 0.1f;
    /** World (perspective) text cuts its coverage here instead of blending (blended edges wrote depth over what lay behind). */
    public static final float WORLD_CUTOUT = 0.5f;

    private TextCoverage() {
    }

    /** The vertex shaders' textIsPerspective: the MVP's last row (m03, m13, m23) is zero only for an orthographic (GUI) matrix. */
    public static boolean perspective(float m03, float m13, float m23) {
        return Math.abs(m03) + Math.abs(m13) + Math.abs(m23) > 1e-6f;
    }

    /** a: the sampled coverage; footprint: texels per screen pixel. Minified: 1 - (1 - a)^min(footprint, MAX_BOOST); magnified: a. */
    public static float coverage(float a, float footprint) {
        a = Math.max(0f, Math.min(a, 1f));
        if (footprint < 1f) {
            return a;
        }
        return (float) (1.0 - Math.pow(1.0 - a, Math.min(footprint, MAX_BOOST)));
    }

    /** text_aa.glsl's textSharp on one axis: t the texel coordinate, m screen pixels per texel. */
    public static float sharpCoord(float t, float m) {
        float c = (float) Math.floor(t - 0.5f) + 0.5f;
        float f = t - c;
        return c + Math.max(0f, Math.min((f - 0.5f) * m + 0.5f, 1f));
    }
}
