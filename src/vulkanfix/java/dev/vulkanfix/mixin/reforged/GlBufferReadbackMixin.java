package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.ReforgedReadback;
import net.vulkanmod.gl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/** Fills a pixel pack buffer from a deferred glGetTexImage before anything reads it (see GlTextureReadbackMixin). */
@Mixin(value = GlBuffer.class, remap = false)
public abstract class GlBufferReadbackMixin {
    @Inject(method = "glMapBuffer(II)Ljava/nio/ByteBuffer;", at = @At("HEAD"), remap = false)
    private static void vulkanfix$resolveBeforeMap(int target, int access, CallbackInfoReturnable<ByteBuffer> cir) {
        ReforgedReadback.resolve(target);
    }

    @Inject(method = "glMapBufferRange(IJJI)Ljava/nio/ByteBuffer;", at = @At("HEAD"), remap = false)
    private static void vulkanfix$resolveBeforeMapRange(int target, long offset, long length, int access,
                                                        CallbackInfoReturnable<ByteBuffer> cir) {
        ReforgedReadback.resolve(target);
    }

    @Inject(method = "glGetBufferSubData(IJLjava/nio/ByteBuffer;)V", at = @At("HEAD"), remap = false)
    private static void vulkanfix$resolveBeforeGet(int target, long offset, ByteBuffer dest, CallbackInfo ci) {
        ReforgedReadback.resolve(target);
    }
}
