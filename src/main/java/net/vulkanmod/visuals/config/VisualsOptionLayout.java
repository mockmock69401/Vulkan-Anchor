package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.fsr.FsrSettings;

import java.util.List;

import static net.vulkanmod.visuals.render.PostProcessSettings.*;

/**
 * The visuals settings grouped into pages and blocks for the video settings screen (VisualsSettingsPage), with each setting's
 * range, performance cost and the switch it depends on, and the VisualsConfig field behind it. Plain data (no screen class), so
 * it is tested without the game. Keys are PostProcessSettings.KEY_PREFIX + key; pages and blocks have KEY_PREFIX + "page." /
 * "block." + key. W10: the ambient occlusion's strength (aoStrength) is the item ao_strength in the lighting page's shading block.
 */
public final class VisualsOptionLayout {
    public enum Kind {SWITCH, PERCENT, BLOCKS, CHOICE}

    public enum Impact {NONE, LOW, MEDIUM, HIGH}

    /** requires: the key of a switch that must be on for this item to be editable (null: only the post-process switch). */
    public record Item(String key, Kind kind, int min, int max, Impact impact, String requires) {
    }

    public record Block(String key, List<Item> items) {
    }

    public record Page(String key, List<Block> blocks) {
    }

    public static final String ENABLED = "enabled";
    /** The page's first block: the reset to defaults. */
    public static final String GENERAL = "general", RESET = "reset_defaults";
    /** FG design 7: frame generation does not depend on the post-process, so its switch is not greyed out with it. */
    public static final String FRAME_GENERATION = "frame_generation";
    /** FSR design 7: AMD FSR 3.1 frame generation; independent of the post-process like the above. */
    public static final String FSR_FRAME_GENERATION = "fsr_frame_generation";

    /** Whether item follows the post-process master switch (every item but the switch itself and frame generation). */
    public static boolean followsMasterSwitch(Item item) {
        return !item.key().equals(ENABLED) && !item.key().equals(FRAME_GENERATION) && !item.key().equals(FSR_FRAME_GENERATION);
    }

    /** The Distant Horizons block: shown only when DH draws through the fork (VisualsSettingsPage.shownBlocks). */
    public static final String DH_BLOCK = "dh";
    /** Optical Flow and FSR frame generation, with a warning under them. */
    public static final String FRAME_GENERATION_BLOCK = "frame_generation";

    private static Item percent(String key, int min, int max, Impact impact) {
        return new Item(key, Kind.PERCENT, min, max, impact, null);
    }

    private static Item percent(String key, int max) {
        return percent(key, 0, max, Impact.NONE);
    }

    private static Item toggle(String key, Impact impact, String requires) {
        return new Item(key, Kind.SWITCH, 0, 1, impact, requires);
    }

