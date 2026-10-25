package net.vulkanmod.mixin.visuals;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.vulkanmod.visuals.render.DeferredWeather;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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

    @Inject(method = "renderWorldBorder", at = @At("HEAD"), cancellable = true)
    private void visuals$deferBorder(Camera camera, CallbackInfo ci) {
        if (DeferredWeather.deferBorder(camera)) ci.cancel();
    }
}
