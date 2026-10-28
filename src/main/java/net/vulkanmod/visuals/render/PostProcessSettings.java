package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

import java.util.List;

/** The post-process settings as the shaders take them: ratios (1 = 100%), clamped to the settings screen's ranges. */
public record PostProcessSettings(boolean enabled, float exposure, float toneStrength, float contrast, float saturation,
                                  float gradeStrength, float bloomStrength, float vignetteStrength, float fogStrength, float fogHeight,
                                  float sunGlow, float sunsetLight, float lightShafts, float moonlight, float aoStrength, float sunShading, float waterDepth, float waterReflections, float waterRipples, float waterRippleSpeed, boolean farClouds, boolean sunShadows, boolean vanillaShadows, float skyLightAmbient, boolean perPixelLight, float lightColour, float sunWarmth, float blockLightColour, boolean heldLight, int antiAliasing, boolean taa, int lightDebug) {
    // Settings screen ranges, percent
    public static final int EXPOSURE_MIN = 50, EXPOSURE_MAX = 200;
    public static final int CONTRAST_MIN = 50, CONTRAST_MAX = 150;
    public static final int SATURATION_MIN = 0, SATURATION_MAX = 200;
    public static final int STRENGTH_MAX = 100;
    public static final int FOG_STRENGTH_MAX = 200, FOG_HEIGHT_MIN = 32, FOG_HEIGHT_MAX = 512;
    public static final int RIPPLE_SPEED_MAX = 200;
    /** 2026-10-28: the sun warmth goes to twice the tuned warmth (ColourMath.SUN_WARMTH_MIRED per 100%). */
    public static final int SUN_WARMTH_MAX = 200;
    public static final int SKY_LIGHT_AMBIENT_MAX = 100;
    public static final int LIGHT_DEBUG_MAX = 5;
    /** 2026-10-17: the light debug value that paints the pixels the reflection pass mirrors (ReflectionUniforms), not a light view. */
    public static final int REFLECTIVE_DEBUG = 4;
    public static final int ANTI_ALIASING_MAX = 4;

    public static final String KEY_PREFIX = "vulkanmod.visuals.";
    public static final List<String> OPTION_NAMES = List.of("enabled", "exposure", "tone_strength", "contrast", "saturation", "grade_strength",
            "bloom_strength", "vignette_strength", "fog_strength", "fog_height", "sun_glow", "sunset_light", "light_shafts", "moonlight", "ao_strength", "sun_shading", "water_depth", "water_reflections", "water_ripples", "water_ripple_speed", "far_clouds", "sun_shadows", "vanilla_shadows", "sky_light_ambient", "per_pixel_light", "light_colour", "sun_warmth", "block_light_colour", "held_light", "anti_aliasing", "taa", "light_debug");

    public static PostProcessSettings from(VisualsConfig config) {
        VisualsConfig c = config != null ? config : new VisualsConfig();
        return new PostProcessSettings(c.enabled, percent(c.exposure, EXPOSURE_MIN, EXPOSURE_MAX), percent(c.toneStrength, 0, STRENGTH_MAX),
                percent(c.contrast, CONTRAST_MIN, CONTRAST_MAX), percent(c.saturation, SATURATION_MIN, SATURATION_MAX),
                percent(c.gradeStrength, 0, STRENGTH_MAX), percent(c.bloomStrength, 0, STRENGTH_MAX), percent(c.vignetteStrength, 0, STRENGTH_MAX),
                percent(c.fogStrength, 0, FOG_STRENGTH_MAX), (float) clamp(c.fogHeight, FOG_HEIGHT_MIN, FOG_HEIGHT_MAX),
                percent(c.sunGlow, 0, STRENGTH_MAX), percent(c.sunsetLight, 0, STRENGTH_MAX), percent(c.lightShafts, 0, STRENGTH_MAX),
                percent(c.moonlight, 0, STRENGTH_MAX), percent(c.aoStrength, 0, STRENGTH_MAX), percent(c.sunShading, 0, STRENGTH_MAX), percent(c.waterDepth, 0, STRENGTH_MAX), percent(c.waterReflections, 0, STRENGTH_MAX), percent(c.waterRipples, 0, STRENGTH_MAX), percent(c.waterRippleSpeed, 0, RIPPLE_SPEED_MAX), c.farClouds, c.sunShadows, c.vanillaShadows, percent(c.skyLightAmbient, 0, SKY_LIGHT_AMBIENT_MAX), c.perPixelLight, percent(c.lightColour, 0, STRENGTH_MAX), percent(c.sunWarmth, 0, SUN_WARMTH_MAX), percent(c.blockLightColour, 0, STRENGTH_MAX), c.heldLight, clamp(c.antiAliasing, 0, ANTI_ALIASING_MAX), c.taa, clamp(c.lightDebug, 0, LIGHT_DEBUG_MAX));
    }

    /** The values in the settings screen's units, for the log line. */
    public String describe() {
        return (this.enabled ? "on" : "off") + ", exposure " + pct(this.exposure) + ", tone " + pct(this.toneStrength) + ", contrast " + pct(this.contrast)
                + ", saturation " + pct(this.saturation) + ", grade " + pct(this.gradeStrength) + ", bloom " + pct(this.bloomStrength)
                + ", vignette " + pct(this.vignetteStrength) + ", fog " + pct(this.fogStrength) + "/" + Math.round(this.fogHeight) + ", glow "
                + pct(this.sunGlow) + ", sunset " + pct(this.sunsetLight) + ", shafts " + pct(this.lightShafts) + ", moon " + pct(this.moonlight) + ", ao " + pct(this.aoStrength) + ", sun " + pct(this.sunShading) + ", water " + pct(this.waterDepth) + ", reflections " + pct(this.waterReflections) + ", ripples " + pct(this.waterRipples) + ", ripple speed " + pct(this.waterRippleSpeed) + ", clouds " + (this.farClouds ? "on" : "off") + ", shadows " + (this.sunShadows ? "on" : "off") + ", vanilla shadows " + (this.vanillaShadows ? "on" : "off") + ", sky ambient " + pct(this.skyLightAmbient) + ", pixel light " + (this.perPixelLight ? "on" : "off") + ", light colour " + pct(this.lightColour) + ", sun warmth " + pct(this.sunWarmth) + ", block light colour " + pct(this.blockLightColour) + ", held light " + (this.heldLight ? "on" : "off") + ", anti-aliasing " + this.antiAliasing + ", taa " + (this.taa ? "on" : "off") + ", light debug " + this.lightDebug;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float percent(int value, int min, int max) {
        return clamp(value, min, max) / 100f;
    }

    private static int pct(float ratio) {
        return Math.round(ratio * 100);
    }
}
