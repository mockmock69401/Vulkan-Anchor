package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.ReforgedDrawRelease;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every draw of the frame has returned by Reforged's endFrame: release the RenderedBuffers of those that threw. */
@Mixin(value = Renderer.class, remap = false)
public abstract class RendererReleaseAbandonedMixin {
    @Inject(method = "endFrame()V", at = @At("HEAD"), remap = false)
    private void vulkanfix$releaseAbandonedDraws(CallbackInfo ci) {
        ReforgedDrawRelease.releaseAbandoned();
    }
}
