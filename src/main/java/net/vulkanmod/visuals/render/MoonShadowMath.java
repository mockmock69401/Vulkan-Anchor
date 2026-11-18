package net.vulkanmod.visuals.render;

import org.joml.Vector3f;

/**
 * 2026-11-18 (the user, comparing night screenshots with other renderers: "the Vulkan side has no shadows from the moonlight"): at night
 * the shadow maps and the lighting pass take the moon as their light. Vanilla draws the moon opposite the sun, so its direction is the
 * sun's half a day on, and the shadow maps' sun steps (ShadowSteps) run on that time unchanged.
 * <p>
 * The hand-over is at the horizon: the sun's weight (SunMath.dayFactor) has reached 0 there and the moon's starts from 0, each rising over
 * the same 0.2 of height, so no frame shades by both lights and nothing pops. The moon's weight is a share of the sun shading's: MOON_SHADOW
 * at full moon with the moonlight setting at its default, scaled by the moonlight setting, by the phase (vanilla's moon brightness per
 * phase: none at new moon) and by the moon's height (rain included, as the sun's). The composite shades it with the moon's own levels
 * (LightingMath.MOON_SHADE_LEVEL, MOON_SUN_LEVEL: a sun term of -1 at night), so the moonlit ground keeps about its brightness and the
 * shadows are darker than the night around them, cool because the night's light is (the moonlight colour). LightingUniforms and ShadowFrame use it.
 */
public final class MoonShadowMath {
    /** The moon's shadow weight at full moon, the moon high and the moonlight setting at its default: a share of the sun shading's. */
    public static final float MOON_SHADOW = 0.75f;
    /** The moonlight setting's default (VisualsConfig.moonlight 50, PostProcessSettings 0..1 per 100): MOON_SHADOW there. */
    public static final float MOONLIGHT_DEFAULT = 0.5f;
    /** Vanilla's moon brightness per phase (DimensionType.MOON_BRIGHTNESS_PER_PHASE): full moon 1, new moon 0. */
    static final float[] PHASE_LIGHT = {1.0f, 0.75f, 0.5f, 0.25f, 0.0f, 0.25f, 0.5f, 0.75f};

    private MoonShadowMath() {
    }

    /** Whether the moon is the light: the sun is below the horizon. */
    public static boolean moonLights(float timeOfDay) {
        return SunMath.sunDirection(timeOfDay).y < 0;
    }

    /** The light's time of day: the sun's while it is up, else the moon's (half a day on, in 0..1). */
    public static float lightTime(float timeOfDay) {
        if (!moonLights(timeOfDay)) return timeOfDay;
        float t = timeOfDay + 0.5f;
        return t - (float) Math.floor(t);
    }

    /** The light's direction (unit, toward the light): the sun's while it is up, else the moon's. */
    public static Vector3f lightDirection(float timeOfDay) {
        return SunMath.sunDirection(lightTime(timeOfDay));
    }

    /** The moon's strength before its height: MOON_SHADOW x the moonlight setting per its default, at most 1, x the phase's brightness. */
    public static float strength(float moonlight, int moonPhase) {
        float m = Float.isFinite(moonlight) ? Math.max(0, moonlight) : 0;
        return Math.min(1, MOON_SHADOW * m / MOONLIGHT_DEFAULT) * PHASE_LIGHT[Math.floorMod(moonPhase, PHASE_LIGHT.length)];
    }

    /** The light's weight 0..1: the sun's day factor while it is up, else moonStrength (strength()) x the moon's own day factor. */
    public static float weight(float timeOfDay, float rain, float moonStrength) {
        float sunHeight = SunMath.sunDirection(timeOfDay).y;
        if (sunHeight >= 0) return SunMath.dayFactor(sunHeight, rain);
        return (moonStrength > 0 ? Math.min(1, moonStrength) : 0) * SunMath.dayFactor(-sunHeight, rain);
    }
}
