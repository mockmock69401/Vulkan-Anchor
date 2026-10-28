package net.vulkanmod.visuals.render;

/**
 * W4: the two light colours the composite tints the lighting with (B15, slots 22 and 23): direct sunlight by the sun's height and
 * the light of a surface lit by the sky only. Both are linear Rec. 709 tints of luminance 1 (they change colour, not brightness);
 * the composite scales them by the light-colour setting and the sun's weight.
 */
public final class ColourMath {
    /** Direct sunlight is the Rayleigh transmittance through this fraction of the real air mass (the real one is deep red at the horizon). */
    public static final float SUN_LIGHT_REDDENING = 0.5f;
    /** The shade's tint: Rayleigh's lambda^-4 sky colour raised to this power (a cool hint, far paler than the sky itself). */
    public static final float SHADE_EXPONENT = 0.2f;

    /** Linear colour of light on a surface lit by the sky only (shade side), luminance 1, bluer than any sunlight. */
    public static final float[] SKY_SHADE = skyShade();

    /** 2026-10-28: colour temperatures are relative to this white (D65, sRGB's white point), in kelvin. */
    public static final float WHITE_KELVIN = 6500f;
    /** Planck's second radiation constant hc/k in micrometre kelvin (CODATA 2018). */
    public static final double PLANCK_C2 = 14387.77;
    /**
     * 2026-10-28 (the user: next to Complementary Reimagined the sunlight lacked warmth; its sunlit terrain had a 25-40% higher red to
     * green ratio): the sun warmth setting's 100% lowers the direct sunlight's colour temperature by this many mireds, from WHITE_KELVIN
     * to about 4000 K (screen check: 68 mireds, 4500 K, moved the backlit morning view's red/green from 0.66 to only 0.72). The shade
     * colour takes SHADE_WARMTH_SHARE of the shift (the morning's shade sides were the bluest part of the view) and stays cooler than white.
     */
    public static final float SUN_WARMTH_MIRED = 96f, SHADE_WARMTH_SHARE = 0.2f;
    /** 2026-10-28: torch light's colour (slot 31), a flame-like 3000 K, and the moonlight's on the sky-lit light at night (slot 32). */
    public static final float BLOCK_LIGHT_KELVIN = 3000f, MOON_LIGHT_KELVIN = 10000f;
    public static final float[] BLOCK_LIGHT = temperatureTint(BLOCK_LIGHT_KELVIN);
    public static final float[] MOON_LIGHT = temperatureTint(MOON_LIGHT_KELVIN);

    private ColourMath() {
    }

    /**
     * Linear colour of direct sunlight for a sun height (sine of elevation, -1..1): the sunlight's Rayleigh transmittance (Hansen and
     * Travis 1974 optical depth at AtmosphereUniforms.SKY_WAVELENGTHS) through SUN_LIGHT_REDDENING of the Kasten and Young 1989 air
     * mass, luminance 1. Near white at noon, orange at the horizon; at or below the horizon the horizon's colour (the composite fades
     * it out with the day weight). Never negative or NaN.
     */
    public static float[] sunLight(float sunHeight) {
        float m = AtmosphereMath.airMass(Float.isNaN(sunHeight) ? 0 : sunHeight);
        float[] tau = AtmosphereUniforms.rayleighDepths();
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) c[i] = (float) Math.exp(-SUN_LIGHT_REDDENING * tau[i] * m);
        return normalised(c);
    }

    /**
     * 2026-10-28: sunLight(sunHeight) warmed by the sun warmth setting (1 = 100%): times temperatureTint at warmth x SUN_WARMTH_MIRED
     * below WHITE_KELVIN, luminance 1. Warmth 0 (or less) is sunLight(sunHeight) itself.
     */
    public static float[] sunLight(float sunHeight, float warmth) {
        float[] c = sunLight(sunHeight);
        if (!(warmth > 0)) return c;
        float[] t = temperatureTint(warmKelvin(warmth));
        return normalised(new float[]{c[0] * t[0], c[1] * t[1], c[2] * t[2]});
    }

    /** 2026-10-28: SKY_SHADE warmed by SHADE_WARMTH_SHARE of the sun warmth (1 = 100%), luminance 1; warmth 0 is SKY_SHADE. */
    public static float[] skyShade(float warmth) {
        if (!(warmth > 0)) return SKY_SHADE.clone();
        float[] t = temperatureTint(warmKelvin(warmth * SHADE_WARMTH_SHARE));
        return normalised(new float[]{SKY_SHADE[0] * t[0], SKY_SHADE[1] * t[1], SKY_SHADE[2] * t[2]});
    }

    /** The colour temperature the sun warmth (1 = 100%) gives: warmth x SUN_WARMTH_MIRED mireds below WHITE_KELVIN. */
    public static float warmKelvin(float warmth) {
        return 1e6f / (1e6f / WHITE_KELVIN + Math.max(0, warmth) * SUN_WARMTH_MIRED);
    }

    /**
     * Linear colour of a black body at kelvin against one at WHITE_KELVIN: Planck's law at AtmosphereUniforms.SKY_WAVELENGTHS (the same
     * three wavelengths the sky's colours are made at), luminance 1. Warmer than white below WHITE_KELVIN, cooler above.
     */
    public static float[] temperatureTint(float kelvin) {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) {
            double l = AtmosphereUniforms.SKY_WAVELENGTHS[i];
            c[i] = (float) (Math.expm1(PLANCK_C2 / (l * WHITE_KELVIN)) / Math.expm1(PLANCK_C2 / (l * kelvin)));
        }
        return normalised(c);
    }

    private static float[] skyShade() {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) c[i] = (float) Math.pow(AtmosphereUniforms.SKY_WAVELENGTHS[i], -4 * SHADE_EXPONENT);
        return normalised(c);
    }

    /** The colour scaled to luminance 1; white when it has none. */
    static float[] normalised(float[] c) {
        float l = PostMath.luminance(c[0], c[1], c[2]);
        if (!(l > 0) || !Float.isFinite(l)) return new float[]{1, 1, 1};
        return new float[]{Math.max(0, c[0] / l), Math.max(0, c[1] / l), Math.max(0, c[2] / l)};
    }
}
