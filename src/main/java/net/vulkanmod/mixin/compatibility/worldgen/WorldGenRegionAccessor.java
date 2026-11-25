package net.vulkanmod.mixin.compatibility.worldgen;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The region's chunk grid, for WorldGenRegionChunkStatusMixin (accessors get their SRG names from the refmap; shadows here do not). */
@Mixin(WorldGenRegion.class)
public interface WorldGenRegionAccessor {
    @Accessor("cache")
    List<ChunkAccess> vulkanmod$cache();

    @Accessor("firstPos")
    ChunkPos vulkanmod$firstPos();

    @Accessor("size")
    int vulkanmod$size();
}
