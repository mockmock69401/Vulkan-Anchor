package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.render.ShaderColours;
import net.vulkanmod.visuals.render.ShaderColours.Colour;

import java.util.function.ToIntFunction;

/**
 * 2026-11-10 (session 8, the user: "presets for the colour pickers too: a cartoon look like Genshin Impact's, a scary dark mood, realistic
 * colours, cinematic"): palettes for every ShaderColours colour at once, on the colours page. Like VisualsPresets for the performance
 * settings: choosing a palette sets every colour; changing any colour after that shows Custom, and putting them all back on a palette shows
 * it again. Since the session 8 follow-up a palette also sets three look settings ({@link #LOOK_KEYS}: bloom, saturation, fog strength),
 * changed and shown the same way, and the sunlight and shade light colours; never a performance setting (VisualsPresets owns those, and
 * the two key sets are disjoint).
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
 * The follow-up's light colours and look settings, from the same references: REALISTIC a near-white noon sun (about 5500 K in daylight
 * photographs) and blue skylight in the shade (shadows on snow and concrete photograph blue), less bloom than the default (a camera's
 * glare is subtle), the default saturation and a little more fog (aerial perspective). CINEMATIC an orange sun and teal shadows (the
 * grade's two poles), a wider glow (the films' halation), a slightly muted saturation and more haze. ANIME a warm bright sun and a
 * blue-violet shade (cel-shaded shadows are tinted, not grey), strong bloom, high saturation and clear air (less fog). HORROR a pale,
 * sickly daylight and a grey-green shade, little bloom, low saturation and the fog at twice the default.
 *
 * <p>Session 12 (2026-11-11, the user: the star colour in the colours section): every palette sets the stars' colour too, from the same
 * references. REALISTIC a faint blue-white (night-sky photographs: most naked-eye stars read white, the brightest ones blue-white; the
 * palette's near-white moon), CINEMATIC a teal-white (the grade's cool night, its moon's hue), ANIME a pale lavender (the blue-violet
 * night of cel-shaded games and Shinkai's starry skies), HORROR a dim grey-green (the almost black night: a darker colour dims the stars
 * to about a third, the sickly cast of its daylight). The star count, size and brightness are the player's: no palette sets them.
 */
public final class ColourPresets {
    public static final int DEFAULT = 0, REALISTIC = 1, CINEMATIC = 2, ANIME = 3, HORROR = 4, CUSTOM = 5;
    public static final int LEVELS = 5;

