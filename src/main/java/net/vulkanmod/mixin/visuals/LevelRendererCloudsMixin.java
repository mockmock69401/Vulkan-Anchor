package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.visuals.render.FarClouds;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P7h design 3.5: while the visuals draw the far clouds (the post-process ran last frame with the setting on and a ready map),
 * vanilla's 768-block cloud mesh is not drawn; otherwise vanilla's clouds stay.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCloudsMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void visuals$farClouds(PoseStack poseStack, Matrix4f frustum, Matrix4f projection, float partialTick, double camX, double camY, double camZ,
                                   CallbackInfo ci) {
        if (FarClouds.replacesVanilla()) ci.cancel();
    }
}
