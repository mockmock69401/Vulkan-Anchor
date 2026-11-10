package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.ShaderColours;
import net.vulkanmod.visuals.render.ShaderColours.Colour;

import java.util.function.ToIntFunction;

/**
 * 2026-11-10 (session 8, the user: "presets for the colour pickers too: a cartoon look like Genshin Impact's, a scary dark mood, realistic
 * colours, cinematic"): palettes for every ShaderColours colour at once, on the colours page. Like VisualsPresets for the performance
 * settings: choosing a palette sets every colour; changing any colour after that shows Custom, and putting them all back on a palette shows
 * it again. They only set colours (no tone, saturation or performance setting).
 *
 * <p>The references each palette was set from (what they show, not how they are made; the clean-room rule):
 * <ul>
 * <li>REALISTIC: photographs of clear and overcast skies (a deeper zenith blue than the default, sunsets orange-red low and lavender
 * above, a neutral grey overcast, moonlight close to white), coastal water photographs (blue-green, teal under water), and the auroras'
 * emission lines (oxygen 557.7 nm green, 630.0 nm red; Baranoski et al. 2003, which aurora.glsl follows).</li>
 * <li>CINEMATIC: the "teal and orange" grade of action films (Mad Max: Fury Road, 2015; Transformers, 2007): skies, shadows and water
 * pushed toward teal, the sun, sunsets and fire toward orange; Blade Runner 2049 (2017) for a warm haze. The fog's swatch is pinker than
 * the haze it gives: the fog colour multiplies the sky's light, and with the teal day sky an amber one (0xC9B79C, the first try) turned the
 * horizon olive in game; this one cancels the teal to a warm grey.</li>
 * <li>ANIME: bright cel-shaded open-world games and anime backgrounds (Genshin Impact, 2020, its published screenshots; Makoto Shinkai's
 * films' skies): a saturated azure sky, pure white clouds, coral-peach sunsets with pink, a blue-violet night, turquoise water.</li>
 * <li>HORROR: fog-bound horror (Silent Hill 2, 2024; The Witch, 2015; The Mist, 2007): a grey-green daylight and fog, clouds in grey, a
 * blood-red sunset and a red moon, an almost black night, dim sickly auroras, murky water.</li>
 * </ul>
 */
public final class ColourPresets {
    public static final int DEFAULT = 0, REALISTIC = 1, CINEMATIC = 2, ANIME = 3, HORROR = 4, CUSTOM = 5;
    public static final int LEVELS = 5;

    /**
     * The palettes, one row per level (DEFAULT is ShaderColours' defaults), in ShaderColours.Colour order: day sky, sunset, sunrise, sun glow,
     * night sky, fog, rainy sky, clouds, rain, snow, moon, torch light, aurora body, aurora top, aurora lower edge, End aurora, water,
     * underwater.
     */
    private static final int[][] PALETTES = {
            ShaderColours.defaults(),
            {0x6A94CC, 0xFF8A48, 0xF7B486, 0xFFE6C4, 0x1C2A46, 0xC2CBD4, 0xE6ECF2, 0xF2F2F2, 0xDCE6F0, 0xF7F9FF, 0xEEEEEA, 0xFFA458,
                    0x48FF8C, 0xFF4A6A, 0xA868FF, 0xCB89FF, 0x4C9CBC, 0x4AA6AA},
            {0x5C98C0, 0xFF7A2E, 0xFF9C5A, 0xFFC48A, 0x183C4C, 0xF0B8A8, 0xB6C6CA, 0xFFEEDC, 0xC4DEE6, 0xEEF8FF, 0xD2EEFF, 0xFF8C3A,
                    0x3CF2E2, 0xFF5A86, 0x7E62FF, 0x9E74FF, 0x369AA6, 0x28868E},
            {0x58A6F4, 0xFF9874, 0xFFC0AA, 0xFFF2CC, 0x2E3C7C, 0xC6DEF8, 0xDCE6FF, 0xFFFFFF, 0xD4EAFF, 0xFFFFFF, 0xF4F6FF, 0xFFB46E,
                    0x4CFFD6, 0xFF74E4, 0xB482FF, 0xD494FF, 0x3CD2EA, 0x3CCAE2},
            {0x6C7876, 0xA23C2C, 0x8C7A5E, 0xC49482, 0x0E1316, 0x858F84, 0x6E766E, 0x9A9C98, 0x98A298, 0xC6CAC6, 0xD04A3A, 0xFF6A30,
                    0x2E9E44, 0x7E1020, 0x5E1E7C, 0x5E1E5E, 0x2E4E46, 0x283E36},
    };

    private ColourPresets() {
    }

    public static boolean isLevel(int level) {
        return level >= DEFAULT && level < LEVELS;
    }

    /** A palette's colour (0xRRGGBB). */
    public static int value(Colour c, int level) {
        return PALETTES[level][c.ordinal()];
    }

    /** Sets every colour to the palette's; CUSTOM (or anything else) changes nothing. Records the choice. */
    public static void apply(VisualsConfig c, int level) {
        if (isLevel(level)) {
            for (Colour colour : Colour.values()) c.setColour(colour, value(colour, level));
        }
        c.colourPreset = isLevel(level) ? level : CUSTOM;
    }

    /** The palette the colours are all on (the first, so DEFAULT before the others), or CUSTOM. */
    public static int detect(ToIntFunction<Colour> colours) {
        for (int level = 0; level < LEVELS; level++) {
            boolean all = true;
            for (Colour c : Colour.values()) {
                if ((colours.applyAsInt(c) & 0xFFFFFF) != value(c, level)) {
                    all = false;
                    break;
                }
            }
            if (all) return level;
        }
        return CUSTOM;
    }

    /** The saved choice if its colours are still all the palette's; else what they match now (a file edited by hand, an older file). */
    public static int validated(VisualsConfig c) {
        if (isLevel(c.colourPreset) && detect(c::colour) == c.colourPreset) return c.colourPreset;
        return detect(c::colour);
    }

    /** Rows in Colour order, for the tests. */
    static int[] palette(int level) {
        return PALETTES[level].clone();
    }
}
