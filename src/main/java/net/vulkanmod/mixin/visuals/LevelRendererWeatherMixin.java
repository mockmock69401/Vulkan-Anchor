package net.vulkanmod.mixin.visuals;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.vulkanmod.visuals.render.DeferredWeather;
import net.vulkanmod.visuals.render.ShaderWeather;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P7h final review: while the visuals draw the far clouds over sky pixels, vanilla's rain, snow and world border (drawn after the
 * clouds without depth) would be covered by them; they are drawn after the post-process instead (DeferredWeather).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWeatherMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void visuals$deferWeather(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
        if (DeferredWeather.deferRain(lightTexture, partialTick, camX, camY, camZ)) ci.cancel();
    }

    /**
     * 2026-11-10 (the user: "the rain bouncing off the grass still looks vanilla"): while the shader draws the weather (ShaderWeather), its
     * splashes on the ground replace vanilla's rain particles; tickRain's smoke over lava, magma and campfires and its rain sounds stay.
     */
    @Redirect(method = "tickRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void visuals$rainSplash(ClientLevel level, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
        if (particle == ParticleTypes.RAIN && ShaderWeather.replacesVanilla()) return;
        level.addParticle(particle, x, y, z, dx, dy, dz);
    }

    @Inject(method = "renderWorldBorder", at = @At("HEAD"), cancellable = true)
    private void visuals$deferBorder(Camera camera, CallbackInfo ci) {
        if (DeferredWeather.deferBorder(camera)) ci.cancel();
    }
}
