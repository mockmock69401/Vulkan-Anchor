package net.vulkanmod.render.text;

/**
 * 2026-10-30: the font pages' mip levels, built on the CPU from the level above. RGBA pages average alpha-weighted (premultiplied, then
 * divided back), so the transparent texels around a glyph do not darken its edge; a block with no alpha at all is transparent white.
 * R8 pages (TTF glyphs, read as intensity) average plainly. Pixels are row-major, tightly packed (stride = width * texel size).
 */
public final class FontMipmaps {
    /** Levels of a page: 256, 128, 64, 32 (deeper levels would mix neighbouring glyphs across the 1-texel gutter). */
    public static final int LEVELS = 4;

    private FontMipmaps() {
    }

    /** {x, y, w, h} at level covering every level-0 texel of the rectangle, clamped to the level's size (page w x h at level 0). */
    public static int[] levelRect(int x, int y, int w, int h, int level, int pageW, int pageH) {
        int lw = Math.max(1, pageW >> level), lh = Math.max(1, pageH >> level);
        int x0 = Math.min(x >> level, lw - 1), y0 = Math.min(y >> level, lh - 1);
        int x1 = Math.min((x + w + (1 << level) - 1) >> level, lw), y1 = Math.min((y + h + (1 << level) - 1) >> level, lh);
        return new int[]{x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)};
    }

    /** Fills rect (dst level coordinates) of dst (dstW x dstH) from the 2x2 blocks of src (one level up, srcW wide). */
    public static void downsampleRgba(byte[] src, int srcW, byte[] dst, int dstW, int dstH, int[] rect) {
        for (int y = rect[1]; y < Math.min(rect[1] + rect[3], dstH); y++) {
            for (int x = rect[0]; x < Math.min(rect[0] + rect[2], dstW); x++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int i = 0; i < 4; i++) {
                    int s = ((2 * y + (i >> 1)) * srcW + 2 * x + (i & 1)) * 4;
                    int ta = src[s + 3] & 255;
                    a += ta;
                    r += (src[s] & 255) * ta;
                    g += (src[s + 1] & 255) * ta;
                    b += (src[s + 2] & 255) * ta;
                }
                int d = (y * dstW + x) * 4;
                if (a == 0) {
                    dst[d] = dst[d + 1] = dst[d + 2] = (byte) 255;
                    dst[d + 3] = 0;
                } else {
                    dst[d] = (byte) ((r + a / 2) / a);
                    dst[d + 1] = (byte) ((g + a / 2) / a);
                    dst[d + 2] = (byte) ((b + a / 2) / a);
                    dst[d + 3] = (byte) ((a + 2) / 4);
                }
            }
        }
    }

    /** As downsampleRgba for single-channel pages: the rounded average of each 2x2 block. */
    public static void downsampleR8(byte[] src, int srcW, byte[] dst, int dstW, int dstH, int[] rect) {
        for (int y = rect[1]; y < Math.min(rect[1] + rect[3], dstH); y++) {
            for (int x = rect[0]; x < Math.min(rect[0] + rect[2], dstW); x++) {
                int s = 2 * y * srcW + 2 * x;
                int sum = (src[s] & 255) + (src[s + 1] & 255) + (src[s + srcW] & 255) + (src[s + srcW + 1] & 255);
                dst[y * dstW + x] = (byte) ((sum + 2) / 4);
            }
        }
    }
}