    /**
     * The palettes, one row per level (DEFAULT is ShaderColours' defaults), in ShaderColours.Colour order: day sky, sunset, sunrise, sun glow,
     * night sky, stars (session 12), fog, rainy sky, clouds, rain, snow, sunlight, shade light, moon, torch light, aurora body, aurora top, aurora lower edge,
     * End aurora, water, underwater; session 13's particle groups: smoke, flames, embers, drips, magic, ambient, combat (Realistic: real
     * smoke a little darker; Cinematic: warm smoke and fire, teal water; Anime: light clean smoke, bright fire and magic; Horror: dark smoke,
     * sickly fire, murky drips).
     */
    private static final int[][] PALETTES = {
            ShaderColours.defaults(),
            {0x6A94CC, 0xFF8A48, 0xF7B486, 0xFFE6C4, 0x1C2A46, 0xF2F5FF, 0xC2CBD4, 0xE6ECF2, 0xF2F2F2, 0xDCE6F0, 0xF7F9FF, 0xFFC88C, 0xE8F0FF, 0xEEEEEA, 0xFFA458,
                    0x48FF8C, 0xFF4A6A, 0xA868FF, 0xCB89FF, 0x4C9CBC, 0x4AA6AA,
                    0x6E6A66, 0xFFA848, 0xFFA020, 0x3F76E4, 0xB070F0, 0xA0A0A0, 0xE7E7E7},
            {0x5C98C0, 0xFF7A2E, 0xFF9C5A, 0xFFC48A, 0x183C4C, 0xD6F0FF, 0xF0B8A8, 0xB6C6CA, 0xFFEEDC, 0xC4DEE6, 0xEEF8FF, 0xFFB066, 0xD0F0F0, 0xD2EEFF, 0xFF8C3A,
                    0x3CF2E2, 0xFF5A86, 0x7E62FF, 0x9E74FF, 0x369AA6, 0x28868E,
                    0x9A8E84, 0xFF9030, 0xFF7020, 0x3C8CC0, 0x8C70FF, 0xA49A8E, 0xE8D8C8},
            {0x58A6F4, 0xFF9874, 0xFFC0AA, 0xFFF2CC, 0x2E3C7C, 0xE0E6FF, 0xC6DEF8, 0xDCE6FF, 0xFFFFFF, 0xD4EAFF, 0xFFFFFF, 0xFFD08E, 0xE0E4FF, 0xF4F6FF, 0xFFB46E,
                    0x4CFFD6, 0xFF74E4, 0xB482FF, 0xD494FF, 0x3CD2EA, 0x3CCAE2,
                    0xB4B8C8, 0xFFB850, 0xFFA040, 0x4CA8FF, 0xD080FF, 0xC0C4D8, 0xFFFFFF},
            {0x6C7876, 0xA23C2C, 0x8C7A5E, 0xC49482, 0x0E1316, 0x8E968A, 0x858F84, 0x6E766E, 0x9A9C98, 0x98A298, 0xC6CAC6, 0xD8D0B0, 0xB8C8C0, 0xD04A3A, 0xFF6A30,
                    0x2E9E44, 0x7E1020, 0x5E1E7C, 0x5E1E5E, 0x2E4E46, 0x283E36,
                    0x4A4C48, 0xC8A040, 0xD04A20, 0x4A6E5E, 0x6A3A6A, 0x5E625A, 0xB0A8A0},
    };