    public static final List<Page> PAGES = List.of(
            new Page("image", List.of(
                    new Block("post", List.of(toggle(ENABLED, Impact.MEDIUM, null))),
                    new Block("tone", List.of(
                            percent("exposure", EXPOSURE_MIN, EXPOSURE_MAX, Impact.NONE),
                            percent("tone_strength", STRENGTH_MAX),
                            percent("contrast", CONTRAST_MIN, CONTRAST_MAX, Impact.NONE),
                            percent("saturation", SATURATION_MIN, SATURATION_MAX, Impact.NONE),
                            percent("grade_strength", STRENGTH_MAX))),
                    new Block("effects", List.of(
                            percent("bloom_strength", 0, STRENGTH_MAX, Impact.LOW),
                            percent("vignette_strength", STRENGTH_MAX),
                            new Item("anti_aliasing", Kind.CHOICE, 0, ANTI_ALIASING_MAX, Impact.MEDIUM, null),
                            toggle("taa", Impact.LOW, null))),
                    //2026-10-25: the two generators in their own block, with the cost warning under them (block.frame_generation.note)
                    new Block(FRAME_GENERATION_BLOCK, List.of(
                            toggle(FRAME_GENERATION, Impact.LOW, null),
                            toggle(FSR_FRAME_GENERATION, Impact.LOW, null))))),
            new Page("atmosphere", List.of(
                    new Block("fog", List.of(
                            percent("fog_strength", 0, FOG_STRENGTH_MAX, Impact.NONE),
                            new Item("fog_height", Kind.BLOCKS, FOG_HEIGHT_MIN, FOG_HEIGHT_MAX, Impact.NONE, null),
                            percent("sun_glow", STRENGTH_MAX))),
                    new Block("sky", List.of(
                            percent("sunset_light", STRENGTH_MAX),
                            percent("light_shafts", 0, STRENGTH_MAX, Impact.MEDIUM),
                            percent("moonlight", STRENGTH_MAX),
                            toggle("far_clouds", Impact.MEDIUM, null))))),
            new Page("lighting", List.of(
                    new Block("shading", List.of(
                            percent("sun_shading", STRENGTH_MAX),
                            percent("ao_strength", 0, STRENGTH_MAX, Impact.MEDIUM),
                            percent("light_colour", STRENGTH_MAX),
                            percent("sun_warmth", SUN_WARMTH_MAX),
                            percent("block_light_colour", STRENGTH_MAX),
                            toggle("edge_cover", Impact.NONE, null))),
                    new Block(DH_BLOCK, List.of(
                            toggle("dh_lods", Impact.HIGH, null),
                            toggle("dh_shadows", Impact.MEDIUM, "dh_lods"))),
                    new Block("shadows", List.of(
                            toggle("sun_shadows", Impact.HIGH, null),
                            toggle("vanilla_shadows", Impact.MEDIUM, "sun_shadows"))),
                    new Block("light", List.of(
                            toggle("per_pixel_light", Impact.LOW, null),
                            new Item("sky_light_ambient", Kind.PERCENT, 0, SKY_LIGHT_AMBIENT_MAX, Impact.NONE, "per_pixel_light"),
                            toggle("held_light", Impact.LOW, "per_pixel_light"))),
                    new Block("debug", List.of(new Item("light_debug", Kind.CHOICE, 0, LIGHT_DEBUG_MAX, Impact.NONE, null))))),
            new Page("water", List.of(
                    new Block("water", List.of(
                            percent("water_depth", STRENGTH_MAX),
                            percent("water_reflections", 0, STRENGTH_MAX, Impact.MEDIUM),
                            percent("water_ripples", STRENGTH_MAX),
                            percent("water_ripple_speed", RIPPLE_SPEED_MAX))),
                    new Block("wind", List.of(
                            percent("foliage_wind", STRENGTH_MAX))))));

    private VisualsOptionLayout() {
    }

    /** The value a fresh VisualsConfig has (the reset). */
    public static int defaultValue(Item item) {
        return get(new VisualsConfig(), item);
    }

