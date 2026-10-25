package net.vulkanmod.mixin.visuals;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.vulkanmod.visuals.dh.DhWorldGenIsolation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-10-23: Distant Horizons' generation regions report no old chunks around, so vanilla's Blender does not scan whole regions on
 * the server's IO queue for them (DhWorldGenIsolation). The server's own generation is untouched.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionBlendingMixin {
    @Inject(method = "isOldChunkAround", at = @At("HEAD"), cancellable = true)
    private void visuals$noBlendingScanForLods(ChunkPos pos, int radius, CallbackInfoReturnable<Boolean> cir) {
        if (DhWorldGenIsolation.isDhRegion(this.getClass())) cir.setReturnValue(false);
    }
}
