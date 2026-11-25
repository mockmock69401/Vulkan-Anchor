package net.vulkanmod.mixin.compatibility.worldgen;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.vulkanmod.compat.WorldGenChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-11-25 (the user: "상태 확인을 되살리는 호환기능을 vulkan anchor 쪽에 구현해줘"): Berezka's library (berezka_api 1.2.9.7) cancels
 * getChunk at its head and hands back a region chunk whatever status it has reached. The Twilight Forest reads a neighbour's biome
 * through the region while it builds a chunk, gets a chunk that has no biomes yet, and the game crashes ("Asking for biomes before we
 * have biomes", Zero to Engineering). This runs before that mixin (lower priority) and answers as vanilla does: a chunk that has not
 * reached the asked status is not there, so the caller works the biome out from the generator instead.
 */
@Mixin(value = WorldGenRegion.class, priority = 900)
public abstract class WorldGenRegionChunkStatusMixin {
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void compat$noChunkBeforeItsStatus(int x, int z, ChunkStatus status, boolean nonnull, CallbackInfoReturnable<ChunkAccess> cir) {
        WorldGenRegion self = (WorldGenRegion) (Object) this;
        if (nonnull || !self.hasChunk(x, z)) return;
        WorldGenRegionAccessor region = (WorldGenRegionAccessor) self;
        ChunkPos first = region.vulkanmod$firstPos();
        ChunkAccess chunk = region.vulkanmod$cache().get(WorldGenChunkStatus.index(x, z, first.x, first.z, region.vulkanmod$size()));
        if (chunk != null && WorldGenChunkStatus.missing(chunk.getStatus(), status)) cir.setReturnValue(null);
    }
}
