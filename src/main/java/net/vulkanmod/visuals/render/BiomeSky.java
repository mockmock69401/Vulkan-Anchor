package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.vulkanmod.visuals.config.VisualsConfig;

/**
 * 2026-11-15 (shader roadmap session 25): the camera's desert share (BiomeSkyMath) this frame, read from the level at the start of
 * VisualsRenderer's frame (captureAtmosphere) and read back by AtmosphereUniforms (the night sky, the stars) and CloudUniforms (the
 * clouds). 0 outside a dimension with the Overworld's sky, without a level, and with the post-process off.
 */
public final class BiomeSky {
    private static float desert, snow, thunder;
    private static float target, snowTarget;
    private static double lastSeconds = Double.NaN, sampledSeconds = Double.NEGATIVE_INFINITY;
    private static ClientLevel sampledLevel;
    private static final float[] SAMPLES = new float[BiomeSkyMath.KERNEL * BiomeSkyMath.KERNEL];
    /** -Dvulkanmod.visuals.biomeLog=true: the shares in the log every 2 s (the session's captures check the biome blend with it). */
    private static final boolean LOG = Boolean.getBoolean("vulkanmod.visuals.biomeLog");
    private static double loggedSeconds = Double.NEGATIVE_INFINITY;
    private static final float[] SNOW_SAMPLES = new float[BiomeSkyMath.KERNEL * BiomeSkyMath.KERNEL];

    private BiomeSky() {
    }

    /** The desert share 0..1 this frame. */
    public static float desert() {
        return desert;
    }

    /** Session 25 (step 3): the snow share 0..1 this frame (columns where it snows at the camera's height, same kernel and follow). */
    public static float snow() {
        return snow;
    }

    /** Session 25 (step 3): the sun's cover by this frame's storm (StormMath.cover), at the thunder level update was given. */
    public static float cover() {
        return StormMath.cover(sand(thunder), blizzard(thunder));
    }

    /** Session 25 (step 3): the sandstorm's strength this frame for the thunder level (StormMath.strength; 0 off). */
    public static float sand(float thunder) {
        VisualsConfig c = VisualsConfig.get();
        return StormMath.strength(thunder, desert, c == null ? 0 : c.stormWeather);
    }

    /** Session 25 (step 3): the blizzard's strength this frame for the thunder level. */
    public static float blizzard(float thunder) {
        VisualsConfig c = VisualsConfig.get();
        return StormMath.strength(thunder, snow, c == null ? 0 : c.stormWeather);
    }

    /** The night sky's tint this frame on the palette's night colour base (exactly 1 outside deserts or with the setting at 0). */
    public static float[] nightTint(float[] base) {
        VisualsConfig c = VisualsConfig.get();
        return c == null ? new float[]{1, 1, 1} : BiomeSkyMath.nightTint(desert, c.desertNight, c.desertNightColour, base);
    }

    /** 2026-11-17: the desert Milky Way's share over the Vanilla and Round star types this frame (0 outside deserts). */
    public static float band() {
        VisualsConfig c = VisualsConfig.get();
        return c == null ? 0 : BiomeSkyMath.band(desert, c.desertNight, c.desertNightColour);
    }

    /** 2026-11-17: how far the Milky Way turns lavender this frame. */
    public static float lavender() {
        VisualsConfig c = VisualsConfig.get();
        return c == null ? 0 : BiomeSkyMath.lavender(desert, c.desertNight, c.desertNightColour);
    }

    /** 2026-11-17: the moon's glow factor this frame (1 outside deserts). */
    public static float moonGlow() {
        VisualsConfig c = VisualsConfig.get();
        return c == null ? 1 : BiomeSkyMath.moonGlow(desert, c.desertNight, c.desertNightColour);
    }

    /** The stars' weight factor this frame. */
    public static float starBoost() {
        VisualsConfig c = VisualsConfig.get();
        return BiomeSkyMath.starBoost(desert, c == null ? 0 : c.desertNight);
    }

