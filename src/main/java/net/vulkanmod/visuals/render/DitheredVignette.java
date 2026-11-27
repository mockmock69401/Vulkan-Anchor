package net.vulkanmod.visuals.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.InputStream;
import java.util.Optional;

/**
 * 2026-11-27: the texture Gui.renderVignette draws in place of vanilla's vignette.png while the visuals are on and the Output Dither
 * setting is not off (GuiFrameGenMixin): vanilla's own texture (a resource pack's too) baked by VignetteDither to one texel per pixel of
 * the GUI's full-screen quad. Baked again when that size changes or the vanilla texture is reloaded (its image is replaced). Render
 * thread only. A texture that cannot be read or baked leaves vanilla's draw in place (logged once).
 */
public final class DitheredVignette {
    private static final ResourceLocation VANILLA = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");
    private static final ResourceLocation BAKED = ResourceLocation.fromNamespaceAndPath("vulkanmod", "visuals/vignette_dithered");

    private static boolean baked, failed;
    private static int bakedWidth, bakedHeight;
    private static VulkanImage source;
    private static long sourceView;

    private DitheredVignette() {
    }

    /** The texture to draw the vignette with this frame, or null for vanilla's own. */
    public static ResourceLocation texture() {
        if (failed) return null;
        VisualsConfig config = VisualsConfig.get();
        if (!config.enabled || config.outputDither == PostMath.DITHER_OFF) return null;
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        //The GUI's quad spans the scaled screen times the scale: a pixel or two past the framebuffer when the scale does not divide it
        int scale = Math.max(1, (int) Math.round(window.getGuiScale()));
        int width = window.getGuiScaledWidth() * scale, height = window.getGuiScaledHeight() * scale;
        if (width < 1 || height < 1) return null;
        TextureManager textures = mc.getTextureManager();
        VulkanImage image = vanillaImage(textures);
        if (image == null) return null;
        if (!baked || width != bakedWidth || height != bakedHeight || image != source || image.getImageView() != sourceView) {
            try {
                bake(mc, textures, width, height);
            } catch (Exception | LinkageError e) {
                failed = true;
                VisualsLog.warn("Dithered vignette not made, vanilla's is drawn: " + e);
                return null;
            }
            source = image;
            sourceView = image.getImageView();
        }
        return BAKED;
    }

    private static VulkanImage vanillaImage(TextureManager textures) {
        AbstractTexture t = textures.getTexture(VANILLA);
        GlTexture gl = t == null ? null : GlTexture.getTexture(t.getId());
        return gl == null ? null : gl.getVulkanImage();
    }

    private static void bake(Minecraft mc, TextureManager textures, int width, int height) throws Exception {
        Optional<Resource> resource = mc.getResourceManager().getResource(VANILLA);
        if (resource.isEmpty()) throw new IllegalStateException(VANILLA + " missing");
        int[] src;
        int srcWidth, srcHeight;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            srcWidth = image.getWidth();
            srcHeight = image.getHeight();
            src = new int[srcWidth * srcHeight];
            for (int y = 0; y < srcHeight; y++) {
                for (int x = 0; x < srcWidth; x++) {
                    src[y * srcWidth + x] = image.getPixelRGBA(x, y);
                }
            }
        }
        int[] pixels = VignetteDither.bake(src, srcWidth, srcHeight, width, height);
        NativeImage out = new NativeImage(width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out.setPixelRGBA(x, y, pixels[y * width + x]);
                }
            }
        } catch (RuntimeException e) {
            out.close();
            throw e;
        }
        //A new texture each time (its size may change); the manager closes the one it replaces
        textures.register(BAKED, new DynamicTexture(out));
        baked = true;
        bakedWidth = width;
        bakedHeight = height;
    }
}
