package net.vulkanmod.mixin.chunk;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs after Distant Horizons' hook, so only on frames the guard did not cancel: draws and cancels.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class SectionLayerLateMixin {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void lateSectionLayer(RenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projectionMatrix, CallbackInfo ci) {
        WorldRenderer.getInstance().renderSectionLayer(renderType, camX, camY, camZ, modelView, projectionMatrix);
        ci.cancel();
    }
}
