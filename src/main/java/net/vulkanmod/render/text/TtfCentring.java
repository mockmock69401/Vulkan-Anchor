package net.vulkanmod.render.text;

import it.unimi.dsi.fastutil.ints.IntSet;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

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

    private TtfCentring() {
    }

    /** GUI pixels to move the glyphs down (negative: up) for reference letters from top to bottom below the line's top. */
    static float shift(float top, float bottom) {
        if (Float.isNaN(top) || Float.isNaN(bottom) || bottom - top >= VANILLA_LETTERS) return 0f;
        return LINE_HEIGHT / 2f - (top + bottom) / 2f;
    }

    /**
     * The shift for a TrueTypeGlyphProvider: font, its skipped codepoints, pointScale and ascent (oversampled pixels) and oversample
     * as it keeps them. Glyph tops are (ascent - y1 * pointScale) / oversample below the line's top (TrueTypeGlyphProvider.Glyph).
     */
    public static float shift(STBTTFontinfo font, IntSet skip, float pointScale, float ascent, float oversample) {
        float top = Float.NaN, bottom = Float.NaN;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x0 = stack.mallocInt(1), y0 = stack.mallocInt(1), x1 = stack.mallocInt(1), y1 = stack.mallocInt(1);
            for (int codepoint : REFERENCE) {
                if (skip.contains(codepoint)) continue;
                int glyph = STBTruetype.stbtt_FindGlyphIndex(font, codepoint);
                if (glyph == 0 || !STBTruetype.stbtt_GetGlyphBox(font, glyph, x0, y0, x1, y1)) continue;
                float t = (ascent - y1.get(0) * pointScale) / oversample, b = (ascent - y0.get(0) * pointScale) / oversample;
                top = Float.isNaN(top) ? t : Math.min(top, t);
                bottom = Float.isNaN(bottom) ? b : Math.max(bottom, b);
            }
        }
        return shift(top, bottom);
    }
}
