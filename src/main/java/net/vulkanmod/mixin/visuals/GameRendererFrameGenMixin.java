package net.vulkanmod.mixin.visuals;

import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.visuals.framegen.FrameGen;
import net.vulkanmod.visuals.fsr.FsrCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame generation's capture and the switch to the UI layer (FG design 4): right before the hand's depth clear in renderLevel, so the
 * world image holds everything drawn after the level too (the post-process, the deferred weather, Forge's AFTER_LEVEL overlays) and the
 * hand draws into the UI layer; at the end of renderLevel for a frame that draws no hand. FrameGen.beforeHand runs once per frame and
 * returns at once while frame generation is off. FSR's capture (FsrCapture.beforeHand: its HUD-less colour, depth and motion, FSR
 * design 4.5) runs at the same points, once per frame, and returns at once unless FSR is the active frame generation. require = 0 on
 * the clear: a mod that redirects that call leaves the RETURN hook, which then captures after the hand, instead of failing the game's
 * start.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFrameGenMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", ordinal = 0), require = 0)
    private void visuals$beforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        FrameGen.beforeHand();
        FsrCapture.beforeHand();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void visuals$afterLevelWithoutHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        FrameGen.beforeHand();
        FsrCapture.beforeHand();
    }
}
