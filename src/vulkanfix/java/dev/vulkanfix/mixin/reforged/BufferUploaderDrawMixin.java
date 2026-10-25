package dev.vulkanfix.mixin.reforged;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reforged's BufferUploader.draw (no shader) only records the draw, keeping the descriptor sets of the last
 * drawWithShader. In GL that draw samples whatever is bound now: Xaero's minimap and world map draw each tile batch
 * after GlStateManager._bindTexture, so every tile repeated the first batch's texture. Re-binding the bound pipeline's
 * descriptor sets picks up the texture bound since (unchanged textures keep their descriptor set).
 */
@Mixin(value = BufferUploader.class, priority = 1100, remap = false)
public abstract class BufferUploaderDrawMixin {
    @Inject(method = "m_231209_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V", at = @At("HEAD"), remap = false)
    private static void vulkanfix$rebindTextures(BufferBuilder.RenderedBuffer renderedBuffer, CallbackInfo ci) {
        Renderer renderer = Renderer.getInstance();
        Pipeline pipeline = renderer == null ? null : renderer.getBoundPipeline();
        if (pipeline != null) {
            renderer.uploadAndBindUBOs(pipeline);
        }
    }
}
