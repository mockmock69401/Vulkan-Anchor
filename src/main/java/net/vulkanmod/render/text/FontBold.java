package net.vulkanmod.render.text;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;

import java.util.List;
import java.util.function.Function;

/**
 * 2026-10-31 (the user: bold text is vanilla's second copy of each glyph one GUI pixel over): on an oversampled (TTF) font that pixel
 * is several texels, so thin strokes showed twice with a gap between. Such glyphs get a bold glyph of their own on the font's pages:
 * the coverage widened over a span to the right (BOLD_WEIGHT of the bold offset) and a little up and down, round at the ends, drawn
 * once. The advance stays vanilla's, so text keeps its width. Pixel fonts keep vanilla's two copies (the same image there).
 */
public final class FontBold {
    /** Of vanilla's bold offset (one GUI pixel): how much wider a bold glyph's strokes get. */
    static final float BOLD_WEIGHT = 0.5f;

    private FontBold() {
    }

    /** A FontSet's bold glyphs (FontSetM): codepoint's, or null where vanilla's two copies stay. */
    public interface Source {
        BakedGlyph vulkanmod$boldGlyph(int codepoint, float boldOffset);
    }

    /** Whether glyphs drawn at this oversample get a bold glyph of their own (above one texel per GUI pixel). */
    static boolean ownGlyph(float oversample) {
        return oversample > 1f;
    }

    /** Texels the strokes widen to the right. */
    static int spanX(float oversample, float boldOffset) {
        return Math.max(1, Math.round(BOLD_WEIGHT * boldOffset * oversample));
    }

    /** Texels they grow up and down (each). */
    static int spanY(int spanX) {
        return spanX / 4;
    }

    /**
     * src (w x h, texel bytes per texel: 1 coverage, 4 RGBA) widened kx to the right and ky up and down: (w + kx) x (h + 2ky), each
     * texel the most covering (most opaque) one under an ellipse over [0, kx] x [-ky, ky] behind it.
     */
    static byte[] widen(byte[] src, int w, int h, int texel, int kx, int ky) {
        int ow = w + kx, oh = h + 2 * ky;
        byte[] out = new byte[ow * oh * texel];
        float rx = kx / 2f + 0.5f, ry = ky + 0.5f;
        int alpha = texel == 4 ? 3 : 0;
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int best = -1, bestCover = -1;
                for (int dy = -ky; dy <= ky; dy++) {
                    int sy = y - ky - dy;
                    if (sy < 0 || sy >= h) continue;
                    for (int dx = 0; dx <= kx; dx++) {
                        int sx = x - dx;
                        if (sx < 0 || sx >= w) continue;
                        float ex = (dx - kx / 2f) / rx, ey = dy / ry;
                        if (ex * ex + ey * ey > 1f) continue;
                        int s = (sy * w + sx) * texel;
                        int cover = src[s + alpha] & 255;
                        if (cover > bestCover) {
                            bestCover = cover;
                            best = s;
                        }
                    }
                }
                if (best >= 0) System.arraycopy(src, best, out, (y * ow + x) * texel, texel);
            }
        }
        return out;
    }

    /**
     * The bold glyph of codepoint from the first provider that has it, stitched into the font's pages, or null where vanilla's two
     * copies stay (a pixel font, an empty glyph, a provider without it).
     */
    public static BakedGlyph bake(List<GlyphProvider> providers, int codepoint, Function<SheetGlyphInfo, BakedGlyph> stitch, float boldOffset) {
        for (GlyphProvider provider : providers) {
            GlyphInfo info = provider.getGlyph(codepoint);
            if (info == null) continue;
            BakedGlyph glyph = info.bake(sheet -> ownGlyph(sheet.getOversample()) ? stitch.apply(new Sheet(sheet, boldOffset)) : null);
            return glyph instanceof EmptyGlyph ? null : glyph;
        }
        return null;
    }

    /** The widened glyph: its level 0 is the plain glyph's, uploaded then widened on the page (FontPages.widen). */
    private static final class Sheet implements SheetGlyphInfo {
        private final SheetGlyphInfo plain;
        private final int kx, ky;

        Sheet(SheetGlyphInfo plain, float boldOffset) {
            this.plain = plain;
            this.kx = spanX(plain.getOversample(), boldOffset);
            this.ky = spanY(this.kx);
        }

        @Override
        public int getPixelWidth() {
            return this.plain.getPixelWidth() + this.kx;
        }

        @Override
        public int getPixelHeight() {
            return this.plain.getPixelHeight() + 2 * this.ky;
        }

        @Override
        public void upload(int x, int y) {
            this.plain.upload(x, y + this.ky);
            FontPages.widenBound(x, y + this.ky, this.plain.getPixelWidth(), this.plain.getPixelHeight(), this.kx, this.ky);
        }

        @Override
        public boolean isColored() {
            return this.plain.isColored();
        }

        @Override
        public float getOversample() {
            return this.plain.getOversample();
        }

        @Override
        public float getBearingX() {
            return this.plain.getBearingX();
        }

        @Override
        public float getBearingY() {
            return this.plain.getBearingY() - this.ky / this.plain.getOversample();
        }
    }
}
