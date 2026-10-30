package net.vulkanmod.render.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-30: the font pages' mip levels (alpha-weighted for RGBA, a plain average for R8). */
class FontMipmapsTest {
    private static byte[] rgba(int w, int h, int r, int g, int b, int a) {
        byte[] p = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            p[i * 4] = (byte) r;
            p[i * 4 + 1] = (byte) g;
            p[i * 4 + 2] = (byte) b;
            p[i * 4 + 3] = (byte) a;
        }
        return p;
    }

    private static int[] texel(byte[] p, int w, int x, int y) {
        int i = (y * w + x) * 4;
        return new int[]{p[i] & 255, p[i + 1] & 255, p[i + 2] & 255, p[i + 3] & 255};
    }

    @Test
    void whiteGlyphEdgesStayWhite() {
        byte[] src = rgba(2, 2, 0, 0, 0, 0);
        src[0] = src[1] = src[2] = src[3] = (byte) 255;
        byte[] dst = new byte[4];
        FontMipmaps.downsampleRgba(src, 2, dst, 1, 1, new int[]{0, 0, 1, 1});
        assertArrayEquals(new int[]{255, 255, 255, 64}, texel(dst, 1, 0, 0));
    }

    @Test
    void flatRegionsAreExact() {
        byte[] src = rgba(4, 4, 10, 20, 30, 200);
        byte[] dst = new byte[2 * 2 * 4];
        FontMipmaps.downsampleRgba(src, 4, dst, 2, 2, new int[]{0, 0, 2, 2});
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertArrayEquals(new int[]{10, 20, 30, 200}, texel(dst, 2, x, y));
            }
        }
        byte[] r8 = new byte[16];
        java.util.Arrays.fill(r8, (byte) 77);
        byte[] r8dst = new byte[4];
        FontMipmaps.downsampleR8(r8, 4, r8dst, 2, 2, new int[]{0, 0, 2, 2});
        for (byte v : r8dst) assertEquals(77, v & 255);
    }

    @Test
    void emptyBlockIsTransparentWhite() {
        byte[] dst = new byte[4];
        FontMipmaps.downsampleRgba(new byte[16], 2, dst, 1, 1, new int[]{0, 0, 1, 1});
        assertArrayEquals(new int[]{255, 255, 255, 0}, texel(dst, 1, 0, 0));
    }

    @Test
    void r8Average() {
        byte[] dst = new byte[1];
        FontMipmaps.downsampleR8(new byte[]{0, (byte) 255, (byte) 255, (byte) 255}, 2, dst, 1, 1, new int[]{0, 0, 1, 1});
        assertEquals(191, dst[0] & 255);
    }

    @Test
    void levelRectCoversTheGlyph() {
        assertEquals(4, FontMipmaps.LEVELS);
        assertArrayEquals(new int[]{2, 3, 2, 2}, FontMipmaps.levelRect(5, 7, 3, 2, 1, 256, 256));
        assertArrayEquals(new int[]{31, 31, 1, 1}, FontMipmaps.levelRect(250, 250, 6, 6, 3, 256, 256));
    }

    @Test
    void onlyTheRectIsWritten() {
        byte[] src = rgba(4, 4, 255, 255, 255, 255);
        byte[] dst = rgba(2, 2, 1, 2, 3, 4);
        FontMipmaps.downsampleRgba(src, 4, dst, 2, 2, new int[]{1, 0, 1, 1});
        assertArrayEquals(new int[]{1, 2, 3, 4}, texel(dst, 2, 0, 0));
        assertArrayEquals(new int[]{255, 255, 255, 255}, texel(dst, 2, 1, 0));
        assertArrayEquals(new int[]{1, 2, 3, 4}, texel(dst, 2, 1, 1));
    }
}
