package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.fsr.FsrSettings;
import net.vulkanmod.visuals.particle.VanillaParticles;
import net.vulkanmod.visuals.render.CelestialUniforms;
import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.render.DimensionUniforms;
import net.vulkanmod.visuals.render.PbrMath;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import net.vulkanmod.visuals.render.ShadowMath;
import net.vulkanmod.visuals.render.StarMath;

import java.util.List;

import static net.vulkanmod.visuals.render.PostProcessSettings.*;

/**
 * The visuals settings grouped into pages and blocks for the video settings screen (VisualsSettingsPage), with each setting's
 * range, performance cost and the switch it depends on, and the VisualsConfig field behind it. Plain data (no screen class), so
 * it is tested without the game. Keys are PostProcessSettings.KEY_PREFIX + key; pages and blocks have KEY_PREFIX + "page." /
 * "block." + key. W10: the ambient occlusion's strength (aoStrength) is the item ao_strength in the lighting page's shading block.
 */
public final class VisualsOptionLayout {
    /** 2026-11-10 (session 8): COLOUR, a 0xRRGGBB shader colour (ShaderColours) picked with the colour picker. */
    public enum Kind {SWITCH, PERCENT, BLOCKS, CHUNKS, CHOICE, COLOUR}

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

    /** 2026-11-11: the atmosphere page's weather block: the shader weather switch, then each effect's type, size and amount. */
    public static final String WEATHER_BLOCK = "weather";

    private static List<Item> weatherItems() {
        List<Item> items = new java.util.ArrayList<>();
        items.add(toggle("shader_weather", Impact.LOW, null));
        items.addAll(ParticleEffect.SNOW.items(Impact.LOW));
        items.addAll(ParticleEffect.RAIN.items(Impact.LOW));
        return List.copyOf(items);
    }

    /** 2026-11-10 (session 4): the performance preset (VisualsPresets), first on the first page, above the reset. */
    public static final String PRESET = "preset";
    /** 2026-11-10 (session 9): the lighting page's LabPBR block, with a note under it (block.materials.note: it needs a LabPBR resource pack). */
    public static final String MATERIALS_BLOCK = "materials";

    /**
     * 2026-11-10 (session 8): the colours page's first block, and the button on it that puts every colour back (as the first page's reset
     * does for everything).
     */
    public static final String COLOURS_FIRST = "colours_sky", RESET_COLOURS = "reset_colours";
    /** 2026-11-10 (session 8): the colour palette (ColourPresets), first on the colours page, above its reset. */
    public static final String COLOUR_PRESET = "colour_preset";

    //A shader colour (ShaderColours.Colour): the picker over 0x000000..0xFFFFFF; requires as for the settings it colours
    private static Item colour(Colour c, String requires) {
        return new Item(c.key(), Kind.COLOUR, 0, 0xFFFFFF, Impact.NONE, requires);
    }

    /**
     * Session 8 follow-up: the look settings a colour palette sets (ColourPresets.LOOK_KEYS). Each is on its own page and again in the colours
     * page's look block under the palette: the same setting, two widgets kept equal (VisualsSettingsPage.pages); item(key) finds the first.
     */
    public static final String COLOURS_LOOK = "colours_look";
    private static final Item SATURATION = percent("saturation", SATURATION_MIN, SATURATION_MAX, Impact.NONE);
    private static final Item BLOOM = percent("bloom_strength", 0, STRENGTH_MAX, Impact.LOW);
    private static final Item FOG = percent("fog_strength", 0, FOG_STRENGTH_MAX, Impact.NONE);
    private static final Item MOONLIGHT = percent("moonlight", STRENGTH_MAX);
    //2026-11-13: the image page's tone block, which the palettes set too
    private static final Item EXPOSURE = percent("exposure", EXPOSURE_MIN, EXPOSURE_MAX, Impact.NONE);
    private static final Item TONE = percent("tone_strength", STRENGTH_MAX);
    private static final Item CONTRAST = percent("contrast", CONTRAST_MIN, CONTRAST_MAX, Impact.NONE);
    private static final Item GRADE = percent("grade_strength", STRENGTH_MAX);

