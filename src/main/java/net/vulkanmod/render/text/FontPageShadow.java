package net.vulkanmod.render.text;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-30: the CPU copy of one font page's levels. Every upload into level 0 is copied in (honouring GL's unpack row length and
 * skips), and the levels below are rebuilt over the rectangle it touched; the caller uploads what write returns. Final review: the
 * copy lives in TILE x TILE tiles made where uploads land (whole 2048x2048 pages were 21 MiB each), and on RGBA pages a transparent
 * texel takes an opaque neighbour's colour, else white (black transparent texels darkened bilinear edges), so level 0 goes back up
 * when that changed it.
 */
final class FontPageShadow {
    /** A region of a level to upload: rect {x, y, w, h} in that level, its texels tightly packed. */
    record Upload(int level, int[] rect, byte[] pixels) {
    }

    static final int TILE = 64;

    private final int width, height, texel;
    private final byte[][][] tiles = new byte[FontMipmaps.LEVELS][][];

    FontPageShadow(int width, int height, int texelBytes) {
        this.width = width;
        this.height = height;
        this.texel = texelBytes;
        for (int l = 0; l < FontMipmaps.LEVELS; l++) {
            this.tiles[l] = new byte[tilesX(l) * tilesY(l)][];
        }
    }

    private int levelW(int l) {
        return Math.max(1, this.width >> l);
    }

    private int levelH(int l) {
        return Math.max(1, this.height >> l);
    }

    private int tilesX(int l) {
        return (levelW(l) + TILE - 1) / TILE;
    }

    private int tilesY(int l) {
        return (levelH(l) + TILE - 1) / TILE;
    }

    /** Bytes held by the tiles made so far. */
    long allocatedBytes() {
        long n = 0;
        for (byte[][] level : this.tiles) {
            for (byte[] t : level) {
                if (t != null) n += t.length;
            }
        }
        return n;
    }

    /** The tile holding (x, y) of level l, made (transparent: white with alpha 0 on RGBA pages) when create. */
    private byte[] tile(int l, int x, int y, boolean create) {
        int i = (y / TILE) * tilesX(l) + x / TILE;
        byte[] t = this.tiles[l][i];
        if (t == null && create) {
            t = new byte[TILE * TILE * this.texel];
            if (this.texel == 4) {
                for (int k = 0; k < t.length; k += 4) {
                    t[k] = t[k + 1] = t[k + 2] = (byte) 255;
                }
            }
            this.tiles[l][i] = t;
        }
        return t;
    }

    private int offset(int x, int y) {
        return ((y % TILE) * TILE + x % TILE) * this.texel;
    }

    private void read(int l, int x, int y, byte[] dst, int d) {
        byte[] t = tile(l, x, y, false);
        if (t == null) {
            for (int c = 0; c < this.texel; c++) dst[d + c] = (byte) (this.texel == 4 && c < 3 ? 255 : 0);
        } else {
            System.arraycopy(t, offset(x, y), dst, d, this.texel);
        }
    }

    private void write(int l, int x, int y, byte[] src, int s) {
        System.arraycopy(src, s, tile(l, x, y, true), offset(x, y), this.texel);
    }

    /** The first byte of the texel at (x, y) of a level (tests). */
    int texel(int level, int x, int y) {
        byte[] px = new byte[this.texel];
        read(level, x, y, px, 0);
        return px[0] & 255;
    }

    /** rect {x, y, w, h} of level l, tightly packed. */
    private byte[] region(int l, int[] r) {
        byte[] px = new byte[r[2] * r[3] * this.texel];
        for (int j = 0; j < r[3]; j++) {
            for (int i = 0; i < r[2]; i++) {
                read(l, r[0] + i, r[1] + j, px, (j * r[2] + i) * this.texel);
            }
        }
        return px;
    }

    /** Level-0 upload of w x h at (x, y) from src (from its position; rowLength 0 = w); the regions to upload after it. */
    List<Upload> write(int x, int y, int w, int h, int skipRows, int skipPixels, int rowLength, ByteBuffer src) {
        int rowLen = rowLength > 0 ? rowLength : w;
        int base = src.position();
        w = Math.min(w, this.width - x);
        h = Math.min(h, this.height - y);
        byte[] px = new byte[this.texel];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int s = base + ((skipRows + j) * rowLen + skipPixels + i) * this.texel;
                for (int c = 0; c < this.texel; c++) px[c] = src.get(s + c);
                write(0, x + i, y + j, px, 0);
            }
        }
        List<Upload> out = new ArrayList<>(FontMipmaps.LEVELS);
        int[] rect0 = {x, y, w, h};
        if (this.texel == 4 && fillTransparent(rect0)) {
            out.add(new Upload(0, rect0, region(0, rect0)));
        }
        for (int l = 1; l < FontMipmaps.LEVELS; l++) {
            int[] rect = FontMipmaps.levelRect(x, y, w, h, l, this.width, this.height);
            int[] up = {2 * rect[0], 2 * rect[1], 2 * rect[2], 2 * rect[3]};
            byte[] from = region(l - 1, up);
            byte[] to = new byte[rect[2] * rect[3] * this.texel];
            int[] all = {0, 0, rect[2], rect[3]};
            if (this.texel == 4) {
                FontMipmaps.downsampleRgba(from, up[2], to, rect[2], rect[3], all);
            } else {
                FontMipmaps.downsampleR8(from, up[2], to, rect[2], rect[3], all);
            }
            for (int j = 0; j < rect[3]; j++) {
                for (int i = 0; i < rect[2]; i++) {
                    write(l, rect[0] + i, rect[1] + j, to, (j * rect[2] + i) * this.texel);
                }
            }
            out.add(new Upload(l, rect, to));
        }
        return out;
    }

    /** RGBA level 0 over r: each alpha-0 texel takes the colour of an opaque 4-neighbour (as uploaded), else white; whether any changed. */
    private boolean fillTransparent(int[] r) {
        byte[] orig = region(0, r);
        byte[] nb = new byte[4];
        boolean changed = false;
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int j = 0; j < r[3]; j++) {
            for (int i = 0; i < r[2]; i++) {
                int o = (j * r[2] + i) * 4;
                if (orig[o + 3] != 0) continue;
                int cr = 255, cg = 255, cb = 255;
                for (int[] d : dirs) {
                    int nx = r[0] + i + d[0], ny = r[1] + j + d[1];
                    if (nx < 0 || ny < 0 || nx >= this.width || ny >= this.height) continue;
                    int li = i + d[0], lj = j + d[1];
                    if (li >= 0 && lj >= 0 && li < r[2] && lj < r[3]) {
                        System.arraycopy(orig, (lj * r[2] + li) * 4, nb, 0, 4);
                    } else {
                        read(0, nx, ny, nb, 0);
                    }
                    if (nb[3] != 0) {
                        cr = nb[0] & 255;
                        cg = nb[1] & 255;
                        cb = nb[2] & 255;
                        break;
                    }
                }
                if ((orig[o] & 255) != cr || (orig[o + 1] & 255) != cg || (orig[o + 2] & 255) != cb) {
                    write(0, r[0] + i, r[1] + j, new byte[]{(byte) cr, (byte) cg, (byte) cb, 0}, 0);
                    changed = true;
                }
            }
        }
        return changed;
    }
}
