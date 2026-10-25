package net.vulkanmod.mixin.visuals;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.vulkanmod.visuals.render.LightUpdates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ruling R4: a section whose sky or block light changed is uploaded to the light volume again. */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheLightMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void visuals$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        LightUpdates.changed(pos.x(), pos.y(), pos.z());
    }
}
