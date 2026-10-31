package net.vulkanmod.render.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: a font smaller than the default should sit in the middle of the line spacing): a TTF font whose letters are
 * shorter than vanilla's moves them so their middle is the line's (9 GUI pixels, vanilla's line height).
 */
class TtfCentringTest {
    /** The user's test font (medium_font.ttf, size 8): 'A' 2.28..6.22 and '가' 1.85..6.66 GUI pixels below the line's top. */
    @Test
    void aSmallFontMovesToTheLinesMiddle() {
        assertEquals(4.5f - (1.85f + 6.66f) / 2, TtfCentring.shift(1.85f, 6.66f), 1e-5f);
        assertTrue(TtfCentring.shift(1.85f, 6.66f) > 0, "a little down");
    }

    @Test
    void aFontAsTallAsVanillasStays() {
        assertEquals(0f, TtfCentring.shift(0f, 7f));
        assertEquals(0f, TtfCentring.shift(-1f, 8f));
    }

    @Test
    void aSmallFontHighUpMovesDown() {
        assertEquals(2.5f, TtfCentring.shift(0f, 4f), 1e-6f);
    }

    @Test
    void aSmallFontLowDownMovesUp() {
        assertEquals(-2f, TtfCentring.shift(4f, 9f), 1e-6f);
    }

    @Test
    void noReferenceLettersNoMove() {
        assertEquals(0f, TtfCentring.shift(Float.NaN, Float.NaN));
    }
}
