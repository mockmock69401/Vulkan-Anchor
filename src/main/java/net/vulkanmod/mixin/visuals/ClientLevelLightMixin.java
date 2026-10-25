package net.vulkanmod.mixin.visuals;

import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.visuals.render.LightUpdates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ruling R4: a light packet for a loaded section reaches the client as a dirty mark; the light volume uploads that section again. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelLightMixin {
    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void visuals$sectionDirty(int x, int y, int z, CallbackInfo ci) {
        LightUpdates.changed(x, y, z);
    }
}
