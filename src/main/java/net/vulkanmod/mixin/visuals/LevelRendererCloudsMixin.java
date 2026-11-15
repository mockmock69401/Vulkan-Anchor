package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.visuals.render.BiomeSky;
import net.vulkanmod.visuals.render.FarClouds;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P7h design 3.5: while the visuals draw the far clouds (the post-process ran last frame with the setting on and a ready map),
 * vanilla's 768-block cloud mesh is not drawn; otherwise vanilla's clouds stay.
 * <p>2026-11-15 (session 25): in a desert vanilla's clouds fade by the desert's clearing too (BiomeSky.cloudClear: the shader colour's
 * alpha while they draw, put back after), and are not drawn once it is all but complete.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCloudsMixin {
    @Unique
    private boolean visuals$faded;

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void visuals$farClouds(PoseStack poseStack, Matrix4f frustum, Matrix4f projection, float partialTick, double camX, double camY, double camZ,
                                   CallbackInfo ci) {
        this.visuals$faded = false;
        if (FarClouds.replacesVanilla()) {
            ci.cancel();
            return;
        }
        float clear = BiomeSky.cloudClear();
        if (clear >= 0.995f) {
            ci.cancel();
        } else if (clear > 0) {
            RenderSystem.setShaderColor(1, 1, 1, 1 - clear);
            this.visuals$faded = true;
        }
    }

    @Inject(method = "renderClouds", at = @At("RETURN"))
    private void visuals$cloudsDone(PoseStack poseStack, Matrix4f frustum, Matrix4f projection, float partialTick, double camX, double camY, double camZ,
                                    CallbackInfo ci) {
        if (this.visuals$faded) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            this.visuals$faded = false;
        }
    }
}