    public static final List<Page> PAGES = List.of(
            new Page("image", List.of(
                    new Block(GENERAL, List.of(new Item(PRESET, Kind.CHOICE, VisualsPresets.POTATO, VisualsPresets.CUSTOM, Impact.NONE, null))),
                    new Block("post", List.of(toggle(ENABLED, Impact.MEDIUM, null))),
                    new Block("tone", List.of(
                            EXPOSURE,
                            TONE,
                            CONTRAST,
                            SATURATION,
                            GRADE)),
                    new Block("effects", List.of(
                            BLOOM,
                            percent("vignette_strength", STRENGTH_MAX),
                            new Item("anti_aliasing", Kind.CHOICE, 0, ANTI_ALIASING_MAX, Impact.MEDIUM, null),
                            toggle("taa", Impact.LOW, null))),
                    //2026-10-25: the two generators in their own block, with the cost warning under them (block.frame_generation.note)
                    new Block(FRAME_GENERATION_BLOCK, List.of(
                            toggle(FRAME_GENERATION, Impact.LOW, null),
                            toggle(FSR_FRAME_GENERATION, Impact.LOW, null))))),
            new Page("atmosphere", List.of(
                    new Block("fog", List.of(
                            FOG,
                            new Item("fog_height", Kind.BLOCKS, FOG_HEIGHT_MIN, FOG_HEIGHT_MAX, Impact.NONE, null),
                            percent("weather_fog", 0, FOG_STRENGTH_MAX, Impact.NONE),
                            percent("distance_haze", 0, FOG_STRENGTH_MAX, Impact.NONE),
                            percent("sun_glow", STRENGTH_MAX))),
                    new Block("sky", List.of(
                            percent("sunset_light", STRENGTH_MAX),
                            percent("light_shafts", 0, STRENGTH_MAX, Impact.MEDIUM),
                            MOONLIGHT,
                            toggle("far_clouds", Impact.MEDIUM, null),
                            //2026-11-09 (session 3): the realistic clouds and their settings (greyed out with the vanilla shape)
                            new Item("cloud_style", Kind.CHOICE, 0, CloudMath.STYLE_MAX, Impact.HIGH, "far_clouds"),
                            new Item("cloud_coverage", Kind.PERCENT, 0, CLOUD_COVERAGE_MAX, Impact.NONE, "cloud_style"),
                            //2026-11-09 (after session 3b): each cloud's size (a bigger cloud is also taller: a little more cost) and how many there are
                            new Item("cloud_size", Kind.PERCENT, CLOUD_SIZE_MIN, CLOUD_SIZE_MAX, Impact.LOW, "cloud_style"),
                            new Item("cloud_count", Kind.PERCENT, 0, CLOUD_COUNT_MAX, Impact.NONE, "cloud_style"),
                            new Item("cloud_quality", Kind.CHOICE, 0, CloudMath.QUALITY_MAX, Impact.HIGH, "cloud_style"),
                            //2026-11-09 (session 5): round sun and moon; auroras (the strength greyed out with them off)
                            new Item("celestial_shape", Kind.CHOICE, 0, CelestialUniforms.SHAPE_MAX, Impact.NONE, null),
                            //2026-11-11 (session 10 follow-up): the round sun's and moon's size (greyed out with the square shape: vanilla's art)
                            new Item("sun_size", Kind.PERCENT, CelestialUniforms.SIZE_MIN, CelestialUniforms.SIZE_MAX, Impact.NONE, "celestial_shape"),
                            new Item("moon_size", Kind.PERCENT, CelestialUniforms.SIZE_MIN, CelestialUniforms.SIZE_MAX, Impact.NONE, "celestial_shape"),
                            new Item("aurora", Kind.CHOICE, 0, CelestialUniforms.AURORA_MAX, Impact.LOW, null),
                            new Item("aurora_strength", Kind.PERCENT, 0, CelestialUniforms.AURORA_STRENGTH_MAX, Impact.NONE, "aurora"),
                            //2026-11-11 (session 12): vanilla's stars: how many, how big, how bright (100 = vanilla's)
                            //2026-11-11 (session 13): their shape (vanilla's squares or round soft points)
                            new Item("star_type", Kind.CHOICE, 0, StarMath.TYPE_MAX, Impact.NONE, null),
                            new Item("star_count", Kind.PERCENT, 0, StarMath.COUNT_MAX, Impact.NONE, null),
                            new Item("star_size", Kind.PERCENT, StarMath.SIZE_MIN, StarMath.SIZE_MAX, Impact.NONE, null),
                            new Item("star_brightness", Kind.PERCENT, 0, StarMath.BRIGHTNESS_MAX, Impact.NONE, null),
                            //2026-11-12 (session 14 follow-up): the far clouds and the shader weather at half resolution (much cheaper, a little
                            // softer); on in the Potato, Low and Medium presets
                            toggle("half_res_effects", Impact.NONE, null))),
                    //2026-11-13 (session 19): the Nether's heat haze, nether portals' glow, the End's galaxy sky
                    new Block("dimensions", List.of(
                            percent("nether_heat_haze", 0, DimensionUniforms.STRENGTH_MAX, Impact.LOW),
                            percent("portal_glow", 0, DimensionUniforms.STRENGTH_MAX, Impact.LOW),
                            percent("end_galaxy", 0, DimensionUniforms.STRENGTH_MAX, Impact.LOW))),
                    //2026-11-09 (session 6): shader-drawn rain and snow (off: vanilla's columns); 2026-11-11: the snow's and the rain's type,
                    // size and amount (ParticleEffect's rows; the amount costs as many drops: about 75% of the pass at 50, 110% at 200)
                    new Block(WEATHER_BLOCK, weatherItems()))),
            new Page("lighting", List.of(
                    new Block("shading", List.of(
                            percent("sun_shading", STRENGTH_MAX),
                            percent("ao_strength", 0, STRENGTH_MAX, Impact.MEDIUM),
                            percent("light_colour", STRENGTH_MAX),
                            percent("sun_warmth", SUN_WARMTH_MAX),
                            percent("block_light_colour", STRENGTH_MAX),
                            percent("block_glow", GLOW_MAX),
                            percent("ore_glow", GLOW_MAX),
                            toggle("edge_cover", Impact.NONE, null))),
                    new Block(DH_BLOCK, List.of(
                            toggle("dh_lods", Impact.HIGH, null),
                            toggle("dh_shadows", Impact.MEDIUM, "dh_lods"))),
                    new Block("shadows", List.of(
                            toggle("sun_shadows", Impact.HIGH, null),
                            toggle("vanilla_shadows", Impact.MEDIUM, "sun_shadows"),
                            new Item("shadow_distance", Kind.CHUNKS, ShadowMath.SHADOW_DISTANCE_MIN, ShadowMath.SHADOW_DISTANCE_MAX, Impact.HIGH, "vanilla_shadows"),
                            toggle("entity_shadows", Impact.MEDIUM, "sun_shadows"))),
                    new Block("light", List.of(
                            toggle("per_pixel_light", Impact.LOW, null),
                            new Item("sky_light_ambient", Kind.PERCENT, 0, SKY_LIGHT_AMBIENT_MAX, Impact.NONE, "per_pixel_light"),
                            toggle("held_light", Impact.LOW, "per_pixel_light"))),
                    //2026-11-10 (shader roadmap session 9): LabPBR materials from the resource packs (PbrAtlas): the switch, the normal maps'
                    // strength, the reflections of smooth materials and the parallax (each greyed out with PBR off)
                    new Block(MATERIALS_BLOCK, List.of(
                            toggle("pbr", Impact.MEDIUM, "per_pixel_light"),
                            new Item("pbr_normal_strength", Kind.PERCENT, 0, PbrMath.NORMAL_STRENGTH_MAX, Impact.NONE, "pbr"),
                            toggle("pbr_reflections", Impact.MEDIUM, "pbr"),
                            toggle("pbr_parallax", Impact.LOW, "pbr"))),
                    new Block("debug", List.of(new Item("light_debug", Kind.CHOICE, 0, LIGHT_DEBUG_MAX, Impact.NONE, null))))),
            new Page("water", List.of(
                    new Block("water", List.of(
                            percent("water_depth", STRENGTH_MAX),
                            percent("water_reflections", 0, STRENGTH_MAX, Impact.MEDIUM),
                            new Item("block_reflections", Kind.CHOICE, 0, BLOCK_REFLECTIONS_MAX, Impact.MEDIUM, null),
                            percent("water_ripples", STRENGTH_MAX),
                            percent("water_ripple_speed", RIPPLE_SPEED_MAX),
                            percent("water_ripple_density", RIPPLE_DENSITY_MIN, RIPPLE_DENSITY_MAX, Impact.NONE),
                            percent("underwater_visibility", UNDERWATER_VISIBILITY_MIN, UNDERWATER_VISIBILITY_MAX, Impact.NONE))),
                    new Block("wind", List.of(
                            percent("foliage_wind", STRENGTH_MAX))))),
            //2026-11-11 (shader roadmap session 13, the user: "every vanilla effect that looks pixel-shaped ... type, size, amount and colour"):
            // a block per particle group (VanillaParticles.Group): type (vanilla / realistic), size and amount; the colours are on the colours page
            new Page("particles", particleBlocks()),
            //2026-11-10 (session 8, the user: "the parts with set colours, such as the sunset and the sky's colour, as colour pickers"):
            // ShaderColours' colours by what they colour; the defaults are the look before the pickers, and the presets leave them alone
            new Page("colours", List.of(
                    new Block(COLOURS_FIRST, List.of(
                            new Item(COLOUR_PRESET, Kind.CHOICE, ColourPresets.DEFAULT, ColourPresets.CUSTOM, Impact.NONE, null),
                            colour(Colour.DAY_SKY, null),
                            colour(Colour.SUNSET, null),
                            colour(Colour.SUNRISE, null),
                            colour(Colour.SUN_GLOW, null),
                            colour(Colour.NIGHT_SKY, null),
                            //2026-11-11 (session 12): the stars
                            colour(Colour.STARS, null))),
                    //Session 8 follow-up: what a palette sets besides the colours (also on the image and atmosphere pages)
                    new Block(COLOURS_LOOK, List.of(BLOOM, SATURATION, FOG, MOONLIGHT, EXPOSURE, TONE, CONTRAST, GRADE)),
                    new Block("colours_air", List.of(
                            colour(Colour.FOG, null),
                            colour(Colour.OVERCAST, null),
                            colour(Colour.CLOUDS, "far_clouds"),
                            colour(Colour.RAIN, "shader_weather"),
                            colour(Colour.SNOW, "shader_weather"))),
                    new Block("colours_light", List.of(
                            colour(Colour.SUNLIGHT, null),
                            colour(Colour.SHADE_LIGHT, null),
                            colour(Colour.MOON, null),
                            colour(Colour.BLOCK_LIGHT, null))),
                    new Block("colours_aurora", List.of(
                            colour(Colour.AURORA_MAIN, "aurora"),
                            colour(Colour.AURORA_UPPER, "aurora"),
                            colour(Colour.AURORA_LOWER, "aurora"),
                            colour(Colour.AURORA_END, "aurora"))),
                    new Block("colours_water", List.of(
                            colour(Colour.WATER, null),
                            colour(Colour.UNDERWATER, null))),
                    //Session 13: the particle groups' colours
                    new Block("colours_particles", List.of(
                            colour(Colour.PARTICLE_SMOKE, null),
                            colour(Colour.PARTICLE_FLAMES, null),
                            colour(Colour.PARTICLE_EMBERS, null),
                            colour(Colour.PARTICLE_DRIPS, null),
                            colour(Colour.PARTICLE_MAGIC, null),
                            colour(Colour.PARTICLE_AMBIENT, null),
                            colour(Colour.PARTICLE_COMBAT, null))))));

