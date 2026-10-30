package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.font.FontTexture;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.text.FontPages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 2026-10-30: font atlas pages are mipmapped images whose sampler follows the text anti-aliasing option (FontPages). */
@Mixin(FontTexture.class)
public class MFontTexture {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/TextureUtil;prepareImage(Lcom/mojang/blaze3d/platform/NativeImage$InternalGlFormat;III)V"))
    private void vulkanmod$fontPage(NativeImage.InternalGlFormat format, int id, int width, int height) {
        GlTexture.bindTexture(id);
        GlTexture.setVulkanImage(id, FontPages.create(width, height, format));
    }
}
