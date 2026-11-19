package net.vulkanmod.mixin.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@SuppressWarnings("target")
@Mixin(Screenshot.class)
public class ScreenshotRecorderM {

       
              
       
    @Overwrite(remap = false)
    public static NativeImage m_92279_(RenderTarget target) {
        int width = target.width;
        int height = target.height;

        NativeImage nativeimage = new NativeImage(width, height, false);
        GlTexture.bindTexture(target.getColorTextureId());

                                                                   
                                      
        Renderer.getInstance().flushCmds();

        //downloadTexture gives a drawn target GL's bottom-up rows (VulkanImage.requiresGlDownloadFlip); flipped back as vanilla does
        nativeimage.downloadTexture(0, true);
        VulkanImage source = VTextureSelector.getBoundTexture(0);
        if (source != null && source.requiresGlDownloadFlip(0)) {
            nativeimage.flipY();
        }
        return nativeimage;
    }
}