    /**
     * Session 8 follow-up (the user: "in each palette, bloom, saturation and fog amount too"): the look settings a palette also sets, by
     * VisualsOptionLayout key. Look only: none is a performance preset setting (VisualsPresets.VALUES; tested), so the two kinds of preset
     * never set the same setting. The distance haze, the rain's fog and the fog height stay the player's. 2026-11-12 (the user: "check
     * whether there is a night brightness setting, and have the colour presets include it"): the moonlight, the night's brightness.
     */
    public static final String[] LOOK_KEYS = {"bloom_strength", "saturation", "fog_strength", "moonlight", "exposure", "tone_strength", "contrast",
            "grade_strength"};
    /**
     * Each palette's LOOK_KEYS values, one row per level (DEFAULT is VisualsConfig's defaults; tested). 2026-11-11 (the user, after trying
     * them): Realistic's fog 110 -> 40, Anime 70 / 120 / 70 -> 35 / 110 / 80, Horror's bloom 20 -> 15. 2026-11-12, the moonlight: Default
     * the setting's default; Realistic a little darker (a real moonlit night); Cinematic brighter (film's day-for-night); Anime brighter
     * still (the bright blue-violet nights of cel-shaded games); Horror almost dark (its almost black night).
     *
     * <p>2026-11-13 (the user: "contrast, exposure, tone mapping and warm sunlight in the colour presets too"): the image page's tone block.
     * Default the settings' defaults (no warm/cool grade). Realistic a camera's neutral rendering with a light warm-sun, cool-shade split
     * (daylight photographs: sunlit areas warm, shade lit by the blue sky). Cinematic the teal-and-orange grade at its strongest, a little
     * more contrast and a touch darker (the films' low-key exposure). Anime brighter, a softer tone curve (cel shading keeps colours flat and
     * bright rather than rolling the highlights off) and a little warm/cool split. Horror darker with hard contrast (deep shadows) and no warm
     * grade (its sickly, cold daylight).
     */
    private static final int[][] LOOK = {
            {45, 94, 100, 50, 100, 100, 100, 0},
            {30, 94, 40, 40, 100, 100, 100, 20},
            {60, 85, 125, 60, 95, 100, 110, 70},
            {35, 110, 80, 70, 110, 70, 105, 25},
            {15, 60, 200, 15, 85, 100, 120, 0},
    };
    /** The moonlight's value before the palettes set it (VisualsConfig's default): an older file on a palette with it takes the palette's. */
    static final int MOONLIGHT_BEFORE_16 = 50;
    /** 2026-11-13: the tone block's values before the palettes set them (VisualsConfig's defaults), by key; see moveNewLooks. */
    static final java.util.Map<String, Integer> TONE_BEFORE_18 = java.util.Map.of("exposure", 100, "tone_strength", 100, "contrast", 100, "grade_strength", 0);
    /** The rows revisions 9 and 10 saved, without the moonlight (VisualsConfig.read moves a file still exactly on one of them to LOOK's row). */
    static final int[][] LOOK_BEFORE_11 = {
            {45, 94, 100},
            {30, 94, 110},
            {60, 85, 125},
            {70, 120, 70},
            {20, 60, 200},
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

    /** A palette's value for a LOOK_KEYS key (IllegalArgumentException for another key). */
    public static int look(String key, int level) {
        for (int i = 0; i < LOOK_KEYS.length; i++) if (LOOK_KEYS[i].equals(key)) return LOOK[level][i];
        throw new IllegalArgumentException(key);
    }

    /**
     * Session 11 (2026-11-10, the user: "anime-style clouds too, after Fantasy, linked with the Anime palette"): the cloud style is the one
     * performance setting a palette touches. ANIME picks the anime clouds (and shows only while they are on: changing the cloud style by
     * hand shows Custom); every other palette leaves the style alone, except that it takes the anime clouds back to the performance
     * preset's cloud style (the vanilla shape under Custom), so going Anime and back to another palette restores the sky it had. The
     * performance presets never fight this: they keep the anime clouds (VisualsPresets.keepsLook).
     */
    public static final String CLOUD_KEY = "cloud_style";

    /** The cloud style choosing palette level leaves, from the current style and performance preset. */
    public static int cloudStyle(int level, int style, int preset) {
        if (level == ANIME) return CloudMath.STYLE_ANIME;
        if (isLevel(level) && style == CloudMath.STYLE_ANIME) return VisualsPresets.isLevel(preset) ? VisualsPresets.value(CLOUD_KEY, preset) : CloudMath.STYLE_VANILLA;
        return style;
    }

    public static boolean isLookKey(String key) {
        for (String k : LOOK_KEYS) if (k.equals(key)) return true;
        return false;
    }

    /** Sets every colour and look setting to the palette's, and the cloud style (cloudStyle); CUSTOM (or anything else) changes nothing. Records the choice. */
    public static void apply(VisualsConfig c, int level) {
        if (isLevel(level)) {
            for (Colour colour : Colour.values()) c.setColour(colour, value(colour, level));
            for (String key : LOOK_KEYS) VisualsOptionLayout.set(c, VisualsOptionLayout.item(key), look(key, level));
            c.cloudStyle = cloudStyle(level, c.cloudStyle, c.preset);
        }
        c.colourPreset = isLevel(level) ? level : CUSTOM;
    }

    /**
     * The palette the colours and look settings are all on (the first, so DEFAULT before the others), or CUSTOM. looks gives a look
     * setting's value by key, and the cloud style's (CLOUD_KEY): ANIME also needs the anime clouds.
     */
    public static int detect(ToIntFunction<Colour> colours, ToIntFunction<String> looks) {
        for (int level = 0; level < LEVELS; level++) {
            boolean all = true;
            for (Colour c : Colour.values()) {
                if ((colours.applyAsInt(c) & 0xFFFFFF) != value(c, level)) {
                    all = false;
                    break;
                }
            }
            for (int i = 0; all && i < LOOK_KEYS.length; i++) all = looks.applyAsInt(LOOK_KEYS[i]) == LOOK[level][i];
            if (all && level == ANIME) all = looks.applyAsInt(CLOUD_KEY) == CloudMath.STYLE_ANIME;
            if (all) return level;
        }
        return CUSTOM;
    }

    public static int detect(VisualsConfig c) {
        return detect(c::colour, key -> VisualsOptionLayout.get(c, VisualsOptionLayout.item(key)));
    }

    /** The saved choice if its colours and looks are still all the palette's; else what they match now (a file edited by hand, an older file). */
    public static int validated(VisualsConfig c) {
        int now = detect(c);
        return isLevel(c.colourPreset) && now == c.colourPreset ? c.colourPreset : now;
    }

    /** Rows in Colour order, for the tests. */
    static int[] palette(int level) {
        return PALETTES[level].clone();
    }

    /**
     * 2026-11-11: a file saved at revision 9 or 10 on a palette (its colours all the palette's) whose look values are still exactly that
     * palette's old row takes the new row: the player chose the palette, not those numbers. Returns whether it did.
     */
    static boolean moveOldLook(VisualsConfig c) {
        int level = c.colourPreset;
        if (!isLevel(level)) return false;
        for (Colour colour : Colour.values()) if (c.colour(colour) != value(colour, level)) return false;
        int old = LOOK_BEFORE_11[level].length;
        for (int i = 0; i < old; i++) {
            if (VisualsOptionLayout.get(c, VisualsOptionLayout.item(LOOK_KEYS[i])) != LOOK_BEFORE_11[level][i]) return false;
        }
        for (int i = 0; i < old; i++) VisualsOptionLayout.set(c, VisualsOptionLayout.item(LOOK_KEYS[i]), LOOK[level][i]);
        return true;
    }

    /**
     * 2026-11-12: a file saved before the palettes set the moonlight, on a palette (its colours and other look values all the palette's)
     * with the moonlight still at its old default, takes the palette's moonlight and stays on it; a moonlight the player changed is kept.
     */
    static boolean moveOldMoonlight(VisualsConfig c) {
        return moveNewLooks(c, java.util.Map.of("moonlight", MOONLIGHT_BEFORE_16));
    }

    /**
     * A file saved before the palettes set some look settings (before: each such key's value back then, the setting's old default), on a
     * palette whose colours and other look settings are all the palette's, takes the palette's values for them and stays on it. Each of
     * those keys must still be at its old value (or already the palette's): one the player changed keeps the file as it is (it then shows
     * Custom). Used for the moonlight (revision 16) and the tone block (revision 18), together for a file older than both. Returns whether
     * it moved the file.
     */
    static boolean moveNewLooks(VisualsConfig c, java.util.Map<String, Integer> before) {
        int level = c.colourPreset;
        if (!isLevel(level) || before.isEmpty()) return false;
        for (Colour colour : Colour.values()) if (c.colour(colour) != value(colour, level)) return false;
        for (int i = 0; i < LOOK_KEYS.length; i++) {
            int v = VisualsOptionLayout.get(c, VisualsOptionLayout.item(LOOK_KEYS[i]));
            Integer old = before.get(LOOK_KEYS[i]);
            if (v != LOOK[level][i] && (old == null || v != old)) return false;
        }
        for (String key : before.keySet()) VisualsOptionLayout.set(c, VisualsOptionLayout.item(key), look(key, level));
        return true;
    }

    /**
     * Session 12: a file saved before the star colour (white stars, the default) on a palette whose every other colour is still the
     * palette's takes the palette's star colour, so it stays on the palette it chose. Returns whether it did.
     */
    static boolean moveOldStars(VisualsConfig c) {
        return moveOldColours(c, java.util.EnumSet.of(Colour.STARS));
    }

    /**
     * Sessions 12 and 13: a file saved before some colours existed (missing: their fields absent, so at their defaults) on a palette whose
     * every other colour is still the palette's takes the palette's values for them, so it stays on the palette it chose. Returns whether
     * it did.
     */
    static boolean moveOldColours(VisualsConfig c, java.util.Set<Colour> missing) {
        int level = c.colourPreset;
        if (!isLevel(level) || missing.isEmpty()) return false;
        for (Colour colour : missing) if (c.colour(colour) != colour.defaultRgb()) return false;
        for (Colour colour : Colour.values()) if (!missing.contains(colour) && c.colour(colour) != value(colour, level)) return false;
        for (Colour colour : missing) c.setColour(colour, value(colour, level));
        return true;
    }

    /** The look row in LOOK_KEYS order, for the tests. */
    static int[] looks(int level) {
        return LOOK[level].clone();
    }
}
