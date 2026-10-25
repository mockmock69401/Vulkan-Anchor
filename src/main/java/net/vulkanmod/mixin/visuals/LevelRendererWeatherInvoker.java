package net.vulkanmod.mixin.visuals;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's weather and world border, drawn by DeferredWeather after the visuals' post-process. */
@Mixin(LevelRenderer.class)
public interface LevelRendererWeatherInvoker {
    @Invoker("renderSnowAndRain")
    void visuals$renderSnowAndRain(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ);

    @Invoker("renderWorldBorder")
    void visuals$renderWorldBorder(Camera camera);
}
