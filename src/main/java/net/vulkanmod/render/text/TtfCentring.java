package net.vulkanmod.render.text;

import it.unimi.dsi.fastutil.ints.IntSet;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FreeType;

/**
 * 2026-10-31 (the user: a font smaller than the default should sit in the middle of the line spacing): a TTF font's letters stand on
 * its baseline wherever its ascent puts it. A font whose reference letters are shorter than vanilla's ('A' fills 0..7 below the
 * line's top) moves its glyphs so their middle is the middle of the line (vanilla's 9 GUI pixels; the user chose the line's middle
 * over vanilla letters' middle, 3.5).
 */
public final class TtfCentring {
    /** Vanilla's letter height ('A'): shorter fonts are centred. */
    static final float VANILLA_LETTERS = 7f;
    /** Font.lineHeight. */
    static final float LINE_HEIGHT = 9f;
    /** A Latin capital and a Hangul syllable: what a line's letters fill. */
    private static final int[] REFERENCE = {'A', 0xAC00};
    /** TrueTypeGlyphProvider.getGlyph's FT_Load_Glyph flags. */
    private static final int LOAD_FLAGS = 4194312;

    private TtfCentring() {
    }

    /** GUI pixels to move the glyphs down (negative: up) for reference letters from top to bottom below the line's top. */
    static float shift(float top, float bottom) {
        if (Float.isNaN(top) || Float.isNaN(bottom) || bottom - top >= VANILLA_LETTERS) return 0f;
        return LINE_HEIGHT / 2f - (top + bottom) / 2f;
    }

    /**
     * The shift for a TrueTypeGlyphProvider (1.21.1: FreeType, sized and transformed by its constructor): face, its skipped codepoints
     * and oversample. A glyph's top is 7 - bitmap_top / oversample below the line's top (SheetGlyphInfo.getTop), its bitmap rows tall.
     */
    public static float shift(FT_Face face, IntSet skip, float oversample) {
        float top = Float.NaN, bottom = Float.NaN;
        for (int codepoint : REFERENCE) {
            if (skip.contains(codepoint)) continue;
            int glyph = FreeType.FT_Get_Char_Index(face, codepoint);
            if (glyph == 0 || FreeType.FT_Load_Glyph(face, glyph, LOAD_FLAGS) != 0) continue;
            FT_GlyphSlot slot = face.glyph();
            if (slot == null || slot.bitmap().rows() <= 0) continue;
            float t = 7f - slot.bitmap_top() / oversample, b = t + slot.bitmap().rows() / oversample;
            top = Float.isNaN(top) ? t : Math.min(top, t);
            bottom = Float.isNaN(bottom) ? b : Math.max(bottom, b);
        }
        return shift(top, bottom);
    }
}
