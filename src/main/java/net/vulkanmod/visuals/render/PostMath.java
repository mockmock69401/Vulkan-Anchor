package net.vulkanmod.visuals.render;

/**
 * W2: the final pass's maths, mirrored from postmath.glsl, postfinal.glsl and post_blit.frag so the tests can check it. Every
 * {@code const} those shaders declare is a constant here of the same name and value (PostShaderConstantsTest).
 *
 * <p>Scene units (see the header of postmath.glsl): linear Rec. 709 / sRGB primaries, 1.0 = a white (albedo 1) surface in full
 * daylight, i.e. vanilla's full-bright texel decoded from sRGB, at exposure 100.
 */
public final class PostMath {
    private PostMath() {
    }

    // ---- shared with postmath.glsl ----

    /** Rec. 709 / sRGB linear luminance weights (ITU-R BT.709-6). */
    public static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};
    /** Scene units: a white surface in full daylight at exposure 100. */
    public static final float SCENE_WHITE = 1.0f;
    /** A suggestion for the sun disc's peak in scene units: far above white, so the shoulder rolls it off to white softly. */
    public static final float SUN_DISC_LUMINANCE = 24.0f;
    /** sRGB transfer function (IEC 61966-2-1:1999): linear cut, linear slope, the power segment's scale, offset and exponent. */
    public static final float SRGB_CUT = 0.0031308f, SRGB_SLOPE = 12.92f, SRGB_SCALE = 1.055f, SRGB_OFFSET = 0.055f, SRGB_GAMMA = 2.4f;
    /** The display value at SRGB_CUT (decode threshold). */
    public static final float SRGB_DISPLAY_CUT = 0.04045f;
    /** Tone mapping at strength 100: the identity up to this linear value, an exponential shoulder above it. */
    public static final float TONE_KNEE = 0.55f;
    /** Interleaved gradient noise (Jimenez 2014) for the display dither. */
    public static final float IGN_X = 0.06711056f, IGN_Y = 0.00583715f, IGN_SCALE = 52.9829189f;

    // ---- shared with postfinal.glsl ----

    /** Dither amplitude: just under one 8-bit step peak to peak, so a value already on the 8-bit grid never moves. */
    public static final float DITHER = 0.0039137257f;//0.998 / 255
    /** Vignette (cos^4 law): tan^2 of the angle at the screen's corner, see vignette(). */
    public static final float VIGNETTE_TAN2 = 0.16f;

    // ---- shared with post_blit.frag ----

    /** Unsharp mask gain per unit strength: strength 1 adds the full difference from the four neighbours' mean. */
    public static final float SHARPEN_GAIN = 0.25f;

    // ---- Java only ----

    /** Exposure 100 multiplies the scene by this (the scene is already in display-referred units, see SCENE_WHITE). */
    public static final float EXPOSURE_SCALE = 1.0f;
    /** The bloom's mix weight at bloom 100. 2026-10-24 (CTE2: "no haze at all, even at 100%"): 0.15 changed the screen by 4-5/255. */
    public static final float BLOOM_MIX = 0.45f;
    /** The light shafts' scale at light shafts 100 (shaftLight is the colour at full strength). */
    public static final float SHAFT_SCALE = 1.0f;
    /** Sharpening after TAA; SMAA alone keeps edges sharp and gets a plain copy. */
    public static final float SHARPEN_TAA = 0.5f;
    /** Bloom pyramid: at most this many levels; a level is added while its shorter side keeps at least BLOOM_MIN_SIDE texels. */
    public static final int BLOOM_MAX_LEVELS = 8, BLOOM_MIN_SIDE = 8;

    // ---- conversions ----

    public static float luminance(float r, float g, float b) {
        return LUMA[0] * r + LUMA[1] * g + LUMA[2] * b;
    }

    /** sRGB encoding (IEC 61966-2-1:1999), linear [0, 1] to display [0, 1]; negative input gives 0. */
    public static float linearToDisplay(float x) {
        if (x <= SRGB_CUT) return Math.max(x, 0) * SRGB_SLOPE;
        return SRGB_SCALE * (float) Math.pow(x, 1 / SRGB_GAMMA) - SRGB_OFFSET;
    }

    /** sRGB decoding, display [0, 1] to linear [0, 1]. */
    public static float displayToLinear(float d) {
        if (d <= SRGB_DISPLAY_CUT) return Math.max(d, 0) / SRGB_SLOPE;
        return (float) Math.pow((d + SRGB_OFFSET) / SRGB_SCALE, SRGB_GAMMA);
    }

    // ---- tone mapping ----

    /** The knee for a tone strength (0..1): 1 at strength 0 (the identity, clipped at 1), TONE_KNEE at 1. */
    public static float toneKnee(float toneStrength) {
        float s = Math.max(0, Math.min(1, toneStrength));
        return 1 + (TONE_KNEE - 1) * s;
    }

    /**
     * Per channel: the identity below the knee k, above it the exponential shoulder k + (1 - k)(1 - exp(-(x - k)/(1 - k))), which
     * meets the line with slope 1 (C1) and tends to 1. Built after the linear section plus exponential shoulder of Uchimura's
     * curve (Uchimura 2017, "HDR theory and practice", CEDEC) without its toe, so darks pass unchanged (night stays readable).
     */
    public static float toneMap(float x, float knee) {
        x = Math.max(x, 0);
        if (x <= knee) return Math.min(x, 1);
        float w = Math.max(1 - knee, 1e-6f);
        float y = knee + w * (1 - (float) Math.exp(-(x - knee) / w));
        return Math.min(y, 1);
    }

    // ---- colour ----

    /**
     * Contrast on a display value about mid-grey: Schlick's gain function (Schlick 1994, "Fast alternatives to Perlin's bias and
     * gain functions", Graphics Gems IV) with its slope at 0.5 equal to contrast; it keeps 0, 0.5 and 1 and is exactly the
     * identity at contrast 1. The shader takes k = contrast - 1 (bias(t) = t / (k(1 - t) + 1)).
     */
    public static float contrast(float d, float contrast) {
        float k = contrast - 1;
        d = Math.max(0, Math.min(1, d));
        if (d < 0.5f) return bias(2 * d, k) * 0.5f;
        return 1 - bias(2 - 2 * d, k) * 0.5f;
    }

    private static float bias(float t, float k) {
        return t / (k * (1 - t) + 1);
    }

    /** Saturation in linear light about the pixel's luminance (1 neutral, 0 grey). */
    public static float[] saturate(float[] rgb, float saturation) {
        float l = luminance(rgb[0], rgb[1], rgb[2]);
        for (int i = 0; i < 3; i++) rgb[i] = l + (rgb[i] - l) * saturation;
        return rgb;
    }

    /** Rayleigh optical depth of the whole atmosphere at sea level for a wavelength in micrometres (Hansen and Travis 1974). */
    static float rayleighDepth(double micrometres) {
        double l2 = micrometres * micrometres, l4 = l2 * l2;
        return (float) (0.008569 / l4 * (1 + 0.0113 / l2 + 0.00013 / l4));
    }

    /** Representative wavelengths of the sRGB red, green and blue primaries, micrometres. */
    static final double[] WAVELENGTHS = {0.61, 0.55, 0.465};
    /** The grade's warm tint takes this fraction of the real reddening of sunlight (which is far stronger at the horizon). */
    static final float GRADE_WARMTH = 0.3f;
    /** The shade's tint: Rayleigh's lambda^-4 sky colour raised to this power (a hint of blue sky light). */
    static final float COOL_EXPONENT = 0.12f;
    /** The grade fades in over this sun height range around the horizon (none at night). */
    static final float GRADE_FADE_LOW = -0.04f, GRADE_FADE_HIGH = 0.04f;

    /** Luminance 1: the tint changes colour, not brightness. */
    private static float[] normalised(float[] c) {
        float l = luminance(c[0], c[1], c[2]);
        return new float[]{c[0] / l, c[1] / l, c[2] / l};
    }

    /**
     * The warm tint for bright areas: sunlight's colour through the air mass of the sun's height (Kasten and Young 1989 air
     * mass formula; Rayleigh extinction exp(-tau m)), GRADE_WARMTH of the way, luminance 1. Slightly warm at noon, orange at sunset.
     */
    public static float[] warmTint(float sunHeight) {
        double h = Math.max(0, Math.min(1, sunHeight));
        double degrees = Math.toDegrees(Math.asin(h));
        double airMass = 1 / (h + 0.50572 * Math.pow(degrees + 6.07995, -1.6364));
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) t[i] = (float) Math.exp(-GRADE_WARMTH * rayleighDepth(WAVELENGTHS[i]) * airMass);
        return normalised(t);
    }

    /** The cool tint for dark areas (shade lit by the blue sky), luminance 1. */
    public static final float[] COOL_TINT = coolTint();

    private static float[] coolTint() {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) c[i] = (float) Math.pow(WAVELENGTHS[i], -4 * COOL_EXPONENT);
        return normalised(c);
    }

    /** How much of the grade applies at a sun height: none at night, all once the sun is a little above the horizon. */
    public static float gradeFade(float sunHeight) {
        float t = Math.max(0, Math.min(1, (sunHeight - GRADE_FADE_LOW) / (GRADE_FADE_HIGH - GRADE_FADE_LOW)));
        return t * t * (3 - 2 * t);
    }

    /** Split toning: the tint goes from cool to warm with the square root of the tone-mapped luminance. */
    public static float[] grade(float[] rgb, float[] warm, float[] cool) {
        float t = (float) Math.sqrt(Math.max(0, Math.min(1, luminance(rgb[0], rgb[1], rgb[2]))));
        for (int i = 0; i < 3; i++) rgb[i] *= cool[i] + (warm[i] - cool[i]) * t;
        return rgb;
    }

    /**
     * Natural vignetting, the cos^4 law of illumination falloff (e.g. Ray, "Applied Photographic Optics", 2002):
     * cos^4(theta) = 1 / (1 + tan^2(theta))^2 with tan^2(theta) = r^2 VIGNETTE_TAN2 and r the distance from the centre over the
     * half-diagonal (1 in the corners). Mixed in by strength; 1 at strength 0.
     */
    public static float vignette(float fragX, float fragY, int width, int height, float strength) {
        float w = Math.max(1, width), h = Math.max(1, height);
        float dx = fragX - w * 0.5f, dy = fragY - h * 0.5f;
        float r2 = (dx * dx + dy * dy) / (0.25f * (w * w + h * h));
        float q = 1 + r2 * VIGNETTE_TAN2;
        float cos4 = 1 / (q * q);
        return 1 + (cos4 - 1) * strength;
    }

    // ---- the whole chain ----

    /**
     * The final pass for one pixel as postfinal.glsl computes it, without the dither (under half an 8-bit step): scene and bloom in
     * scene units, shafts the shaft image's value, at the pixel centre (fragX, fragY). Returns the display colour.
     */
    public static float[] finalColour(PostUniforms.Values v, float[] scene, float[] bloom, float shafts, float fragX, float fragY) {
        float[] c = new float[3];
        float vig = vignette(fragX, fragY, v.width(), v.height(), v.vignette());
        for (int i = 0; i < 3; i++) {
            float x = nonNegative(scene[i]);
            if (v.bloomMix() > 0) x = x + (nonNegative(bloom[i]) * v.bloomNorm() - x) * v.bloomMix();
            if (v.shaftsOn()) x += v.shaft()[i] * Math.max(shafts, 0);
            c[i] = toneMap(x * v.exposure() * vig, v.toneKnee());
        }
        grade(c, v.warm(), v.cool());
        saturate(c, v.saturation());
        for (int i = 0; i < 3; i++) c[i] = contrast(linearToDisplay(Math.max(0, Math.min(1, c[i]))), v.contrast());
        return c;
    }

    /** postfinal.glsl's nonNegative: negative and NaN as 0. */
    public static float nonNegative(float x) {
        return Float.isNaN(x) ? 0 : Math.max(x, 0);
    }

    // ---- sharpening blit ----

    /**
     * post_blit.frag: an unsharp mask on the cross of four neighbours, clamped to their range (and the centre's) so it cannot
     * overshoot into halos, the limiting idea of contrast-adaptive sharpening (Lottes 2019, AMD FidelityFX CAS, GPUOpen).
     * Strength 0 returns the centre unchanged.
     */
    public static float[] sharpen(float[] c, float[] n, float[] s, float[] e, float[] w, float strength) {
        if (strength <= 0) return c.clone();
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float lo = Math.min(c[i], Math.min(Math.min(n[i], s[i]), Math.min(e[i], w[i])));
            float hi = Math.max(c[i], Math.max(Math.max(n[i], s[i]), Math.max(e[i], w[i])));
            float sharp = c[i] + (4 * c[i] - n[i] - s[i] - e[i] - w[i]) * strength * SHARPEN_GAIN;
            out[i] = Math.max(lo, Math.min(hi, sharp));
        }
        return out;
    }

    /** How strongly the final blit sharpens: TAA's resolve softens the image, SMAA alone does not. */
    public static float sharpenStrength(boolean taaDrawn) {
        return taaDrawn ? SHARPEN_TAA : 0;
    }

    // ---- bloom pyramid ----

    /**
     * Level 0 at half resolution (rounded up; the light shafts share its size and shafts.comp reads the scene at twice its
     * coordinate), each further level half the one before (rounded up) while its shorter side keeps BLOOM_MIN_SIDE texels, at most
     * BLOOM_MAX_LEVELS: 7 levels at 1920x1080 (smallest 15x9) and 2560x1440 (smallest 20x12), a glow wide as a fair part of the
     * screen when the bloom package sums the levels (the progressive downsample-upsample chain of Jimenez 2014,
     * "Next Generation Post Processing in Call of Duty: Advanced Warfare", SIGGRAPH).
     */
    public static int[][] bloomSizes(int width, int height) {
        int w = Math.max(1, (Math.max(1, width) + 1) / 2), h = Math.max(1, (Math.max(1, height) + 1) / 2);
        int count = 1;
        int cw = w, ch = h;
        while (count < BLOOM_MAX_LEVELS) {
            int nw = (cw + 1) / 2, nh = (ch + 1) / 2;
            if (Math.min(nw, nh) < BLOOM_MIN_SIDE || (nw == cw && nh == ch)) break;
            cw = nw;
            ch = nh;
            count++;
        }
        int[][] sizes = new int[count][];
        cw = w;
        ch = h;
        for (int i = 0; i < count; i++) {
            sizes[i] = new int[]{cw, ch};
            cw = (cw + 1) / 2;
            ch = (ch + 1) / 2;
        }
        return sizes;
    }
}
