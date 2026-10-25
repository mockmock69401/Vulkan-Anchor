package dev.vulkanfix.mixin.reforged;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reforged emulates glBindFramebuffer(GL_FRAMEBUFFER, 0) with Minecraft.getMainRenderTarget().bindWrite(true).
 * That is only "the window" while Minecraft's main render target is the MainTarget: Xaero's minimap and world map
 * swap it to their own ImprovedFramebuffer while drawing, so binding 0 re-bound Xaero's framebuffer and the final
 * map composite never reached the screen (empty minimap, black world map). Bind Reforged's real main pass, which is
 * exactly what MainTargetMixin.bindWrite does when nothing is swapped.
 */
@Mixin(value = GlFramebuffer.class, remap = false)
public abstract class GlFramebufferDefaultTargetMixin {
    @Redirect(
            method = "bindFramebuffer(II)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;m_83947_(Z)V"),
            remap = false,
            require = 1
    )
    private static void vulkanfix$bindRealMainPass(RenderTarget mainRenderTarget, boolean updateViewport) {
        Renderer.getInstance().getMainPass().rebindMainTarget();
    }
}
