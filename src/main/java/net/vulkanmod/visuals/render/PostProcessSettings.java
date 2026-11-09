package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

import java.util.List;

/** The post-process settings as the shaders take them: ratios (1 = 100%), clamped to the settings screen's ranges. */
public record PostProcessSettings(boolean enabled, float exposure, float toneStrength, float contrast, float saturation,
                                  float gradeStrength, float bloomStrength, float vignetteStrength, float fogStrength, float fogHeight, float weatherFog, float distanceHaze,
                                  float sunGlow, float sunsetLight, float lightShafts, float moonlight, float aoStrength, float sunShading, float waterDepth, float waterReflections, float waterRipples, float waterRippleSpeed, float waterRippleDensity, float underwaterVisibility, boolean farClouds, int cloudStyle, float cloudCoverage, float cloudSize, float cloudCount, int cloudQuality, int celestialShape, int aurora, float auroraStrength, boolean sunShadows, boolean vanillaShadows, float skyLightAmbient, boolean perPixelLight, float lightColour, float sunWarmth, float blockLightColour, float blockGlow, float oreGlow, boolean heldLight, int antiAliasing, boolean taa, int lightDebug, int blockReflections) {
    // Settings screen ranges, percent
    public static final int EXPOSURE_MIN = 50, EXPOSURE_MAX = 200;
    public static final int CONTRAST_MIN = 50, CONTRAST_MAX = 150;
    public static final int SATURATION_MIN = 0, SATURATION_MAX = 200;
    public static final int STRENGTH_MAX = 100;
    public static final int FOG_STRENGTH_MAX = 200, FOG_HEIGHT_MIN = 32, FOG_HEIGHT_MAX = 512;
    public static final int RIPPLE_SPEED_MAX = 200;
    /** 2026-11-09 (session 2a): water.glsl's RIPPLE_DENSITY_MIN / MAX in percent. */
    public static final int RIPPLE_DENSITY_MIN = 25, RIPPLE_DENSITY_MAX = 200;
    /** 2026-11-09 (session 2b): underwater.glsl's UNDERWATER_VISIBILITY_MIN / MAX in percent. */
    public static final int UNDERWATER_VISIBILITY_MIN = 25, UNDERWATER_VISIBILITY_MAX = 300;
    /** 2026-11-09 (session 3): the realistic clouds' coverage in percent (100: CloudMath.REAL_COVER of the sky). */
    public static final int CLOUD_COVERAGE_MAX = 200;
    /** 2026-11-09 (after session 3b): the realistic clouds' size and count in percent (CloudMath.SIZE_MIN..SIZE_MAX, 0..COUNT_MAX; 100 each the default). */
    public static final int CLOUD_SIZE_MIN = CloudMath.SIZE_MIN, CLOUD_SIZE_MAX = CloudMath.SIZE_MAX, CLOUD_COUNT_MAX = CloudMath.COUNT_MAX;
    /** 2026-11-09 (session 5): the aurora strength in percent (100 = AuroraMath's tuning). */
    public static final int AURORA_STRENGTH_MAX = CelestialUniforms.AURORA_STRENGTH_MAX;
    /** 2026-10-28: the sun warmth goes to twice the tuned warmth (ColourMath.SUN_WARMTH_MIRED per 100%). */
    public static final int SUN_WARMTH_MAX = 200;
    /** 2026-10-28: block and ore glow go to twice the tuned glow. */
    public static final int GLOW_MAX = 200;
    public static final int SKY_LIGHT_AMBIENT_MAX = 100;
    public static final int LIGHT_DEBUG_MAX = 5;
    /** 2026-10-17: the light debug value that paints the pixels the reflection pass mirrors (ReflectionUniforms), not a light view. */
    public static final int REFLECTIVE_DEBUG = 4;
    public static final int ANTI_ALIASING_MAX = 4;
    /** 2026-10-29: the block reflections' levels (BlockReflectionMath.OFF, HIGHLIGHTS, REFLECTIONS). */
    public static final int BLOCK_REFLECTIONS_MAX = 2;

    public static final String KEY_PREFIX = "vulkanmod.visuals.";
    public static final List<String> OPTION_NAMES = List.of("enabled", "exposure", "tone_strength", "contrast", "saturation", "grade_strength",
            "bloom_strength", "vignette_strength", "fog_strength", "fog_height", "weather_fog", "distance_haze", "sun_glow", "sunset_light", "light_shafts", "moonlight", "ao_strength", "sun_shading", "water_depth", "water_reflections", "water_ripples", "water_ripple_speed", "water_ripple_density", "underwater_visibility", "far_clouds", "cloud_style", "cloud_coverage", "cloud_quality", "celestial_shape", "aurora", "aurora_strength", "sun_shadows", "vanilla_shadows", "sky_light_ambient", "per_pixel_light", "light_colour", "sun_warmth", "block_light_colour", "block_glow", "ore_glow", "held_light", "anti_aliasing", "taa", "light_debug", "block_reflections");

    public static PostProcessSettings from(VisualsConfig config) {
        VisualsConfig c = config != null ? config : new VisualsConfig();
        return new PostProcessSettings(c.enabled, percent(c.exposure, EXPOSURE_MIN, EXPOSURE_MAX), percent(c.toneStrength, 0, STRENGTH_MAX),
                percent(c.contrast, CONTRAST_MIN, CONTRAST_MAX), percent(c.saturation, SATURATION_MIN, SATURATION_MAX),
                percent(c.gradeStrength, 0, STRENGTH_MAX), percent(c.bloomStrength, 0, STRENGTH_MAX), percent(c.vignetteStrength, 0, STRENGTH_MAX),
                percent(c.fogStrength, 0, FOG_STRENGTH_MAX), (float) clamp(c.fogHeight, FOG_HEIGHT_MIN, FOG_HEIGHT_MAX), percent(c.weatherFog, 0, FOG_STRENGTH_MAX), percent(c.distanceHaze, 0, FOG_STRENGTH_MAX),
                percent(c.sunGlow, 0, STRENGTH_MAX), percent(c.sunsetLight, 0, STRENGTH_MAX), percent(c.lightShafts, 0, STRENGTH_MAX),
                percent(c.moonlight, 0, STRENGTH_MAX), percent(c.aoStrength, 0, STRENGTH_MAX), percent(c.sunShading, 0, STRENGTH_MAX), percent(c.waterDepth, 0, STRENGTH_MAX), percent(c.waterReflections, 0, STRENGTH_MAX), percent(c.waterRipples, 0, STRENGTH_MAX), percent(c.waterRippleSpeed, 0, RIPPLE_SPEED_MAX), percent(c.waterRippleDensity, RIPPLE_DENSITY_MIN, RIPPLE_DENSITY_MAX), percent(c.underwaterVisibility, UNDERWATER_VISIBILITY_MIN, UNDERWATER_VISIBILITY_MAX), c.farClouds, clamp(c.cloudStyle, 0, CloudMath.STYLE_MAX), percent(c.cloudCoverage, 0, CLOUD_COVERAGE_MAX), percent(c.cloudSize, CLOUD_SIZE_MIN, CLOUD_SIZE_MAX), percent(c.cloudCount, 0, CLOUD_COUNT_MAX), clamp(c.cloudQuality, 0, CloudMath.QUALITY_MAX), clamp(c.celestialShape, 0, CelestialUniforms.SHAPE_MAX), clamp(c.aurora, 0, CelestialUniforms.AURORA_MAX), percent(c.auroraStrength, 0, AURORA_STRENGTH_MAX), c.sunShadows, c.vanillaShadows, percent(c.skyLightAmbient, 0, SKY_LIGHT_AMBIENT_MAX), c.perPixelLight, percent(c.lightColour, 0, STRENGTH_MAX), percent(c.sunWarmth, 0, SUN_WARMTH_MAX), percent(c.blockLightColour, 0, STRENGTH_MAX), percent(c.blockGlow, 0, GLOW_MAX), percent(c.oreGlow, 0, GLOW_MAX), c.heldLight, clamp(c.antiAliasing, 0, ANTI_ALIASING_MAX), c.taa, clamp(c.lightDebug, 0, LIGHT_DEBUG_MAX), clamp(c.blockReflections, 0, BLOCK_REFLECTIONS_MAX));
    }

    /** The values in the settings screen's units, for the log line. */
    public String describe() {
        return (this.enabled ? "on" : "off") + ", exposure " + pct(this.exposure) + ", tone " + pct(this.toneStrength) + ", contrast " + pct(this.contrast)
                + ", saturation " + pct(this.saturation) + ", grade " + pct(this.gradeStrength) + ", bloom " + pct(this.bloomStrength)
                + ", vignette " + pct(this.vignetteStrength) + ", fog " + pct(this.fogStrength) + "/" + Math.round(this.fogHeight) + ", weather fog " + pct(this.weatherFog) + ", distance haze " + pct(this.distanceHaze) + ", glow "
                + pct(this.sunGlow) + ", sunset " + pct(this.sunsetLight) + ", shafts " + pct(this.lightShafts) + ", moon " + pct(this.moonlight) + ", ao " + pct(this.aoStrength) + ", sun " + pct(this.sunShading) + ", water " + pct(this.waterDepth) + ", reflections " + pct(this.waterReflections) + ", ripples " + pct(this.waterRipples) + ", ripple speed " + pct(this.waterRippleSpeed) + ", ripple density " + pct(this.waterRippleDensity) + ", underwater visibility " + pct(this.underwaterVisibility) + ", clouds " + (this.farClouds ? "on" : "off") + ", cloud style " + this.cloudStyle + ", cloud coverage " + pct(this.cloudCoverage) + ", cloud size " + pct(this.cloudSize) + ", cloud count " + pct(this.cloudCount) + ", cloud quality " + this.cloudQuality + ", sun and moon " + this.celestialShape + ", aurora " + this.aurora + "/" + pct(this.auroraStrength) + ", shadows " + (this.sunShadows ? "on" : "off") + ", vanilla shadows " + (this.vanillaShadows ? "on" : "off") + ", sky ambient " + pct(this.skyLightAmbient) + ", pixel light " + (this.perPixelLight ? "on" : "off") + ", light colour " + pct(this.lightColour) + ", sun warmth " + pct(this.sunWarmth) + ", block light colour " + pct(this.blockLightColour) + ", block glow " + pct(this.blockGlow) + ", ore glow " + pct(this.oreGlow) + ", held light " + (this.heldLight ? "on" : "off") + ", anti-aliasing " + this.antiAliasing + ", taa " + (this.taa ? "on" : "off") + ", light debug " + this.lightDebug + ", block reflections " + this.blockReflections;
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
