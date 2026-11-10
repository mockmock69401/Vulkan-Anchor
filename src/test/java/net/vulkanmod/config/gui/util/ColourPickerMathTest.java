package net.vulkanmod.config.gui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-10 (session 8): the colour picker's HSV, channel and hex arithmetic. */
class ColourPickerMathTest {
    @Test
    void hsvRoundTripsEveryEightBitColourItCanReach() {
        for (int rgb : new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0x7F96B8, 0xF2A66A, 0x2A3A58, 0x61FFCE, 0x123456, 0xFFFFFF, 0x808080}) {
            float[] hsv = ColourPickerMath.rgbToHsv(rgb, 0, 0);
            assertEquals(rgb, ColourPickerMath.hsvToRgb(hsv[0], hsv[1], hsv[2]), Integer.toHexString(rgb));
        }
        assertEquals(0xFF0000, ColourPickerMath.hsvToRgb(0, 1, 1));
        assertEquals(0xFF0000, ColourPickerMath.hsvToRgb(1, 1, 1), "the hue wraps");
        assertEquals(0x00FF00, ColourPickerMath.hsvToRgb(1 / 3f, 1, 1));
        assertEquals(0x000000, ColourPickerMath.hsvToRgb(0.5f, 1, 0));
        assertEquals(0xFFFFFF, ColourPickerMath.hsvToRgb(0.7f, 0, 1));
        assertEquals(0xFFFFFF, ColourPickerMath.hsvToRgb(0.7f, -1, 2), "clamped");
    }

    @Test
    void aGreyOrBlackKeepsTheHueItHad() {
        assertEquals(0.4f, ColourPickerMath.rgbToHsv(0x808080, 0.4f, 0.9f)[0], 0);
        float[] black = ColourPickerMath.rgbToHsv(0, 0.4f, 0.9f);
        assertArrayEquals(new float[]{0.4f, 0.9f, 0}, black, 0);
    }

    @Test
    void channelsAndHex() {
        assertEquals(0x12FF56, ColourPickerMath.withChannel(0x123456, 1, 255));
        assertEquals(0x123400, ColourPickerMath.withChannel(0x123456, 2, -5));
        assertEquals(0x34, ColourPickerMath.channel(0x123456, 1));
        assertEquals(0x123456, ColourPickerMath.parseHex("#123456"));
        assertEquals(0xABCDEF, ColourPickerMath.parseHex(" abcdef "));
        assertEquals(0xAABBCC, ColourPickerMath.parseHex("#abc"));
        assertEquals(-1, ColourPickerMath.parseHex("12345"));
        assertEquals(-1, ColourPickerMath.parseHex("12345G"));
        assertEquals(-1, ColourPickerMath.parseHex(null));
        assertEquals("0A0B0C", ColourPickerMath.hex(0x0A0B0C));
    }
}
