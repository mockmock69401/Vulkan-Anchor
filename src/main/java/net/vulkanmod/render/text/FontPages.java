package net.vulkanmod.render.text;

import com.mojang.blaze3d.platform.NativeImage;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 2026-10-30: the font atlas pages (FontTexture, MFontTexture) and the text anti-aliasing option. Pages carry FontMipmaps.LEVELS
 * levels whatever the option; every upload into a page's level 0 (glyphs, and anything a mod writes while the page is bound) is
 * mirrored in its FontPageShadow and the lower levels are uploaded after it. The option only picks each page's sampler (the
 * text shaders read it as the TextAntialias uniform), so it switches instantly. Render thread.
 */
public final class FontPages {
    //Values never capture their key (a WeakHashMap value holding its key would keep the page forever)
    private static final Map<Object, BiConsumer<Object, Boolean>> SAMPLERS = new WeakHashMap<>();
    private static final BiConsumer<Object, Boolean> IMAGE_SAMPLER = (image, on) -> ((VulkanImage) image).setSampler(SamplerManager.getFontSampler(on));
    private static final Map<VulkanImage, FontPageShadow> SHADOWS = new WeakHashMap<>();
    private static Boolean state;

    private FontPages() {
    }

    private static boolean on() {
        if (state == null) {
            state = Initializer.CONFIG == null || Initializer.CONFIG.textAntialiasing;
        }
        return state;
    }

    /** The video option changed: every live page takes the matching sampler. */
    public static void applyOption(boolean on) {
        state = on;
        for (Map.Entry<Object, BiConsumer<Object, Boolean>> e : SAMPLERS.entrySet()) {
            e.getValue().accept(e.getKey(), on);
        }
    }

    /**
     * Whether key is a font page, whose sampler only the option sets: vanilla's text render types call setFilter(false, false) on
     * the font texture at every draw (RenderStateShard.TextureStateShard), which reset it to the nearest one (VulkanImage ignores
     * sampler updates for pages).
     */
    public static boolean manages(Object key) {
        return key != null && SAMPLERS.containsKey(key);
    }

    /** A page (key) whose sampler follows the option; set now to the current state. */
    static void register(Object key, Consumer<Boolean> samplerSetter) {
        register(key, (k, on) -> samplerSetter.accept(on));
    }

    private static void register(Object key, BiConsumer<Object, Boolean> samplerSetter) {
        SAMPLERS.put(key, samplerSetter);
        samplerSetter.accept(key, on());
    }

    /** A new font page: all levels, every level transparent, registered. */
    public static VulkanImage create(int width, int height, NativeImage.InternalGlFormat format) {
        VulkanImage image = new VulkanImage.Builder(width, height).setFormat(format).setMipLevels(FontMipmaps.LEVELS).createVulkanImage();
        int texel = format == NativeImage.InternalGlFormat.RED ? 1 : 4;
        for (int l = 0; l < FontMipmaps.LEVELS; l++) {
            int lw = Math.max(1, width >> l), lh = Math.max(1, height >> l);
            ByteBuffer zero = MemoryUtil.memCalloc(lw * lh * texel);
            try {
                image.uploadSubTextureAsync(l, lw, lh, 0, 0, 0, 0, 0, zero);
            } finally {
                MemoryUtil.memFree(zero);
            }
        }
        SHADOWS.put(image, new FontPageShadow(width, height, texel));
        register(image, IMAGE_SAMPLER);
        return image;
    }

    /** After an upload into image (VTextureSelector.uploadSubTexture): a font page's level 0 → its lower levels follow. */
    public static void afterUpload(VulkanImage image, int level, int w, int h, int x, int y, int skipRows, int skipPixels, int rowLength,
                                   ByteBuffer src) {
        if (level != 0 || image == null) return;
        FontPageShadow shadow = SHADOWS.get(image);
        if (shadow == null) return;
        List<FontPageShadow.Upload> ups = shadow.write(x, y, w, h, skipRows, skipPixels, rowLength, src);
        for (FontPageShadow.Upload up : ups) {
            ByteBuffer buf = MemoryUtil.memAlloc(up.pixels().length);
            try {
                buf.put(up.pixels()).flip();
                int[] r = up.rect();
                image.uploadSubTextureAsync(up.level(), r[2], r[3], r[0], r[1], 0, 0, 0, buf);
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }
}
