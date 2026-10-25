package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.UnderwaterFog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 2026-10-21: under water, with the post-processing on, vanilla's water fog reaches further (UnderwaterFog). */
@Mixin(FogRenderer.class)
public abstract class FogRendererUnderwaterMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void visuals$underwaterFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog, float partialTick,
                                              CallbackInfo ci) {
        if (camera.getFluidInCamera() != FogType.WATER) return;
        RenderSystem.setShaderFogEnd(UnderwaterFog.end(RenderSystem.getShaderFogEnd(), true, VisualsConfig.get().enabled));
    }
}
