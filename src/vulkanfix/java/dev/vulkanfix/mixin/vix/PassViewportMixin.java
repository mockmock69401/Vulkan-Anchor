package dev.vulkanfix.mixin.vix;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import dev.vulkanfix.compat.GlMemoryLayoutFramebuffers;
import dev.vulkanfix.compat.ReforgedViewport;
import dev.vulkanfix.compat.ViewportTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VIX's passes set their viewport while the window is bound (right after unbinding the input) and bind the output without one,
 * where Reforged's top-down viewport stands. Inside VIX's RenderPost (PostEffectLayoutMixin) each draw puts back the last
 * viewport the mod asked for, now for the output framebuffer and so in GL's memory layout.
 */
@Mixin(value = BufferUploader.class, priority = 1100, remap = false)
public abstract class PassViewportMixin {
    @Inject(method = "m_231209_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V", at = @At("HEAD"), remap = false)
    private static void vulkanfix$passViewport(BufferBuilder.RenderedBuffer renderedBuffer, CallbackInfo ci) {
        if (!GlMemoryLayoutFramebuffers.inScope()) {
            return;
        }
        int[] viewport = ViewportTracker.last();
        if (viewport != null) {
            ReforgedViewport.apply(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }
}
