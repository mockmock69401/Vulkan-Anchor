package dev.vulkanfix.mixin.xaero.worldmap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vulkanfix.compat.GlMemoryLayoutFramebuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Xaero's world map framebuffers are sampled like CPU-uploaded textures, so they keep GL's memory layout. */
@Mixin(targets = "xaero.map.graphics.ImprovedFramebuffer", remap = false)
public abstract class WorldMapFramebufferLayoutMixin {
    @Inject(method = "m_83950_(IIZ)V", at = @At("RETURN"), remap = false)
    private void vulkanfix$useGlMemoryLayout(int width, int height, boolean isMac, CallbackInfo ci) {
        GlMemoryLayoutFramebuffers.register(((RenderTarget) (Object) this).frameBufferId);
    }
}