    /** The setting's value in the screen's units (percent, blocks, a choice's index, a switch as 1 or 0), kept in its range. */
    public static int get(VisualsConfig c, Item item) {
        int v = switch (item.key()) {
            case ENABLED -> c.enabled ? 1 : 0;
            case "exposure" -> c.exposure;
            case "tone_strength" -> c.toneStrength;
            case "contrast" -> c.contrast;
            case "saturation" -> c.saturation;
            case "grade_strength" -> c.gradeStrength;
            case "bloom_strength" -> c.bloomStrength;
            case "vignette_strength" -> c.vignetteStrength;
            case "anti_aliasing" -> c.antiAliasing;
            case "taa" -> c.taa ? 1 : 0;
            case FRAME_GENERATION -> c.frameGeneration ? 1 : 0;
            case FSR_FRAME_GENERATION -> c.fsrFrameGeneration ? 1 : 0;
            case "fog_strength" -> c.fogStrength;
            case "fog_height" -> c.fogHeight;
            case "sun_glow" -> c.sunGlow;
            case "sunset_light" -> c.sunsetLight;
            case "light_shafts" -> c.lightShafts;
            case "moonlight" -> c.moonlight;
            case "far_clouds" -> c.farClouds ? 1 : 0;
            case "sun_shading" -> c.sunShading;
            case "ao_strength" -> c.aoStrength;
            case "light_colour" -> c.lightColour;
            case "sun_warmth" -> c.sunWarmth;
            case "block_light_colour" -> c.blockLightColour;
            case "edge_cover" -> c.edgeCover ? 1 : 0;
            case "sun_shadows" -> c.sunShadows ? 1 : 0;
            case "vanilla_shadows" -> c.vanillaShadows ? 1 : 0;
            case "per_pixel_light" -> c.perPixelLight ? 1 : 0;
            case "sky_light_ambient" -> c.skyLightAmbient;
            case "held_light" -> c.heldLight ? 1 : 0;
            case "dh_lods" -> c.dhLods ? 1 : 0;
            case "dh_shadows" -> c.dhShadows ? 1 : 0;
            case "light_debug" -> c.lightDebug;
            case "water_depth" -> c.waterDepth;
            case "water_reflections" -> c.waterReflections;
            case "water_ripples" -> c.waterRipples;
            case "water_ripple_speed" -> c.waterRippleSpeed;
            case "foliage_wind" -> c.foliageWind;
            default -> throw new IllegalArgumentException(item.key());
        };
        return Math.max(item.min(), Math.min(item.max(), v));
    }

    /** Writes the setting from the screen's units (a switch: anything but 0 is on). */
    public static void set(VisualsConfig c, Item item, int value) {
        int v = Math.max(item.min(), Math.min(item.max(), value));
        switch (item.key()) {
            case ENABLED -> c.enabled = v != 0;
            case "exposure" -> c.exposure = v;
            case "tone_strength" -> c.toneStrength = v;
            case "contrast" -> c.contrast = v;
            case "saturation" -> c.saturation = v;
            case "grade_strength" -> c.gradeStrength = v;
            case "bloom_strength" -> c.bloomStrength = v;
            case "vignette_strength" -> c.vignetteStrength = v;
            case "anti_aliasing" -> c.antiAliasing = v;
            case "taa" -> c.taa = v != 0;
            case FRAME_GENERATION, FSR_FRAME_GENERATION -> FsrSettings.onToggle(c, item.key(), v != 0);
            case "fog_strength" -> c.fogStrength = v;
            case "fog_height" -> c.fogHeight = v;
            case "sun_glow" -> c.sunGlow = v;
            case "sunset_light" -> c.sunsetLight = v;
            case "light_shafts" -> c.lightShafts = v;
            case "moonlight" -> c.moonlight = v;
            case "far_clouds" -> c.farClouds = v != 0;
            case "sun_shading" -> c.sunShading = v;
            case "ao_strength" -> c.aoStrength = v;
            case "light_colour" -> c.lightColour = v;
            case "sun_warmth" -> c.sunWarmth = v;
            case "block_light_colour" -> c.blockLightColour = v;
            case "edge_cover" -> c.edgeCover = v != 0;
            case "sun_shadows" -> c.sunShadows = v != 0;
            case "vanilla_shadows" -> c.vanillaShadows = v != 0;
            case "per_pixel_light" -> c.perPixelLight = v != 0;
            case "sky_light_ambient" -> c.skyLightAmbient = v;
            case "held_light" -> c.heldLight = v != 0;
            case "dh_lods" -> c.dhLods = v != 0;
            case "dh_shadows" -> c.dhShadows = v != 0;
            case "light_debug" -> c.lightDebug = v;
            case "water_depth" -> c.waterDepth = v;
            case "water_reflections" -> c.waterReflections = v;
            case "water_ripples" -> c.waterRipples = v;
            case "water_ripple_speed" -> c.waterRippleSpeed = v;
            case "foliage_wind" -> c.foliageWind = v;
            default -> throw new IllegalArgumentException(item.key());
        }
    }
}
