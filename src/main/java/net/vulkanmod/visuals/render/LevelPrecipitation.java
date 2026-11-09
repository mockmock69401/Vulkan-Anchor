package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 2026-11-09 (session 6): the client level as PrecipitationMap reads it, by vanilla's own rules (LevelRenderer.renderSnowAndRain): the
 * column's MOTION_BLOCKING height (Level.getHeight: the first free block) from its loaded chunk, the biome at the camera's height
 * (vanilla reads it there), Biome.hasPrecipitation, and Biome.getPrecipitationAt for snow at and below y 80 and the snow line above it.
 * Render thread only.
 */
final class LevelPrecipitation implements PrecipitationMap.Columns {
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private LevelChunk chunk;
    private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;

    LevelPrecipitation(ClientLevel level) {
        this.level = level;
    }

    /** Forget the cached chunk (call once per frame: chunks load and unload between frames). */
    void newFrame() {
        this.chunk = null;
        this.chunkX = Integer.MIN_VALUE;
    }

    @Override
    public int height(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (cx != this.chunkX || cz != this.chunkZ) {
            this.chunkX = cx;
            this.chunkZ = cz;
            this.chunk = this.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        }
        return this.chunk == null ? PrecipitationMap.NO_HEIGHT : this.chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
    }

    /** 2026-11-10: the column's top MOTION_BLOCKING block holds water (MOTION_BLOCKING counts fluids). */
    @Override
    public boolean water(int x, int z) {
        int h = this.height(x, z);
        return h != PrecipitationMap.NO_HEIGHT && this.chunk.getFluidState(this.pos.set(x, h - 1, z)).is(FluidTags.WATER);
    }

    @Override
    public PrecipitationMap.Climate climate(int x, int y, int z) {
        Biome biome = this.level.getBiome(this.pos.set(x, y, z)).value();
        if (!biome.hasPrecipitation()) return PrecipitationMap.Climate.NONE;
        boolean low = biome.getPrecipitationAt(this.pos.set(x, WeatherMath.WEATHER_TEMPERATURE_Y, z)) == Biome.Precipitation.SNOW;
        int line = PrecipitationMap.snowLine(h -> biome.getPrecipitationAt(this.pos.set(x, h, z)) == Biome.Precipitation.SNOW);
        return new PrecipitationMap.Climate(true, low, line);
    }
}
