package dev.vulkanfix.mixin.reforged;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import dev.vulkanfix.compat.ReforgedDrawRelease;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reforged's drawWithShader/draw overwrites release the RenderedBuffer after recording the draw, where vanilla releases
 * it first. When the draw throws and the caller swallows the exception (FancyMenu's event bus does), the buffer is never
 * released. Track the draws in flight; RendererReleaseAbandonedMixin releases the ones that never returned.
 */
@Mixin(value = BufferUploader.class, priority = 1100, remap = false)
public abstract class BufferUploaderReleaseMixin {
    @Inject(method = {
            "m_231202_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V",
            "m_231209_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V"
    }, at = @At("HEAD"), remap = false)
    private static void vulkanfix$drawStarts(BufferBuilder.RenderedBuffer buffer, CallbackInfo ci) {
        if (RenderSystem.isOnRenderThread()) {
            ReforgedDrawRelease.DRAWS.enter(buffer);
        }
    }

    @Inject(method = {
            "m_231202_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V",
            "m_231209_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V"
    }, at = @At("RETURN"), remap = false)
    private static void vulkanfix$drawReturns(BufferBuilder.RenderedBuffer buffer, CallbackInfo ci) {
        if (RenderSystem.isOnRenderThread()) {
            ReforgedDrawRelease.DRAWS.exit(buffer);
        }
    }
}
