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
 * Draws the terrain layer before vanilla's (which has no sections to draw under Reforged; not cancelled, as upstream 1.21.1). The single drawer when Distant Horizons has no render API
 * (or is not installed); see SectionLayerGuardMixin for the other case.
 */
@Mixin(LevelRenderer.class)
public abstract class SectionLayerMixin {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void renderSectionLayer(RenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projectionMatrix, CallbackInfo ci) {
        WorldRenderer.getInstance().renderSectionLayer(renderType, camX, camY, camZ, modelView, projectionMatrix);
    }
}
