package net.vulkanmod.config.gui.util;

/**
 * 2026-11-10 (session 8): the colour picker's arithmetic (no game classes, so it is tested alone): HSV (Smith 1978, "Color gamut transform
 * pairs", SIGGRAPH: hue 0..1 around the wheel, saturation and value 0..1) to and from 0xRRGGBB, and hex text.
 */
public final class ColourPickerMath {
    private ColourPickerMath() {
    }

    /** 0xRRGGBB for hue h (0..1, wrapping), saturation s and value v (0..1, clamped). */
    public static int hsvToRgb(float h, float s, float v) {
        float hh = h - (float) Math.floor(h);
        float ss = clamp01(s), vv = clamp01(v);
        float x = hh * 6;
        int sector = Math.min((int) x, 5);
        float f = x - sector;
        float p = vv * (1 - ss), q = vv * (1 - ss * f), t = vv * (1 - ss * (1 - f));
        float r, g, b;
        switch (sector) {
            case 0 -> { r = vv; g = t; b = p; }
            case 1 -> { r = q; g = vv; b = p; }
            case 2 -> { r = p; g = vv; b = t; }
            case 3 -> { r = p; g = q; b = vv; }
            case 4 -> { r = t; g = p; b = vv; }
            default -> { r = vv; g = p; b = q; }
        }
        return (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    /** {hue, saturation, value} of 0xRRGGBB; a grey keeps hue `keepHue` (and black also keeps saturation `keepSaturation`). */
    public static float[] rgbToHsv(int rgb, float keepHue, float keepSaturation) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float v = max;
        if (max <= 0) return new float[]{keepHue, keepSaturation, 0};
        float s = d / max;
        if (d <= 0) return new float[]{keepHue, s, v};
        float h;
        if (max == r) h = (g - b) / d;
        else if (max == g) h = 2 + (b - r) / d;
        else h = 4 + (r - g) / d;
        h /= 6;
        if (h < 0) h += 1;
        return new float[]{h, s, v};
    }

    /** 0xRRGGBB with one channel (0 red, 1 green, 2 blue) set to value (0..255, clamped). */
    public static int withChannel(int rgb, int channel, int value) {
        int shift = 16 - 8 * channel;
        int v = Math.max(0, Math.min(255, value));
        return (rgb & ~(0xFF << shift) & 0xFFFFFF) | (v << shift);
    }

    /** A channel of 0xRRGGBB (0 red, 1 green, 2 blue), 0..255. */
    public static int channel(int rgb, int channel) {
        return (rgb >> (16 - 8 * channel)) & 0xFF;
    }

    /** "RRGGBB", "#RRGGBB" or "RGB" (any case) as 0xRRGGBB; -1 when it is none of them. */
    public static int parseHex(String text) {
        if (text == null) return -1;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        if (s.length() != 6) return -1;
        for (int i = 0; i < 6; i++) if (Character.digit(s.charAt(i), 16) < 0) return -1;
        return Integer.parseInt(s, 16);
    }

    /** "RRGGBB" (no #). */
    public static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }

    private static int channel(float c) {
        return Math.max(0, Math.min(255, Math.round(c * 255)));
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
