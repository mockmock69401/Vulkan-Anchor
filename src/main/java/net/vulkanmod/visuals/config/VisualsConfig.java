package net.vulkanmod.visuals.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.render.ShaderColours;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The shader settings, saved as config/vulkanmod_visuals.json. */
public class VisualsConfig {
    private static volatile VisualsConfig instance = new VisualsConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.TRANSIENT, Modifier.STATIC).create();
    /**
     * 6 (2026-11-10, session 4): the preset field; an older file has none and loads as Custom. 7 (2026-11-10, session 8): the shader
     * colours (colour* fields); an older file has none and keeps every colour's default (the picture it had). 8 (2026-11-10, session 9):
     * the LabPBR settings (pbr*); an older file has none: a saved preset level takes that level's PBR values (it stays the level), any
     * other file the defaults. 9 (2026-11-10, session 8 follow-up): the sunlight and shade light colours (colourSunlight, colourShadeLight;
     * an older file has neither: their defaults, the picture it had), and the palettes also set bloom, saturation and fog strength: an older
     * file keeps its values, and shows its palette only while they are the palette's too (else Custom; ColourPresets.validated).
     * 10 (2026-11-10, session 11): the anime cloud style (cloudStyle 2), which the Anime palette now includes: an older file saved on the
     * Anime palette (its colours and looks still Anime's) takes the anime clouds and stays on it; any other file keeps its style.
     * 11 (2026-11-11): the Realistic, Anime and Horror palettes' look values changed (ColourPresets.LOOK); a file of revision 9 or 10 still
     * exactly on a palette's old values takes the new ones and stays on it (ColourPresets.moveOldLook); any other file keeps its values.
     * 12 (2026-11-11, session 10 follow-up): the round sun's and moon's size (sunSize, moonSize); an older file has neither: 100, the new
     * default sizes (a smaller round sun and a bigger round moon than before, as the user asked).
     * 13 (2026-11-11): the snow's and rain's own type, size and amount (ParticleEffect: snowType, snowSize, snowAmount, rainType, rainSize,
     * rainAmount) in place of the one weatherDensity, and their brightness (snowBrightness, rainBrightness); an older file's density becomes
     * both amounts (the picture it had), the rest default.
     * 14 (2026-11-11, session 12): the star settings (starCount, starSize, starBrightness; neither the palettes nor the presets set them)
     * and the star colour (colourStars, which every palette sets); an older file has none: vanilla's stars. A file on a palette (every
     * other colour the palette's) takes that palette's star colour and stays on it (ColourPresets.moveOldStars); any other file keeps
     * white stars, the picture it had.
     * 15 (2026-11-11, session 13): the vanilla particle groups' look (ParticleEffect.PARTICLE_*: particle<Group>Type, Size, Amount,
     * Brightness) and colours (colourParticle<Group>, which every palette sets), and the star type (starType); an older file has none:
     * vanilla's particles and stars. A file on a palette (every other colour the palette's) takes that palette's particle colours and stays
     * on it (ColourPresets.moveOldColours, with the star colour for a file older than 14); any other file keeps the default colours.
     */
    private static final int REVISION = 15;
    /** Session 13: the first revision with the particle groups' colours. */
    static final int PARTICLE_LOOK_REVISION = 15;
    /** The first revision with the snow's and rain's own amounts (an older file's weatherDensity is both). */
    static final int PARTICLE_EFFECTS_REVISION = 13;
    /** The field the particle effect amounts replaced (read from older files only). */
    static final String OLD_WEATHER_DENSITY = "weatherDensity";
    /** 2026-11-11: the first revision with the changed palette look values; 9 the first with palette look values at all. */
    static final int PALETTE_LOOK_REVISION = 11, FIRST_LOOK_REVISION = 9;
    /** Session 12: the first revision with the star settings and the star colour. */
    static final int STARS_REVISION = 14;
    /** Session 11: the first revision whose Anime palette includes the anime clouds. */
    static final int ANIME_CLOUDS_REVISION = 10;
    /** The first revision with the PBR settings. */
    static final int PBR_REVISION = 8;
    /** Session 9: the PBR settings the presets set (VisualsPresets.VALUES). */
    static final String[] PBR_PRESET_KEYS = {"pbr", "pbr_reflections", "pbr_parallax"};
    /** The first revision with the preset field. */
    static final int PRESET_REVISION = 6;
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod Visuals");

    public static Path path() {
        return Path.of("config", "vulkanmod_visuals.json");
    }

    public static VisualsConfig get() {
        return instance;
    }

    public static VisualsConfig load() {
        instance = read(path());
        return instance;
    }

    /**
     * Reads the settings file. A missing file gives the defaults; a file that is not a JSON object gives the defaults (with a
     * warning); a field that is missing keeps its default, and a field of the wrong type keeps its default (with a warning)
     * without costing the other fields. Values are not clamped here (PostProcessSettings and the option layout do that). A file
     * older than currentRevision() is brought to it: no revision so far changed a field's meaning, so every value is kept.
     * Revision 6 (session 4) added the preset: a file older than that (or without a preset) loads as Custom with every value as
     * saved, and a saved level whose settings no longer all have its values is Custom too (VisualsPresets.validated).
     */
    public static VisualsConfig read(Path file) {
        VisualsConfig c = new VisualsConfig();
        if (!Files.isRegularFile(file)) return c;
        JsonObject json;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (root == null || !root.isJsonObject()) {
                LOGGER.warn("Visuals settings {} is not a JSON object, using the defaults", file);
                return c;
            }
            json = root.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Visuals settings {} cannot be read, using the defaults: {}", file, e.toString());
            return c;
        }
        for (Field f : VisualsConfig.class.getFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || !json.has(f.getName())) continue;
            try {
                JsonPrimitive p = json.get(f.getName()).getAsJsonPrimitive();
                if (f.getType() == boolean.class) {
                    if (!p.isBoolean()) throw new IllegalArgumentException("not a boolean");
                    f.setBoolean(c, p.getAsBoolean());
                } else {
                    if (!p.isNumber()) throw new IllegalArgumentException("not a number");
                    f.setInt(c, p.getAsBigDecimal().intValue());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("Visuals setting {} in {} is invalid ({}), using its default", f.getName(), file, e.getMessage());
            }
        }
        int fileRevision = json.has("revision") ? c.revision : 0;
        //2026-11-11: the one precipitation density became the snow's and the rain's amounts
        if (fileRevision < PARTICLE_EFFECTS_REVISION && json.has(OLD_WEATHER_DENSITY)) {
            try {
                int density = json.get(OLD_WEATHER_DENSITY).getAsJsonPrimitive().getAsBigDecimal().intValue();
                c.snowAmount = density;
                c.rainAmount = density;
            } catch (RuntimeException e) {
                LOGGER.warn("Visuals setting {} in {} is invalid ({}), using the default amounts", OLD_WEATHER_DENSITY, file, e.getMessage());
            }
        }
        if (fileRevision < PRESET_REVISION || !json.has("preset")) c.preset = VisualsPresets.CUSTOM;
        //Session 9: a level saved before the PBR settings existed keeps its level
        if (fileRevision < PBR_REVISION && VisualsPresets.isLevel(c.preset)) {
            for (String key : PBR_PRESET_KEYS) VisualsOptionLayout.set(c, VisualsOptionLayout.item(key), VisualsPresets.value(key, c.preset));
        }
        c.preset = VisualsPresets.validated(c);
        //Session 8: the colour palette is the one the colours are on (an older file has neither: the defaults, DEFAULT); since revision 9
        // also its bloom, saturation and fog strength (a file saved before keeps them, so a palette with other values there shows Custom)
        //2026-11-11: a file still exactly on a palette's old look values takes the new ones (first: the Anime check below reads the new row)
        //Session 12: a file on a palette before the star colour existed takes the palette's (first: the checks below compare every colour)
        //Session 13: likewise the particle groups' colours (and the star colour for a file older than both)
        if (fileRevision < PARTICLE_LOOK_REVISION) {
            java.util.EnumSet<Colour> missing = java.util.EnumSet.noneOf(Colour.class);
            if (fileRevision < STARS_REVISION && !json.has("colourStars")) missing.add(Colour.STARS);
            for (ParticleEffect e : ParticleEffect.values()) {
                if (e.colour().key().startsWith("colour_particle_") && !json.has(colourField(e.colour()))) missing.add(e.colour());
            }
            ColourPresets.moveOldColours(c, missing);
        }
        if (fileRevision >= FIRST_LOOK_REVISION && fileRevision < PALETTE_LOOK_REVISION) ColourPresets.moveOldLook(c);
        //Session 11: a file saved on the Anime palette before it included the anime clouds takes them (and stays on the palette)
        if (fileRevision < ANIME_CLOUDS_REVISION && c.colourPreset == ColourPresets.ANIME
                && ColourPresets.detect(c::colour, key -> key.equals(ColourPresets.CLOUD_KEY) ? CloudMath.STYLE_ANIME
                : VisualsOptionLayout.get(c, VisualsOptionLayout.item(key))) == ColourPresets.ANIME) {
            c.cloudStyle = CloudMath.STYLE_ANIME;
        }
        c.colourPreset = ColourPresets.validated(c);
        if (c.revision < REVISION) c.revision = REVISION;
        return c;
    }

    public static int currentRevision() {
        return REVISION;
    }

    /** 2026-11-10 (session 8): the colour field behind c (0xRRGGBB). */
    public int colour(Colour c) {
        return switch (c) {
            case DAY_SKY -> this.colourDaySky;
            case SUNSET -> this.colourSunset;
            case SUNRISE -> this.colourSunrise;
            case SUN_GLOW -> this.colourSunGlow;
            case NIGHT_SKY -> this.colourNightSky;
            case STARS -> this.colourStars;
            case FOG -> this.colourFog;
            case OVERCAST -> this.colourOvercast;
            case CLOUDS -> this.colourClouds;
            case RAIN -> this.colourRain;
            case SNOW -> this.colourSnow;
            case SUNLIGHT -> this.colourSunlight;
            case SHADE_LIGHT -> this.colourShadeLight;
            case MOON -> this.colourMoon;
            case BLOCK_LIGHT -> this.colourBlockLight;
            case AURORA_MAIN -> this.colourAuroraMain;
            case AURORA_UPPER -> this.colourAuroraUpper;
            case AURORA_LOWER -> this.colourAuroraLower;
            case AURORA_END -> this.colourAuroraEnd;
            case WATER -> this.colourWater;
            case UNDERWATER -> this.colourUnderwater;
            case PARTICLE_SMOKE -> this.colourParticleSmoke;
            case PARTICLE_FLAMES -> this.colourParticleFlames;
            case PARTICLE_EMBERS -> this.colourParticleEmbers;
            case PARTICLE_DRIPS -> this.colourParticleDrips;
            case PARTICLE_MAGIC -> this.colourParticleMagic;
            case PARTICLE_AMBIENT -> this.colourParticleAmbient;
            case PARTICLE_COMBAT -> this.colourParticleCombat;
        } & 0xFFFFFF;
    }

    /** 2026-11-10 (session 8): sets the colour field behind c (the low 24 bits of rgb). */
    public void setColour(Colour c, int rgb) {
        int v = rgb & 0xFFFFFF;
        switch (c) {
            case DAY_SKY -> this.colourDaySky = v;
            case SUNSET -> this.colourSunset = v;
            case SUNRISE -> this.colourSunrise = v;
            case SUN_GLOW -> this.colourSunGlow = v;
            case NIGHT_SKY -> this.colourNightSky = v;
            case STARS -> this.colourStars = v;
            case FOG -> this.colourFog = v;
            case OVERCAST -> this.colourOvercast = v;
            case CLOUDS -> this.colourClouds = v;
            case RAIN -> this.colourRain = v;
            case SNOW -> this.colourSnow = v;
            case SUNLIGHT -> this.colourSunlight = v;
            case SHADE_LIGHT -> this.colourShadeLight = v;
            case MOON -> this.colourMoon = v;
            case BLOCK_LIGHT -> this.colourBlockLight = v;
            case AURORA_MAIN -> this.colourAuroraMain = v;
            case AURORA_UPPER -> this.colourAuroraUpper = v;
            case AURORA_LOWER -> this.colourAuroraLower = v;
            case AURORA_END -> this.colourAuroraEnd = v;
            case WATER -> this.colourWater = v;
            case UNDERWATER -> this.colourUnderwater = v;
            case PARTICLE_SMOKE -> this.colourParticleSmoke = v;
            case PARTICLE_FLAMES -> this.colourParticleFlames = v;
            case PARTICLE_EMBERS -> this.colourParticleEmbers = v;
            case PARTICLE_DRIPS -> this.colourParticleDrips = v;
            case PARTICLE_MAGIC -> this.colourParticleMagic = v;
            case PARTICLE_AMBIENT -> this.colourParticleAmbient = v;
            case PARTICLE_COMBAT -> this.colourParticleCombat = v;
        }
    }

    /** Session 13: the field a colour is saved in ("colour_particle_smoke" -> "colourParticleSmoke"). */
    static String colourField(Colour c) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : c.key().toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return b.toString();
    }

    /** 2026-11-10 (session 8): every colour in ShaderColours.Colour order. */
    public int[] colours() {
        int[] v = new int[ShaderColours.COUNT];
        for (Colour c : Colour.values()) v[c.ordinal()] = this.colour(c);
        return v;
    }

    public void save() {
        this.write(path());
    }

    /** 2026-10-30 (audit): through a temporary file moved over the old one, so a crash while saving cannot leave half a file (all defaults on the next load). */
    public void write(Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Visuals settings cannot be saved to {}: {}", file, e.toString());
        }
    }

    //Defaults (final fix R3): the settings the screen checks were tuned with. A percentage's 100 is still the package's full strength;
    // the look ships at these values
    /** 2026-11-10 (session 4): VisualsPresets' level (POTATO..ULTRA) or CUSTOM. The defaults below are the High level's values. */
    public int preset = VisualsPresets.DEFAULT;
    public boolean enabled = true;
    public int exposure = 100;
    public int toneStrength = 100;
    public int contrast = 100;
    public int saturation = 94;//2026-10-23: the user's default (was 107); saved settings keep their value
    public int gradeStrength = 0;
    public int bloomStrength = 45;//2026-10-25: the user's default (was 60; 40, 12 before 2026-10-22); saved settings keep their value
    public int vignetteStrength = 100;
    public int fogStrength = 100;
    /** Blocks of height fog scale: the fog hugs valleys and water. */
    public int fogHeight = 40;
    /** 2026-10-28: how thick the fog of rain and thunderstorms is (AtmosphereUniforms.weatherFog); 0 is the clear-weather fog. */
    public int weatherFog = 100;
    /** 2026-11-09 (session 1b): how thick the aerial haze over the distance is (its near veil and the far fade into the sky), percent. */
    public int distanceHaze = 100;
    public int sunGlow = 50;
    public int sunsetLight = 50;
    public int lightShafts = 50;
    public int moonlight = 50;
    public int aoStrength = 100;
    public int sunShading = 100;
    public int waterDepth = 100;
    public int waterReflections = 100;
    /** 2026-10-29: polished and metal blocks: 0 off, 1 the sun's and moon's highlights, 2 also their reflections (BlockReflectionMath). */
    public int blockReflections = 2;
    public int waterRipples = 100;
    public int waterRippleSpeed = 100;
    /** 2026-11-09 (session 2a): how close together the ripples are, percent (100 = the four octaves of water.glsl; 200 halves every wavelength). */
    public int waterRippleDensity = 100;
    /** 2026-11-09 (session 2b): how far one sees under water, percent (100 = underwater.glsl's extinction; 200 halves it). */
    public int underwaterVisibility = 100;
    /** 2026-10-21: how far plants and leaves sway in the wind (FoliageWind). */
    public int foliageWind = 100;
    public boolean farClouds = true;
    /** 2026-11-09 (session 3): the far clouds' style (CloudMath.STYLE_VANILLA: vanilla's cell shape, STYLE_REALISTIC: cumulus and cirrus; session 11: STYLE_ANIME, cel-shaded pillows), the realistic clouds' coverage (percent, 100 = default) and quality (0..2 low, medium, high). */
    public int cloudStyle = 0;
    public int cloudCoverage = 100;
    public int cloudQuality = 1;
    /** 2026-11-09 (after session 3b): the realistic clouds' size (percent, 100 = default; scales each cloud and the gaps between them) and count (percent, 100 = default, half the clumps the weather would grow; 200 keeps them all, as in 3b). */
    public int cloudSize = 100;
    public int cloudCount = 100;
    /**
     * 2026-11-09 (session 5): the sun's and moon's shape (CelestialUniforms.SHAPE_SQUARE vanilla's squares, SHAPE_ROUND), where auroras
     * appear (AURORA_OFF, AURORA_END_ONLY, AURORA_NIGHT_AND_END: clear nights in the Overworld, strongest in cold biomes, and always in the
     * End) and their strength (percent, 100 = default).
     */
    public int celestialShape = 0;
    /**
     * 2026-11-11 (session 10 follow-up): the round sun's and moon's size (percent, 100 = default: WaterMath.SUN_ROUND_RADIUS and
     * MOON_ROUND_RADIUS; CelestialUniforms.SIZE_MIN..SIZE_MAX). The square shape keeps vanilla's sizes.
     */
    public int sunSize = 100;
    public int moonSize = 100;
    public int aurora = 2;
    public int auroraStrength = 100;
    /**
     * 2026-11-11 (session 12): the night sky's stars (vanilla's, StarMath): how many (percent of vanilla's 1500 tries), their size and
     * their brightness (percent, 100 = vanilla's exactly).
     */
    public int starCount = 100;
    public int starSize = 100;
    public int starBrightness = 100;
    /** 2026-11-11 (session 13): the stars' shape: 0 vanilla's squares, 1 round soft points (StarMath.TYPE_ROUND). */
    public int starType = 0;
    /**
     * 2026-11-09 (session 6): rain and snow drawn by the shader (thin streaks and small flakes in depth layers, none under roofs) in place
     * of vanilla's weather columns; off draws vanilla's exactly as before.
     */
    public boolean shaderWeather = true;
    /**
     * 2026-11-11: the shader snow's and rain's look (ParticleEffect.SNOW, RAIN): the type (0 round, 1 pixel, 2 both), the size, the
     * amount and the brightness (percent, 100 = default; the amount replaced session 6's one density; the brightness multiplies the colour).
     */
    public int snowType = 0;
    public int snowSize = 100;
    public int snowAmount = 100;
    public int snowBrightness = 100;
    public int rainType = 0;
    public int rainSize = 100;
    public int rainAmount = 100;
    public int rainBrightness = 100;
    public boolean sunShadows = true;
    public boolean vanillaShadows = true;
    /** 2026-10-29: chunks around the camera the vanilla terrain casts sharp sun shadows in (ShadowMath.nearReach); 4 is the old 64 blocks. */
    public int shadowDistance = 4;
    /** 2026-10-29: mobs, players and items cast sun shadows (VkEntityShadows). */
    public boolean entityShadows = true;
    public int skyLightAmbient = 100;
    public boolean perPixelLight = true;
    public int lightColour = 70;
    /** 2026-10-28: the direct sunlight's colour temperature (ColourMath.sunLight): 100 is 4500 K, 0 the physical sky's white. */
    public int sunWarmth = 100;
    /** 2026-10-28: torch light's warm colour and its faster falloff (slot 31); 0 is vanilla's lightmap colour. */
    public int blockLightColour = 70;
    /** 2026-10-28: how much light sources' bright texels glow (lava, magma, glowstone...; slot 33 .x); 0 is vanilla's brightness. */
    public int blockGlow = 100;
    /** 2026-10-28: how much ores' specks glow (slot 33 .y); 2026-10-29 nether quartz ore only. 0 leaves it as any block. */
    public int oreGlow = 100;
    public boolean heldLight = true;
    /**
     * 2026-11-10 (shader roadmap session 9): LabPBR materials from the resource packs (PbrAtlas: their _n normal and _s specular maps;
     * without such a pack nothing changes): the normal maps' strength (percent, 100 = as the pack made them), smooth materials'
     * reflections, and the parallax (off: the textures stay flat).
     */
    public boolean pbr = true;
    public int pbrNormalStrength = 100;
    public boolean pbrReflections = true;
    public boolean pbrParallax = false;
    /** 0 off, 1..4 increasing quality: 2 is affordable on a mid-range GPU. */
    public int antiAliasing = 2;
    public boolean edgeCover = true;
    public boolean taa = true;
    public boolean dhLods = true;
    public boolean dhShadows = true;
    /** FG design 7: frame generation (camera reprojection), off by default; applied through a swapchain update (FrameGen.beginFrame). */
    public boolean frameGeneration = false;
    /** FSR design 7: AMD FSR 3.1 frame generation, off by default; exclusive with frameGeneration (FsrSettings). */
    public boolean fsrFrameGeneration = false;
    /** Presented frames per real frame; 2 in phases 1 and 2 (not on the page yet). */
    public int frameGenerationFactor = 2;
    /**
     * 2026-11-10 (session 8): the shader colours (ShaderColours.Colour, 0xRRGGBB display colours, saved as numbers); each default is the
     * colour drawn before the pickers, so a file without them looks as it did. Not performance settings: the presets leave them alone.
     */
    /** 2026-11-10 (session 8): the colour palette the colours are on (ColourPresets.DEFAULT..HORROR) or ColourPresets.CUSTOM. */
    public int colourPreset = ColourPresets.DEFAULT;
    public int colourDaySky = Colour.DAY_SKY.defaultRgb();
    public int colourSunset = Colour.SUNSET.defaultRgb();
    public int colourSunrise = Colour.SUNRISE.defaultRgb();
    public int colourSunGlow = Colour.SUN_GLOW.defaultRgb();
    public int colourNightSky = Colour.NIGHT_SKY.defaultRgb();
    /** 2026-11-11 (session 12): the stars' colour. */
    public int colourStars = Colour.STARS.defaultRgb();
    public int colourFog = Colour.FOG.defaultRgb();
    public int colourOvercast = Colour.OVERCAST.defaultRgb();
    public int colourClouds = Colour.CLOUDS.defaultRgb();
    public int colourRain = Colour.RAIN.defaultRgb();
    public int colourSnow = Colour.SNOW.defaultRgb();
    /** Session 8 follow-up: direct sunlight and the shade's sky light on terrain. */
    public int colourSunlight = Colour.SUNLIGHT.defaultRgb();
    public int colourShadeLight = Colour.SHADE_LIGHT.defaultRgb();
    public int colourMoon = Colour.MOON.defaultRgb();
    public int colourBlockLight = Colour.BLOCK_LIGHT.defaultRgb();
    public int colourAuroraMain = Colour.AURORA_MAIN.defaultRgb();
    public int colourAuroraUpper = Colour.AURORA_UPPER.defaultRgb();
    public int colourAuroraLower = Colour.AURORA_LOWER.defaultRgb();
    public int colourAuroraEnd = Colour.AURORA_END.defaultRgb();
    public int colourWater = Colour.WATER.defaultRgb();
    public int colourUnderwater = Colour.UNDERWATER.defaultRgb();
    //2026-11-11 (shader roadmap session 13): the particle look groups (VanillaParticles.Group, ParticleLook): type (0 vanilla, 1 realistic),
    // size, amount and brightness in percent (100 = vanilla), and each group's colour (ParticleEffect.PARTICLE_*)
    public int particleSmokeType = 0;
    public int particleSmokeSize = 100;
    public int particleSmokeAmount = 100;
    public int particleSmokeBrightness = 100;
    public int particleFlamesType = 0;
    public int particleFlamesSize = 100;
    public int particleFlamesAmount = 100;
    public int particleFlamesBrightness = 100;
    public int particleEmbersType = 0;
    public int particleEmbersSize = 100;
    public int particleEmbersAmount = 100;
    public int particleEmbersBrightness = 100;
    public int particleDripsType = 0;
    public int particleDripsSize = 100;
    public int particleDripsAmount = 100;
    public int particleDripsBrightness = 100;
    public int particleMagicType = 0;
    public int particleMagicSize = 100;
    public int particleMagicAmount = 100;
    public int particleMagicBrightness = 100;
    public int particleAmbientType = 0;
    public int particleAmbientSize = 100;
    public int particleAmbientAmount = 100;
    public int particleAmbientBrightness = 100;
    public int particleCombatType = 0;
    public int particleCombatSize = 100;
    public int particleCombatAmount = 100;
    public int particleCombatBrightness = 100;
    public int colourParticleSmoke = Colour.PARTICLE_SMOKE.defaultRgb();
    public int colourParticleFlames = Colour.PARTICLE_FLAMES.defaultRgb();
    public int colourParticleEmbers = Colour.PARTICLE_EMBERS.defaultRgb();
    public int colourParticleDrips = Colour.PARTICLE_DRIPS.defaultRgb();
    public int colourParticleMagic = Colour.PARTICLE_MAGIC.defaultRgb();
    public int colourParticleAmbient = Colour.PARTICLE_AMBIENT.defaultRgb();
    public int colourParticleCombat = Colour.PARTICLE_COMBAT.defaultRgb();
    public int revision = REVISION;
    public transient int lightDebug = 0;
    /** 2026-10-23 prototype (TerrainLightTarget): the terrain light for vanilla pixels; static, so it is neither saved nor an option (the pilot's set reaches it). */
    public static boolean terrainLight = true;
    /** FG design 7, pilot only: 0 normal, 1 warp check, 2 generated frames only, 3 holes and edges coloured. Static like terrainLight. */
    public static volatile int fgView = 0;
    /** Gate G1.2, pilot only: real frames go through the UI layer composite and nothing is generated (PauseRules "forced"). */
    public static boolean fgForcePause = false;
}
