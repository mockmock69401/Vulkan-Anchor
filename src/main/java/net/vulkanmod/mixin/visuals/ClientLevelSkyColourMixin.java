package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.visuals.render.SkyColourMemo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-11-12 (session 14): getSkyColor once per set of inputs instead of three times a frame (fog colour, vanilla's sky, the
 * atmosphere); SkyColourMemo says why the result is the same. Vec3 is immutable, so the kept one is handed out as it is.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyColourMixin {
    @Unique
    private final SkyColourMemo visuals$skyMemo = new SkyColourMemo();

    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
    private void visuals$keptSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        if (!RenderSystem.isOnRenderThread()) return;
        ClientLevel self = (ClientLevel) (Object) this;
        Object kept = this.visuals$skyMemo.get(self, self.getGameTime(), self.getDayTime(), self.getSkyFlashTime(), pos.x, pos.y, pos.z, partialTick);
        if (kept != null) cir.setReturnValue((Vec3) kept);
    }

    @Inject(method = "getSkyColor", at = @At("RETURN"))
    private void visuals$keepSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        if (!RenderSystem.isOnRenderThread()) return;
        ClientLevel self = (ClientLevel) (Object) this;
        this.visuals$skyMemo.put(self, self.getGameTime(), self.getDayTime(), self.getSkyFlashTime(), pos.x, pos.y, pos.z, partialTick, cir.getReturnValue());
    }
}
