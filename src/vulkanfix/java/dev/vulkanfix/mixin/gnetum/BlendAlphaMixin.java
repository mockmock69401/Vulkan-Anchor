package dev.vulkanfix.mixin.gnetum;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.vulkanfix.compat.GnetumHud;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Gnetum draws the HUD into its framebuffer it makes every blend keep the destination's alpha (alpha factors ONE,
 * ONE_MINUS_SRC_ALPHA), so the cached HUD composites as drawn; it does so in GlStateManager._blendFunc/_blendFuncSeparate. VulkanMod's
 * RenderSystem.blendFunc/blendFuncSeparate set the Vulkan blend state here without passing through GlStateManager, so the cached HUD
 * kept vanilla's alpha and composited translucent (BMC4: washed-out hotbar and minimap). The same rewrite, at VulkanMod's entry points.
 */
@Mixin(value = VRenderSystem.class, remap = false)
public abstract class BlendAlphaMixin {
    private static final int ONE = 1, ONE_MINUS_SRC_ALPHA = 771;

    @Inject(method = "blendFunc(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$gnetumBlend(int src, int dst, CallbackInfo ci) {
        if (GnetumHud.rendering()) {
            VRenderSystem.blendFuncSeparate(src, dst, ONE, ONE_MINUS_SRC_ALPHA);
            ci.cancel();
        }
    }

    @Inject(method = "blendFunc(Lcom/mojang/blaze3d/platform/GlStateManager$SourceFactor;Lcom/mojang/blaze3d/platform/GlStateManager$DestFactor;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$gnetumBlendFactors(GlStateManager.SourceFactor src, GlStateManager.DestFactor dst, CallbackInfo ci) {
        if (GnetumHud.rendering()) {
            VRenderSystem.blendFuncSeparate(src.value, dst.value, ONE, ONE_MINUS_SRC_ALPHA);
            ci.cancel();
        }
    }

    @Inject(method = "blendFuncSeparate(IIII)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$gnetumBlendSeparate(int src, int dst, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if ((srcAlpha != ONE || dstAlpha != ONE_MINUS_SRC_ALPHA) && GnetumHud.rendering()) {
            VRenderSystem.blendFuncSeparate(src, dst, ONE, ONE_MINUS_SRC_ALPHA);
            ci.cancel();
        }
    }

    @Inject(method = "blendFuncSeparate(Lcom/mojang/blaze3d/platform/GlStateManager$SourceFactor;Lcom/mojang/blaze3d/platform/GlStateManager$DestFactor;Lcom/mojang/blaze3d/platform/GlStateManager$SourceFactor;Lcom/mojang/blaze3d/platform/GlStateManager$DestFactor;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$gnetumBlendSeparateFactors(GlStateManager.SourceFactor src, GlStateManager.DestFactor dst,
                                                            GlStateManager.SourceFactor srcAlpha, GlStateManager.DestFactor dstAlpha,
                                                            CallbackInfo ci) {
        if (GnetumHud.rendering()) {
            VRenderSystem.blendFuncSeparate(src.value, dst.value, ONE, ONE_MINUS_SRC_ALPHA);
            ci.cancel();
        }
    }
}
