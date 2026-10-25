package net.vulkanmod.visuals.render;

/**
 * Moonlight design 3.1: a night floor for the lightmap's sky-light factor (LightTexture.updateLightTexture's
 * skyDarken * 0.95 + 0.05: 1 by day, 0.76 in daytime rain, 0.599 in a daytime thunderstorm, 0.24 at night). Vanilla's
 * night sky light is already blue, so raising the factor is the moonlight; sky light 0 (caves) stays dark.
 * <p>
 * Vanilla's factor falls linearly from its horizon value to 0.24 while the sun sinks from 0 to DUSK_DEPTH below the
 * horizon. The floor ramps linearly from the same horizon value over the same span, so dusk moves one way only, from
 * the horizon value to the night level (final review: a ramp out of step with vanilla's dipped, then rose).
 */
public final class Moonlight {
    public static final float FLOOR_MAX = 0.8f;
    /** The sun height below the horizon where vanilla's sky darkening ends (getSkyDarken clamps 2 * sunY + 0.2 at 0). */
    public static final float DUSK_DEPTH = 0.1f;

    private Moonlight() {
    }

    /**
     * moonlight is the setting (0..1), timeOfDay the level's (0 is noon), rain and thunder its levels (0..1); applies
     * when the dimension has sky light, no lightning flashes, and the post-process runs. Unchanged while the sun is up.
     */
    public static float skyFactor(float vanilla, float moonlight, float timeOfDay, float rain, float thunder, boolean applies) {
        float sunY = SunMath.sunDirection(timeOfDay).y;
        if (!applies || sunY >= 0) return vanilla;
        float floor = FLOOR_MAX * clamp01(moonlight);
        float horizon = vanillaAtHorizon(rain, thunder);
        float t = Math.min(1, -sunY / DUSK_DEPTH);
        return Math.max(vanilla, horizon + (floor - horizon) * t);
    }

    /** Vanilla's factor with the sun on the horizon: getSkyDarken's 0.2 scaled by the weather, then * 0.8 + 0.2. */
    static float vanillaAtHorizon(float rain, float thunder) {
        float d = 0.2f * (1 - clamp01(rain) * 5 / 16f) * (1 - clamp01(thunder) * 5 / 16f);
        return (d * 0.8f + 0.2f) * 0.95f + 0.05f;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
