package dev.vulkanfix.mixin.vix;

import dev.vulkanfix.compat.GlMemoryLayoutFramebuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VIX (Epic Fight Nightfall, Nightfall Enhance, Fancy Trail) runs its post effects as chains of passes copied from vanilla's
 * PostPass: a GL ortho projection and UVs taken from the position. Reforged draws vanilla's PostPass with Vulkan's native viewport;
 * VIX's passes got the top-down one, so each pass flipped the image and the bloom's eleven passes left the world upside down while
 * hitting mobs. RenderPost runs with every framebuffer in GL's memory layout (native viewport, swapped culling).
 */
@Mixin(targets = "com.guhao.vix.client.pipeline.PostEffectPipelines", remap = false)
public abstract class PostEffectLayoutMixin {
    @Inject(method = "RenderPost", at = @At("HEAD"), remap = false)
    private static void vulkanfix$enterGlLayout(CallbackInfo ci) {
        GlMemoryLayoutFramebuffers.enterScope();
    }

    @Inject(method = "RenderPost", at = @At("RETURN"), remap = false)
    private static void vulkanfix$exitGlLayout(CallbackInfo ci) {
        GlMemoryLayoutFramebuffers.exitScope();
    }
}
