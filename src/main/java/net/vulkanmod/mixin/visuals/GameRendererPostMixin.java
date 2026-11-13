package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.visuals.Visuals;
import net.vulkanmod.visuals.render.DeferredWeather;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The visuals' full-screen post-process (design 2026-10-10 P7b): right after the level renderer has drawn the world (terrain,
 * translucents, particles, weather) and before Forge's AFTER_LEVEL stage, the hand and the GUI. Rain, snow and the world border
 * deferred while the far clouds are drawn come right after (P7h final review), whether or not the post-process ran.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPostMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void visuals$afterLevel(float partialTick, long finishNanos, PoseStack poseStack, CallbackInfo ci) {
        try {
            Visuals.postProcess();
        } finally {
            DeferredWeather.replay();
            //2026-11-13 (session 13 follow-up): the realistic particles' images, over the finished picture
            net.vulkanmod.visuals.particle.DeferredParticles.replay();
        }
    }
}
