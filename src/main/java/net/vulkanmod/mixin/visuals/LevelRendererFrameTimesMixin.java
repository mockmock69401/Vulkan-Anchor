package net.vulkanmod.mixin.visuals;

import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.visuals.dev.FrameRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stutter (2026-10-21): the client light engine's updates in the level render (a chunk arriving relights it on the render
 * thread), timed into FrameRecorder's light. Nothing happens unless the recorder is on.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrameTimesMixin {
    @Unique
    private long visuals$lightStart;

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private void visuals$lightStart(CallbackInfo ci) {
        if (FrameRecorder.ON) this.visuals$lightStart = System.nanoTime();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I", shift = At.Shift.AFTER))
    private void visuals$lightEnd(CallbackInfo ci) {
        if (FrameRecorder.ON) FrameRecorder.time(FrameRecorder.LIGHT, this.visuals$lightStart);
    }
}