    private VisualsOptionLayout() {
    }

    private static List<Block> particleBlocks() {
        java.util.ArrayList<Block> blocks = new java.util.ArrayList<>();
        for (VanillaParticles.Group g : VanillaParticles.Group.values()) blocks.add(new Block("particles_" + g.key(), g.effect().items(Impact.LOW)));
        return List.copyOf(blocks);
    }

    /** The item behind key (IllegalArgumentException when there is none). */
    public static Item item(String key) {
        for (Page page : PAGES) {
            for (Block block : page.blocks()) {
                for (Item item : block.items()) if (item.key().equals(key)) return item;
            }
        }
        throw new IllegalArgumentException(key);
    }

    /** The value a fresh VisualsConfig has (the reset). */
    public static int defaultValue(Item item) {
        return get(new VisualsConfig(), item);
    }

    /** The setting's value in the screen's units (percent, blocks, a choice's index, a switch as 1 or 0), kept in its range. */
    public static int get(VisualsConfig c, Item item) {
        int v = switch (item.key()) {
            case PRESET -> c.preset;
            case COLOUR_PRESET -> c.colourPreset;
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
            case "weather_fog" -> c.weatherFog;
            case "distance_haze" -> c.distanceHaze;
            case "sun_glow" -> c.sunGlow;
            case "sunset_light" -> c.sunsetLight;
            case "light_shafts" -> c.lightShafts;
            case "moonlight" -> c.moonlight;
            case "far_clouds" -> c.farClouds ? 1 : 0;
            case "cloud_style" -> c.cloudStyle;
            case "cloud_coverage" -> c.cloudCoverage;
            case "cloud_size" -> c.cloudSize;
            case "cloud_count" -> c.cloudCount;
            case "celestial_shape" -> c.celestialShape;
            case "sun_size" -> c.sunSize;
            case "moon_size" -> c.moonSize;
            case "aurora" -> c.aurora;
            case "aurora_strength" -> c.auroraStrength;
            case "star_type" -> c.starType;
            case "star_count" -> c.starCount;
            case "star_size" -> c.starSize;
            case "star_brightness" -> c.starBrightness;
            case "shader_weather" -> c.shaderWeather ? 1 : 0;
            case "half_res_effects" -> c.halfResEffects ? 1 : 0;
            case "nether_heat_haze" -> c.netherHeatHaze;
            case "portal_glow" -> c.portalGlow;
            case "end_galaxy" -> c.endGalaxy;
            case "cloud_quality" -> c.cloudQuality;
            case "sun_shading" -> c.sunShading;
            case "ao_strength" -> c.aoStrength;
            case "light_colour" -> c.lightColour;
            case "sun_warmth" -> c.sunWarmth;
            case "block_light_colour" -> c.blockLightColour;
            case "block_glow" -> c.blockGlow;
            case "ore_glow" -> c.oreGlow;
            case "edge_cover" -> c.edgeCover ? 1 : 0;
            case "sun_shadows" -> c.sunShadows ? 1 : 0;
            case "vanilla_shadows" -> c.vanillaShadows ? 1 : 0;
            case "shadow_distance" -> c.shadowDistance;
            case "entity_shadows" -> c.entityShadows ? 1 : 0;
            case "per_pixel_light" -> c.perPixelLight ? 1 : 0;
            case "sky_light_ambient" -> c.skyLightAmbient;
            case "held_light" -> c.heldLight ? 1 : 0;
            case "pbr" -> c.pbr ? 1 : 0;
            case "pbr_normal_strength" -> c.pbrNormalStrength;
            case "pbr_reflections" -> c.pbrReflections ? 1 : 0;
            case "pbr_parallax" -> c.pbrParallax ? 1 : 0;
            case "dh_lods" -> c.dhLods ? 1 : 0;
            case "dh_shadows" -> c.dhShadows ? 1 : 0;
            case "light_debug" -> c.lightDebug;
            case "water_depth" -> c.waterDepth;
            case "water_reflections" -> c.waterReflections;
            case "block_reflections" -> c.blockReflections;
            case "water_ripples" -> c.waterRipples;
            case "water_ripple_speed" -> c.waterRippleSpeed;
            case "water_ripple_density" -> c.waterRippleDensity;
            case "underwater_visibility" -> c.underwaterVisibility;
            case "foliage_wind" -> c.foliageWind;
            default -> {
                ParticleEffect.Target effect = ParticleEffect.byItemKey(item.key());
                if (effect != null) yield effect.effect().get(c, effect.part());
                Colour colour = Colour.byKey(item.key());
                if (colour == null) throw new IllegalArgumentException(item.key());
                yield c.colour(colour);
            }
        };
        return Math.max(item.min(), Math.min(item.max(), v));
    }

