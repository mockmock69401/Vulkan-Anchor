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
    private static float desert;
    private static float target;
    private static double lastSeconds = Double.NaN, sampledSeconds = Double.NEGATIVE_INFINITY;
    private static ClientLevel sampledLevel;
    private static final float[] SAMPLES = new float[BiomeSkyMath.KERNEL * BiomeSkyMath.KERNEL];
    /** -Dvulkanmod.visuals.biomeLog=true: the shares in the log every 2 s (the session's captures check the biome blend with it). */
    private static final boolean LOG = Boolean.getBoolean("vulkanmod.visuals.biomeLog");
    private static double loggedSeconds = Double.NEGATIVE_INFINITY;

    private BiomeSky() {
    }

    /** The desert share 0..1 this frame. */
    public static float desert() {
        return desert;
    }

    /** The night sky's tint this frame (exactly 1 outside deserts or with the setting at 0). */
    public static float[] nightTint() {
        VisualsConfig c = VisualsConfig.get();
        return BiomeSkyMath.nightTint(desert, c == null ? 0 : c.desertNight);
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
        boolean sky = level != null && level.dimensionType().hasSkyLight() && level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL;
        if (!sky) {
            desert = target = 0;
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
                SAMPLES[i] = classify(level.getBiome(pos));
            }
            target = BiomeSkyMath.kernel(SAMPLES);
            sampledSeconds = seconds;
            sampledLevel = level;
        }
        float dt = jump ? Float.NaN : (float) Math.min(1, seconds - lastSeconds);
        desert = jump ? target : BiomeSkyMath.follow(desert, target, dt);
        if (LOG && (seconds - loggedSeconds >= 2 || seconds < loggedSeconds)) {
            loggedSeconds = seconds;
            net.vulkanmod.visuals.VisualsLog.info(String.format(java.util.Locale.ROOT, "Visuals biome shares at %.0f %.0f: desert %.3f (target %.3f)",
                    x, z, desert, target));
        }
        lastSeconds = seconds;
    }

    /** The desert tags, made on first use (the class itself loads without the game's registries: the tests read its state). */
    private static final class Tags {
        static final TagKey<Biome> C_DESERT = TagKey.create(Registries.BIOME, new ResourceLocation("c", "is_desert"));
        static final TagKey<Biome> FORGE_DESERT = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_desert"));
    }

    private static float classify(Holder<Biome> biome) {
        Biome b = biome.value();
        return BiomeSkyMath.classify(biome.is(Tags.C_DESERT) || biome.is(Tags.FORGE_DESERT), biome.is(BiomeTags.IS_BADLANDS), biome.is(BiomeTags.IS_SAVANNA),
                b.hasPrecipitation(), b.getBaseTemperature());
    }
}
