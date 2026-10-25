package net.vulkanmod.mixin.visuals;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.vulkanmod.visuals.dh.DhWorldGenIsolation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-10-23: a real chunk asked for from Distant Horizons' generation steps is not handed to the server thread to load or generate
 * (DhWorldGenIsolation): the step gets an empty chunk, or null where the caller allows none. Every other caller is untouched (the
 * server thread is never one of Distant Horizons' world generation threads). No @Shadow: this build does not map shadowed fields.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheDhIsolationMixin {
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"),
            cancellable = true)
    private void visuals$noRealChunksForLods(int x, int z, ChunkStatus status, boolean require, CallbackInfoReturnable<ChunkAccess> cir) {
        if (!DhWorldGenIsolation.inDhGenerationStep()) return;
        cir.setReturnValue(require ? DhWorldGenIsolation.emptyChunk((ServerLevel) ((ServerChunkCache) (Object) this).getLevel(), x, z) : null);
    }
}
