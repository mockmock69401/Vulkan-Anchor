package net.vulkanmod.render.text;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-30: the CPU copy of one font page's levels. Every upload into level 0 is copied in (honouring GL's unpack row length and
 * skips), and the levels below are rebuilt over the rectangle it touched; the caller uploads what write returns.
 */
final class FontPageShadow {
    /** A region of a lower level to upload: rect {x, y, w, h} in that level, its texels tightly packed. */
    record Upload(int level, int[] rect, byte[] pixels) {
    }

    private final int width, height, texel;
    private final byte[][] levels = new byte[FontMipmaps.LEVELS][];

    FontPageShadow(int width, int height, int texelBytes) {
        this.width = width;
        this.height = height;
        this.texel = texelBytes;
        for (int l = 0; l < FontMipmaps.LEVELS; l++) {
            this.levels[l] = new byte[Math.max(1, width >> l) * Math.max(1, height >> l) * texelBytes];
        }
    }

    /** The first byte of the texel at (x, y) of a level (tests). */
    int texel(int level, int x, int y) {
        return this.levels[level][(y * Math.max(1, this.width >> level) + x) * this.texel] & 255;
    }

    /** Level-0 upload of w x h at (x, y) from src (from its position; rowLength 0 = w); the lower levels' regions to upload. */
    List<Upload> write(int x, int y, int w, int h, int skipRows, int skipPixels, int rowLength, ByteBuffer src) {
        int rowLen = rowLength > 0 ? rowLength : w;
        int base = src.position();
        byte[] l0 = this.levels[0];
        for (int j = 0; j < h && y + j < this.height; j++) {
            for (int i = 0; i < w && x + i < this.width; i++) {
                int s = base + ((skipRows + j) * rowLen + skipPixels + i) * this.texel;
                int d = ((y + j) * this.width + x + i) * this.texel;
                for (int c = 0; c < this.texel; c++) {
                    l0[d + c] = src.get(s + c);
                }
            }
        }
        List<Upload> out = new ArrayList<>(FontMipmaps.LEVELS - 1);
        for (int l = 1; l < FontMipmaps.LEVELS; l++) {
            int lw = Math.max(1, this.width >> l), lh = Math.max(1, this.height >> l), uw = Math.max(1, this.width >> (l - 1));
            int[] rect = FontMipmaps.levelRect(x, y, w, h, l, this.width, this.height);
            if (this.texel == 4) {
                FontMipmaps.downsampleRgba(this.levels[l - 1], uw, this.levels[l], lw, lh, rect);
            } else {
                FontMipmaps.downsampleR8(this.levels[l - 1], uw, this.levels[l], lw, lh, rect);
            }
            byte[] px = new byte[rect[2] * rect[3] * this.texel];
            for (int j = 0; j < rect[3]; j++) {
                System.arraycopy(this.levels[l], ((rect[1] + j) * lw + rect[0]) * this.texel, px, j * rect[2] * this.texel, rect[2] * this.texel);
            }
            out.add(new Upload(l, rect, px));
        }
        return out;
    }
}
