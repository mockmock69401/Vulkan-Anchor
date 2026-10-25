package net.vulkanmod.mixin.chunk;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.visuals.dh.DhPresence;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs before Distant Horizons' hook in the same method: draws and cancels unless DH is drawing.
 * While DH is active it does neither, so DH's hook and then SectionLayerLateMixin get the call.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class SectionLayerGuardMixin {

    @Inject(method = "m_172993_", at = @At("HEAD"), cancellable = true, remap = false)
    private void guardSectionLayer(RenderType renderType, PoseStack poseStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (!DhPresence.isActive()) {
            WorldRenderer.getInstance().renderSectionLayer(renderType, poseStack, camX, camY, camZ, projectionMatrix);
            ci.cancel();
        }
    }
}
