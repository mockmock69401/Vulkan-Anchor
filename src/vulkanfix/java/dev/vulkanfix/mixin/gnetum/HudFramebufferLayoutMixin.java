package dev.vulkanfix.mixin.gnetum;

import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.vulkanfix.compat.GlMemoryLayoutFramebuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gnetum caches the HUD in two framebuffers (made in reset()) and composites them with vanilla blitToScreen, whose UVs assume GL's
 * bottom-up rows, so they keep GL's memory layout; without it the whole HUD is drawn upside down.
 */
@Mixin(targets = "me.decce.gnetum.FramebufferManager", remap = false)
public abstract class HudFramebufferLayoutMixin {
    @Shadow(remap = false)
    private TextureTarget backFramebuffer;
    @Shadow(remap = false)
    private TextureTarget frontFramebuffer;

    @Inject(method = "reset", at = @At("RETURN"), remap = false)
    private void vulkanfix$useGlMemoryLayout(CallbackInfo ci) {
        if (backFramebuffer != null) {
            GlMemoryLayoutFramebuffers.register(backFramebuffer.frameBufferId);
        }
        if (frontFramebuffer != null) {
            GlMemoryLayoutFramebuffers.register(frontFramebuffer.frameBufferId);
        }
    }
}
