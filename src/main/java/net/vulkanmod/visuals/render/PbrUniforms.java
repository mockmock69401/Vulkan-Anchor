package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * 2026-11-10 (shader roadmap session 9, PBR): this frame's PBR state for the terrain's pipeline choice and the composite's slot 58
 * (pbr.glsl): x the wetness, y 1 with the PBR reflections on.
 * <p>
 * The wetness follows the rain where the camera is (session 6's rain level, where its biome rains rather than snows), eased in over
 * {@link PbrMath#WET_RISE_SECONDS} and dried over {@link PbrMath#WET_DRY_SECONDS} of real time (the ground stays wet a while after the
 * rain); each surface then takes it by the sky open above it (pbr.glsl's pbrWetAt).
 */
public final class PbrUniforms {
    /** The composite's slot (scene_copy.comp's pbrParams). */
    public static final int COPY_SLOT = 58;

    private static float wetness;
    private static long lastNanos;

    private PbrUniforms() {
    }

    /** Whether this frame's terrain draws its PBR build: the setting, the atlases with a map, and the material images it writes. */
    public static boolean terrainActive() {
        return PbrAtlas.wanted() && PbrAtlas.active() && TerrainLightTarget.materialOut() > 0;
    }

    /** Whether the PBR reflections may draw (the reflection pass runs for them too: LightingChoice.reflectionPass). */
    public static boolean reflections() {
        return PbrAtlas.wanted() && VisualsConfig.get().pbrReflections && PbrAtlas.active();
    }

    /** Once a frame: eases the wetness toward the rain at the camera. */
    public static void update(ClientLevel level, BlockPos camera, float partialTick) {
        long now = System.nanoTime();
        float seconds = lastNanos == 0 ? 0f : Math.min((now - lastNanos) / 1e9f, 1f);
        lastNanos = now;
        float rain = 0f;
        if (level != null && level.dimensionType().hasSkyLight()) {
            Biome biome = level.getBiome(camera).value();
            if (biome.getPrecipitationAt(camera) == Biome.Precipitation.RAIN) rain = level.getRainLevel(partialTick);
        }
        wetness = PbrMath.easeWetness(wetness, rain, seconds);
    }

    public static float wetness() {
        return wetness;
    }

    /** The pilot: sets the wetness at once (it then eases from there). */
    public static void setWetness(float w) {
        wetness = Math.max(0f, Math.min(1f, w));
    }

    public static void writeCopy(LooseUniformWriter w) {
        w.putVec4(COPY_SLOT, wetness, reflections() ? 1f : 0f, 0f, 0f);
    }
}
