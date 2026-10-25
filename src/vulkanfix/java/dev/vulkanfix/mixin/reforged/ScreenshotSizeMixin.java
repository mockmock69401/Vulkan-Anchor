package dev.vulkanfix.mixin.reforged;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.vulkanfix.compat.ScaledScreenshot;
import net.minecraft.client.Screenshot;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reforged's takeScreenshot allocates the NativeImage at the target's size but downloads the bound colour image
 * at its own size. Under render scale that image is the smaller scaled framebuffer, so F2 saved its rows re-strided
 * into streaks over a black band. Grab at the image's size there (the frame as rendered, before the upscale);
 * priority 1100 lands inside Reforged's @Overwrite (1000).
 */
@Mixin(value = Screenshot.class, priority = 1100, remap = false)
public abstract class ScreenshotSizeMixin {
    @Inject(
            method = "m_92279_(Lcom/mojang/blaze3d/pipeline/RenderTarget;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void vulkanfix$grabAtImageSize(RenderTarget target, CallbackInfoReturnable<NativeImage> cir) {
        int textureId = target.getColorTextureId();
        GlTexture texture = GlTexture.getTexture(textureId);
        VulkanImage image = texture != null ? texture.getVulkanImage() : null;
        if (image == null) {
            return;
        }
        int[] size = ScaledScreenshot.size(target.width, target.height, image.width, image.height);
        if (size == null) {
            return;
        }
        NativeImage nativeImage = new NativeImage(size[0], size[1], false);
        GlTexture.bindTexture(textureId);
        Renderer.getInstance().flushCmds();
        nativeImage.downloadTexture(0, true);
        cir.setReturnValue(nativeImage);
    }
}
