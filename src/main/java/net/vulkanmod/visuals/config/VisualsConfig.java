package net.vulkanmod.visuals.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
    private static final int REVISION = 5;
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
        if (c.revision < REVISION) c.revision = REVISION;
        return c;
    }

    public static int currentRevision() {
        return REVISION;
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
    /** 2026-11-09 (session 3): the far clouds' style (CloudMath.STYLE_VANILLA: vanilla's cell shape, STYLE_REALISTIC: cumulus and cirrus), the realistic clouds' coverage (percent, 100 = default) and quality (0..2 low, medium, high). */
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
    public int aurora = 2;
    public int auroraStrength = 100;
    /**
     * 2026-11-09 (session 6): rain and snow drawn by the shader (thin streaks and small flakes in depth layers, none under roofs) in place
     * of vanilla's weather columns; off draws vanilla's exactly as before. The density (percent, 100 = default) scales how many drops fall.
     */
    public boolean shaderWeather = true;
    public int weatherDensity = 100;
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
    public int revision = REVISION;
    public transient int lightDebug = 0;
    /** 2026-10-23 prototype (TerrainLightTarget): the terrain light for vanilla pixels; static, so it is neither saved nor an option (the pilot's set reaches it). */
    public static boolean terrainLight = true;
    /** FG design 7, pilot only: 0 normal, 1 warp check, 2 generated frames only, 3 holes and edges coloured. Static like terrainLight. */
    public static volatile int fgView = 0;
    /** Gate G1.2, pilot only: real frames go through the UI layer composite and nothing is generated (PauseRules "forced"). */
    public static boolean fgForcePause = false;
}