    /** Writes the setting from the screen's units (a switch: anything but 0 is on). A preset level also sets its settings (VisualsPresets.apply). */
    public static void set(VisualsConfig c, Item item, int value) {
        int v = Math.max(item.min(), Math.min(item.max(), value));
        switch (item.key()) {
            case PRESET -> VisualsPresets.apply(c, v);
            case COLOUR_PRESET -> ColourPresets.apply(c, v);
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
            case "weather_fog" -> c.weatherFog = v;
            case "distance_haze" -> c.distanceHaze = v;
            case "sun_glow" -> c.sunGlow = v;
            case "sunset_light" -> c.sunsetLight = v;
            case "light_shafts" -> c.lightShafts = v;
            case "moonlight" -> c.moonlight = v;
            case "far_clouds" -> c.farClouds = v != 0;
            case "cloud_style" -> c.cloudStyle = v;
            case "cloud_coverage" -> c.cloudCoverage = v;
            case "cloud_size" -> c.cloudSize = v;
            case "cloud_count" -> c.cloudCount = v;
            case "celestial_shape" -> c.celestialShape = v;
            case "sun_size" -> c.sunSize = v;
            case "moon_size" -> c.moonSize = v;
            case "aurora" -> c.aurora = v;
            case "aurora_strength" -> c.auroraStrength = v;
            case "star_type" -> c.starType = v;
            case "star_count" -> c.starCount = v;
            case "star_size" -> c.starSize = v;
            case "star_brightness" -> c.starBrightness = v;
            case "shader_weather" -> c.shaderWeather = v != 0;
            case "half_res_effects" -> c.halfResEffects = v != 0;
            case "nether_heat_haze" -> c.netherHeatHaze = v;
            case "portal_glow" -> c.portalGlow = v;
            case "end_galaxy" -> c.endGalaxy = v;
            case "cloud_quality" -> c.cloudQuality = v;
            case "sun_shading" -> c.sunShading = v;
            case "ao_strength" -> c.aoStrength = v;
            case "light_colour" -> c.lightColour = v;
            case "sun_warmth" -> c.sunWarmth = v;
            case "block_light_colour" -> c.blockLightColour = v;
            case "block_glow" -> c.blockGlow = v;
            case "ore_glow" -> c.oreGlow = v;
            case "edge_cover" -> c.edgeCover = v != 0;
            case "sun_shadows" -> c.sunShadows = v != 0;
            case "vanilla_shadows" -> c.vanillaShadows = v != 0;
            case "shadow_distance" -> c.shadowDistance = v;
            case "entity_shadows" -> c.entityShadows = v != 0;
            case "per_pixel_light" -> c.perPixelLight = v != 0;
            case "sky_light_ambient" -> c.skyLightAmbient = v;
            case "held_light" -> c.heldLight = v != 0;
            case "pbr" -> c.pbr = v != 0;
            case "pbr_normal_strength" -> c.pbrNormalStrength = v;
            case "pbr_reflections" -> c.pbrReflections = v != 0;
            case "pbr_parallax" -> c.pbrParallax = v != 0;
            case "dh_lods" -> c.dhLods = v != 0;
            case "dh_shadows" -> c.dhShadows = v != 0;
            case "light_debug" -> c.lightDebug = v;
            case "water_depth" -> c.waterDepth = v;
            case "water_reflections" -> c.waterReflections = v;
            case "block_reflections" -> c.blockReflections = v;
            case "water_ripples" -> c.waterRipples = v;
            case "water_ripple_speed" -> c.waterRippleSpeed = v;
            case "water_ripple_density" -> c.waterRippleDensity = v;
            case "underwater_visibility" -> c.underwaterVisibility = v;
            case "foliage_wind" -> c.foliageWind = v;
            default -> {
                ParticleEffect.Target effect = ParticleEffect.byItemKey(item.key());
                if (effect != null) {
                    effect.effect().set(c, effect.part(), v);
                    return;
                }
                Colour colour = Colour.byKey(item.key());
                if (colour == null) throw new IllegalArgumentException(item.key());
                c.setColour(colour, v);
            }
        }
    }
}
