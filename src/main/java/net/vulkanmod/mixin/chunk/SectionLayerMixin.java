package net.vulkanmod.mixin.chunk;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the terrain layer and cancels vanilla's. The single drawer when Distant Horizons has no render API
 * (or is not installed); see SectionLayerGuardMixin for the other case.
 */
@Mixin(LevelRenderer.class)
public abstract class SectionLayerMixin {

    @Inject(method = "m_172993_", at = @At("HEAD"), cancellable = true, remap = false)
    private void renderSectionLayer(RenderType renderType, PoseStack poseStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo ci) {
        WorldRenderer.getInstance().renderSectionLayer(renderType, poseStack, camX, camY, camZ, projectionMatrix);
        ci.cancel();
    }
}
