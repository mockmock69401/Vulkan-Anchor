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