    /** The far clouds' clearing this frame (0: all kept). */
    public static float cloudClear() {
        VisualsConfig c = VisualsConfig.get();
        return BiomeSkyMath.cloudClear(desert, c == null ? 0 : c.desertClouds);
    }

    /** Samples the grid around the camera (every RESAMPLE_SECONDS of flow time) and follows its mean; seconds is FlowClock's. */
    public static void update(ClientLevel level, double x, double y, double z, double seconds) {
        update(level, x, y, z, seconds, 0);
    }

    /** As update, with the frame's thunder level (cover()). */
    public static void update(ClientLevel level, double x, double y, double z, double seconds, float thunderLevel) {
        thunder = Float.isFinite(thunderLevel) ? thunderLevel : 0;
        boolean sky = level != null && level.dimensionType().hasSkyLight() && level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL;
        if (!sky) {
            desert = target = snow = snowTarget = 0;
            lastSeconds = Double.NaN;
            sampledLevel = null;
            return;
        }
        boolean jump = level != sampledLevel || Double.isNaN(lastSeconds);
        if (jump || seconds - sampledSeconds >= BiomeSkyMath.RESAMPLE_SECONDS || seconds < sampledSeconds) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            for (int i = 0; i < SAMPLES.length; i++) {
                pos.set(bx + BiomeSkyMath.offset(i, 0), by, bz + BiomeSkyMath.offset(i, 1));
                Holder<Biome> biome = level.getBiome(pos);
                SAMPLES[i] = classify(biome);
                SNOW_SAMPLES[i] = biome.value().hasPrecipitation() && biome.value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW ? 1 : 0;
            }
            target = BiomeSkyMath.kernel(SAMPLES);
            snowTarget = BiomeSkyMath.kernel(SNOW_SAMPLES);
            sampledSeconds = seconds;
            sampledLevel = level;
        }
        float dt = jump ? Float.NaN : (float) Math.min(1, seconds - lastSeconds);
        desert = jump ? target : BiomeSkyMath.follow(desert, target, dt);
        snow = jump ? snowTarget : BiomeSkyMath.follow(snow, snowTarget, dt);
        if (LOG && (seconds - loggedSeconds >= 2 || seconds < loggedSeconds)) {
            loggedSeconds = seconds;
            net.vulkanmod.visuals.VisualsLog.info(String.format(java.util.Locale.ROOT, "Visuals biome shares at %.0f %.0f: desert %.3f (target %.3f), snow %.3f (target %.3f)",
                    x, z, desert, target, snow, snowTarget) + "; footprints: " + Footprints.describe());
        }
        lastSeconds = seconds;
    }

    /** The desert tags, made on first use (the class itself loads without the game's registries: the tests read its state). */
    private static final class Tags {
        static final TagKey<Biome> C_DESERT = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", "is_desert"));
        static final TagKey<Biome> FORGE_DESERT = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("forge", "is_desert"));
        static final TagKey<Biome> C_BADLANDS = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", "is_badlands"));
        static final TagKey<Biome> FORGE_BADLANDS = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("forge", "is_badlands"));
    }

    /** Session 25 (step 3): whether a biome is a desert (the precipitation map's dust flag). */
    static boolean isDesert(Holder<Biome> biome) {
        return classify(biome) > 0.5f;
    }

    private static float classify(Holder<Biome> biome) {
        String id = biome.unwrapKey().map(k -> k.location().toString()).orElse(null);
        boolean tagged = biome.is(Tags.C_DESERT) || biome.is(Tags.FORGE_DESERT) || biome.is(BiomeTags.IS_BADLANDS) || biome.is(Tags.C_BADLANDS)
                || biome.is(Tags.FORGE_BADLANDS);
        return BiomeSkyMath.classify(id, tagged, biome.value().hasPrecipitation());
    }
}
