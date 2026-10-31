package net.vulkanmod.render.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: bold text is vanilla's second copy one pixel over): oversampled glyphs get a bold glyph of their own, the
 * glyph's coverage widened over the whole bold offset, drawn once; pixel fonts keep vanilla's two copies (identical there).
 */
class FontBoldTest {
    @Test
    void oversampledGlyphsWidenByHalfTheBoldOffset() {
        //TTF at oversample 8, vanilla's bold offset 1: half a GUI pixel wider, an eighth taller each side
        assertEquals(4, FontBold.spanX(8f, 1f));
        assertEquals(1, FontBold.spanY(4));
        assertEquals(2, FontBold.spanX(4f, 1f));
        assertEquals(0, FontBold.spanY(2));
    }

    @Test
    void pixelFontsKeepVanillasBold() {
        assertFalse(FontBold.ownGlyph(1f));
        assertFalse(FontBold.ownGlyph(0.5f));
        assertTrue(FontBold.ownGlyph(2f));
    }

    /** Vanilla's second copy one GUI pixel over (8 texels) left a gap between thin strokes; the bold glyph fills it. */
    @Test
    void theWideningLeavesNoGapInAStroke() {
        //one column of coverage 2 texels wide, 3 tall
        int w = 2, h = 3, kx = 4, ky = 0;
        byte[] src = new byte[w * h];
        for (int y = 0; y < h; y++) src[y * w] = src[y * w + 1] = (byte) 200;
        byte[] out = FontBold.widen(src, w, h, 1, kx, ky);
        int ow = w + kx;
        assertEquals(ow * h, out.length);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < ow; x++) {
                assertEquals(200, out[y * ow + x] & 255, "every texel of the widened stroke at " + x + "," + y);
            }
        }
    }

    @Test
    void theWideningIsRoundAtTheEnds() {
        //a single texel widened by 4 across and 1 up and down: the middle row is 5 wide, the rows above and below narrower
        byte[] out = FontBold.widen(new byte[]{(byte) 255}, 1, 1, 1, 4, 1);
        int ow = 5, oh = 3;
        assertEquals(ow * oh, out.length);
        int middle = 0, top = 0, bottom = 0;
        for (int x = 0; x < ow; x++) {
            middle += (out[ow + x] & 255) > 0 ? 1 : 0;
            top += (out[x] & 255) > 0 ? 1 : 0;
            bottom += (out[2 * ow + x] & 255) > 0 ? 1 : 0;
        }
        assertEquals(5, middle);
        assertTrue(top > 0 && top < 5, "top row " + top);
        assertEquals(top, bottom);
    }

    @Test
    void coverageIsTheMaximumNotASum() {
        byte[] src = {(byte) 100, (byte) 60};
        byte[] out = FontBold.widen(src, 2, 1, 1, 1, 0);
        assertArrayEquals(new byte[]{(byte) 100, (byte) 100, (byte) 60}, out);
    }

    @Test
    void colouredGlyphsTakeTheMostOpaqueTexel() {
        //RGBA: a red opaque texel and a blue half-transparent one
        byte[] src = {(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 128};
        byte[] out = FontBold.widen(src, 2, 1, 4, 1, 0);
        assertArrayEquals(new byte[]{(byte) 255, 0, 0, (byte) 255, (byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 128}, out);
    }

    @Test
    void theShadowGivesBackWhatWasUploaded() {
        FontPageShadow page = new FontPageShadow(256, 256, 1);
        byte[] src = new byte[3 * 2];
        for (int i = 0; i < src.length; i++) src[i] = (byte) (10 + i);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(src.length);
        buf.put(src).flip();
        page.write(70, 5, 3, 2, 0, 0, 0, buf);
        assertArrayEquals(src, page.read(70, 5, 3, 2));
    }
}
