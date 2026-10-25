package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.visuals.render.TaaJitter;
import net.vulkanmod.visuals.vk.VkContext;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * W9: the TAA jitter on the level's projection. renderLevel builds the level projection (with the view bobbing) into one matrix,
 * hands it to resetProjectionMatrix and then the same object to LevelRenderer.renderLevel; it is offset in place here, so the
 * terrain, the sky, the LOD renderer and the visuals' frame all see the jittered projection. The offset is a fraction of a pixel of
 * the bound main framebuffer (render scale), the size VkTaa resolves at. The hand is drawn by
 * renderItemInHand, which builds its own projection and stays unjittered. Nothing changes while TaaJitter is inactive (TAA off,
 * post-process skipped: FrameFlags).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererJitterMixin {
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V"), index = 0)
    private Matrix4f visuals$jitter(Matrix4f projection) {
        if (!TaaJitter.active()) return TaaJitter.apply(projection, 0, 0);
        //Final fix R4: a pixel of the framebuffer the level is drawn into (render scale below 100 draws it smaller than the window)
        VkContext.MainTarget level = VkContext.captureMainTarget();
        RenderTarget window = Minecraft.getInstance().getMainRenderTarget();
        int[] size = TaaJitter.levelSize(level != null ? level.width() : 0, level != null ? level.height() : 0, window.width, window.height);
        return TaaJitter.apply(projection, size[0], size[1]);
    }
}
