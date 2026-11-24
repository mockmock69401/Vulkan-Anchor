package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CelestialUniforms;
import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.render.DimensionUniforms;
import net.vulkanmod.visuals.render.ShaderColours;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import net.vulkanmod.visuals.render.StarMath;

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
    /**
     * 2026-11-24 (the user: "기존 기본과 애니메이션, 공포 프리셋만 남기고 나머지 두 개의 프리셋은 지워줘"): Realistic and Cinematic are gone;
     * the palettes left are renumbered (revision 28). A file saved before keeps its palette by the old numbers (fromOldId), and a file on
     * Realistic or Cinematic takes Default (moveRetired).
     */
    public static final int DEFAULT = 0, ANIME = 1, HORROR = 2, CUSTOM = 3;
    public static final int LEVELS = 3;
    /** The ids revisions 8 to 27 saved: Default 0, Realistic 1, Cinematic 2, Anime 3, Horror 4, Custom 5. */
    static final int OLD_REALISTIC = 1, OLD_CINEMATIC = 2, OLD_ANIME = 3, OLD_HORROR = 4, OLD_CUSTOM = 5;

    /**
     * The palettes, one row per level (DEFAULT is ShaderColours' defaults), in ShaderColours.Colour order: day sky, sunset, sunrise, sun glow,
     * night sky, stars (session 12), fog, rainy sky, clouds, rain, snow, sunlight, shade light, moon, torch light, aurora body, aurora top, aurora lower edge,
     * End aurora, water, underwater; session 13's particle groups: smoke, flames, embers, drips, magic, ambient, combat (Realistic: real
     * smoke a little darker; Cinematic: warm smoke and fire, teal water; Anime: light clean smoke, bright fire and magic; Horror: dark smoke,
     * sickly fire, murky drips). 2026-11-17 (the user: "match the sky colour too", after Genshin Impact's night, looked at only: a deep,
     * saturated blue, lighter toward the horizon): Anime's night sky 0x3A6CFF (was ANIME_NIGHT_BEFORE_26, a dim grey-violet).
     */
    private static final int[][] PALETTES = {
            ShaderColours.defaults(),
            {0x58A6F4, 0xFF9874, 0xFFC0AA, 0xFFF2CC, 0x3A6CFF, 0xE0E6FF, 0xC6DEF8, 0xDCE6FF, 0xFFFFFF, 0xD4EAFF, 0xFFFFFF, 0xFFD08E, 0xE0E4FF, 0xF4F6FF, 0xFFB46E,
                    0x4CFFD6, 0xFF74E4, 0xB482FF, 0xD494FF, 0x3CD2EA, 0x3CCAE2,
                    0xB4B8C8, 0xFFB850, 0xFFA040, 0x4CA8FF, 0xD080FF, 0xC0C4D8, 0xFFFFFF},
            {0x6C7876, 0xA23C2C, 0x8C7A5E, 0xC49482, 0x0E1316, 0x8E968A, 0x858F84, 0x6E766E, 0x9A9C98, 0x98A298, 0xC6CAC6, 0xD8D0B0, 0xB8C8C0, 0xD04A3A, 0xFF6A30,
                    0x2E9E44, 0x7E1020, 0x5E1E7C, 0x5E1E5E, 0x2E4E46, 0x283E36,
                    0x4A4C48, 0xC8A040, 0xD04A20, 0x4A6E5E, 0x6A3A6A, 0x5E625A, 0xB0A8A0},
    };

    /** The Realistic and Cinematic palettes' colours (revisions 8 to 27), in Colour order: moveRetired recognises a file still on one. */
    private static final int[][] RETIRED = {
            {0x6A94CC, 0xFF8A48, 0xF7B486, 0xFFE6C4, 0x1C2A46, 0xF2F5FF, 0xC2CBD4, 0xE6ECF2, 0xF2F2F2, 0xDCE6F0, 0xF7F9FF, 0xFFC88C, 0xE8F0FF, 0xEEEEEA, 0xFFA458,
                    0x48FF8C, 0xFF4A6A, 0xA868FF, 0xCB89FF, 0x4C9CBC, 0x4AA6AA,
                    0x6E6A66, 0xFFA848, 0xFFA020, 0x3F76E4, 0xB070F0, 0xA0A0A0, 0xE7E7E7},
            {0x5C98C0, 0xFF7A2E, 0xFF9C5A, 0xFFC48A, 0x183C4C, 0xD6F0FF, 0xF0B8A8, 0xB6C6CA, 0xFFEEDC, 0xC4DEE6, 0xEEF8FF, 0xFFB066, 0xD0F0F0, 0xD2EEFF, 0xFF8C3A,
                    0x3CF2E2, 0xFF5A86, 0x7E62FF, 0x9E74FF, 0x369AA6, 0x28868E,
                    0x9A8E84, 0xFF9030, 0xFF7020, 0x3C8CC0, 0x8C70FF, 0xA49A8E, 0xE8D8C8},
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
     *
     * <p>2026-11-14 (the user: "the Anime preset looks a little overdone: saturation 105%, fog 30%"; "in the other presets bloom and fog
     * are too high too: bring both down to about 30-40%"): Anime 110 / 80 -> 105 / 30; Default's bloom and fog 45 / 100 -> 35 / 35
     * (VisualsConfig's defaults with it), Cinematic's 60 / 125 -> 40 / 40, Horror's fog 200 -> 40 (its bloom, 15, was already lower);
     * Realistic's 30 / 40 were already in range.
     */
    private static final int[][] LOOK = {
            {35, 94, 35, 50, 100, 100, 100, 0},
            {35, 105, 30, 57, 110, 70, 105, 25},
            {15, 60, 40, 15, 85, 100, 120, 0},
    };
    /**
     * The rows revisions 21 to 26 saved: Anime's moonlight was 70 (2026-11-23, the user: "달빛은 57%로 낮추기"; VisualsConfig.read moves a
     * file still on the old row to LOOK's; moveOldRows).
     */
    static final int[][] LOOK_BEFORE_27 = {
            {35, 94, 35, 50, 100, 100, 100, 0},
            {35, 105, 30, 70, 110, 70, 105, 25},
            {15, 60, 40, 15, 85, 100, 120, 0},
    };
    /** The rows revisions 18 to 20 saved (VisualsConfig.read moves a file still on one of them to LOOK's row; moveOldRows). */
    static final int[][] LOOK_BEFORE_21 = {
            {45, 94, 100, 50, 100, 100, 100, 0},
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
     * performance setting a palette touches. ANIME picks the anime clouds.
     *
     * <p>2026-11-16 (the user: "only the Anime preset should change the clouds; choosing another preset resets the cloud choice", then
     * "choosing the Realistic or Cinematic preset should pick the realistic clouds"): REALISTIC and CINEMATIC pick the realistic clouds,
     * ANIME the anime ones (clouds), and each shows only while its clouds are on (changing the cloud style by hand shows Custom). DEFAULT
     * and HORROR leave the style as the player set it, the anime clouds too: they took the anime clouds back to the performance preset's
     * style, the vanilla shape at Potato to High and under Custom, which wiped the player's choice. The performance presets keep a
     * palette's clouds (VisualsPresets.keepsLook).
     */
    public static final String CLOUD_KEY = "cloud_style";
    /** clouds' answer for a palette that leaves the cloud style alone. */
    public static final int ANY_CLOUDS = -1;

    /**
     * The cloud style palette level picks (CloudMath.STYLE_REALISTIC for REALISTIC, CINEMATIC and ANIME), or ANY_CLOUDS. 2026-11-23 (the
     * user: "애니메이션 색상 프리셋에서 현실적 구름 사용 (애니메이션 구름은 차후 개선 예정)"): ANIME picks the realistic clouds too, until
     * the anime clouds are reworked (it picked STYLE_ANIME from revision 10 to 26).
     */
    public static int clouds(int level) {
        return switch (level) {
            case ANIME -> CloudMath.STYLE_REALISTIC;
            default -> ANY_CLOUDS;
        };
    }

    /** The cloud style choosing palette level leaves, from the current style (clouds; DEFAULT, HORROR and CUSTOM keep it). */
    public static int cloudStyle(int level, int style) {
        int own = clouds(level);
        return own == ANY_CLOUDS ? style : own;
    }

    /**
     * 2026-11-17 (shader roadmap session 12 follow-up, the user approved: "Yes, link them"): the star type, linked as the clouds are. ANIME
     * picks the anime stars (StarMath.TYPE_ANIME, the painted night with the teal Milky Way) and shows only while they are on (changing the
     * star type by hand shows Custom); every other palette leaves the star type as the player set it, the anime stars too. The star type is
     * not a performance setting, so the performance presets never change it (VisualsPresets.VALUES has no star_type).
     */
    public static final String STAR_KEY = "star_type";
    /** stars' answer for a palette that leaves the star type alone. */
    public static final int ANY_STARS = -1;

    /** The star type palette level picks (StarMath.TYPE_ANIME for ANIME), or ANY_STARS. */
    public static int stars(int level) {
        return level == ANIME ? StarMath.TYPE_ANIME : ANY_STARS;
    }

    /** The star type choosing palette level leaves, from the current type (stars; every palette but ANIME keeps it). */
    public static int starType(int level, int type) {
        int own = stars(level);
        return own == ANY_STARS ? type : own;
    }

    /**
     * 2026-11-23 (the user: "애니메이션 색상 프리셋은 1,2번 사진을 적용하고, 나머지 기본/현실적/시네마틱은 우주느낌의 3,4번 사진을 참고해"): the
     * End's sky, linked as the clouds are. ANIME picks the painted sky (DimensionUniforms.END_SKY_ANIME), DEFAULT the space one
     * (END_SKY_SPACE; 2026-11-24: Realistic and Cinematic, which had it too, are gone), each shown only while it is on (changing the End
     * sky by hand shows Custom); HORROR leaves it as the player set it. Not a performance setting.
     */
    public static final String END_SKY_KEY = "end_sky";
    /** endSkies' answer for a palette that leaves the End sky alone. */
    public static final int ANY_END_SKY = -1;

    /** The End sky palette level picks, or ANY_END_SKY. */
    public static int endSkies(int level) {
        return switch (level) {
            case ANIME -> DimensionUniforms.END_SKY_ANIME;
            case DEFAULT -> DimensionUniforms.END_SKY_SPACE;
            default -> ANY_END_SKY;
        };
    }

    /** The End sky choosing palette level leaves, from the current one (endSkies; HORROR and CUSTOM keep it). */
    public static int endSky(int level, int style) {
        int own = endSkies(level);
        return own == ANY_END_SKY ? style : own;
    }

    /**
     * 2026-11-14 (the user: "in the Horror preset, turn the aurora off automatically"): the aurora mode is the second performance setting
     * a palette touches. HORROR turns the auroras off (and shows only while they are off); another palette takes them back on to the
     * performance preset's mode when they are off and the preset is a level, so going Horror and back restores the sky it had (under a
     * Custom preset the player's off is kept: nothing says it was Horror's). The performance presets keep the auroras off while the
     * palette is Horror (VisualsPresets.keepsLook).
     */
    public static final String AURORA_KEY = "aurora";

    /** The aurora mode choosing palette level leaves, from the current mode and performance preset. */
    public static int aurora(int level, int mode, int preset) {
        if (level == HORROR) return CelestialUniforms.AURORA_OFF;
        if (isLevel(level) && mode == CelestialUniforms.AURORA_OFF && VisualsPresets.isLevel(preset)) return VisualsPresets.value(AURORA_KEY, preset);
        return mode;
    }

    public static boolean isLookKey(String key) {
        for (String k : LOOK_KEYS) if (k.equals(key)) return true;
        return false;
    }

    /** Sets every colour and look setting to the palette's, the cloud style (cloudStyle), the star type (starType) and the aurora mode (aurora); CUSTOM (or anything else) changes nothing. Records the choice. */
    public static void apply(VisualsConfig c, int level) {
        if (isLevel(level)) {
            for (Colour colour : Colour.values()) c.setColour(colour, value(colour, level));
            for (String key : LOOK_KEYS) VisualsOptionLayout.set(c, VisualsOptionLayout.item(key), look(key, level));
            c.cloudStyle = cloudStyle(level, c.cloudStyle);
            c.starType = starType(level, c.starType);
            c.endSky = endSky(level, c.endSky);
            c.aurora = aurora(level, c.aurora, c.preset);
        }
        c.colourPreset = isLevel(level) ? level : CUSTOM;
    }

    /**
     * The palette the colours and look settings are all on (the first, so DEFAULT before the others), or CUSTOM. looks gives a look
     * setting's value by key, and the cloud style's (CLOUD_KEY) and aurora mode's (AURORA_KEY): ANIME also needs the anime clouds,
     * REALISTIC and CINEMATIC the realistic ones (clouds), HORROR the auroras off; and the star type's (STAR_KEY): ANIME also needs the
     * anime stars (stars).
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
            if (all && clouds(level) != ANY_CLOUDS) all = looks.applyAsInt(CLOUD_KEY) == clouds(level);
            if (all && stars(level) != ANY_STARS) all = looks.applyAsInt(STAR_KEY) == stars(level);
            if (all && endSkies(level) != ANY_END_SKY) all = looks.applyAsInt(END_SKY_KEY) == endSkies(level);
            if (all && level == HORROR) all = looks.applyAsInt(AURORA_KEY) == CelestialUniforms.AURORA_OFF;
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
     * 2026-11-14: a file saved before the look rows changed (before: the rows it saved, LOOK_BEFORE_21), on a palette whose colours are all
     * the palette's and whose every look setting is still that row's (or already the new one's; alsoBefore: older settings' old defaults,
     * for a file saved before the palettes set them), takes the palette's new row and stays on it: the player chose the palette, not those
     * numbers. A look setting the player changed keeps the file as it is. Returns whether it moved the file.
     */
    static boolean moveOldRows(VisualsConfig c, int[][] before, java.util.Map<String, Integer> alsoBefore) {
        int level = c.colourPreset;
        if (!isLevel(level)) return false;
        for (Colour colour : Colour.values()) if (c.colour(colour) != value(colour, level)) return false;
        for (int i = 0; i < LOOK_KEYS.length; i++) {
            int v = VisualsOptionLayout.get(c, VisualsOptionLayout.item(LOOK_KEYS[i]));
            Integer also = alsoBefore.get(LOOK_KEYS[i]);
            if (v != LOOK[level][i] && v != before[level][i] && (also == null || v != also)) return false;
        }
        for (int i = 0; i < LOOK_KEYS.length; i++) VisualsOptionLayout.set(c, VisualsOptionLayout.item(LOOK_KEYS[i]), LOOK[level][i]);
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

    /** 2026-11-17: the Anime palette's night sky before revision 26. */
    static final int ANIME_NIGHT_BEFORE_26 = 0x2E3C7C;

    /**
     * 2026-11-17: a file on the Anime palette whose night sky is still the palette's old one (ANIME_NIGHT_BEFORE_26) and whose every other
     * colour is the palette's (or, missing, at its default: a file older than those colours) takes the new night sky, so it stays on the
     * palette it chose. Returns whether it did.
     */
    static boolean moveOldAnimeNight(VisualsConfig c, java.util.Set<Colour> missing) {
        if (c.colourPreset != ANIME || c.colour(Colour.NIGHT_SKY) != ANIME_NIGHT_BEFORE_26) return false;
        for (Colour colour : Colour.values()) {
            if (colour == Colour.NIGHT_SKY) continue;
            boolean old = missing.contains(colour) && c.colour(colour) == colour.defaultRgb();
            if (!old && c.colour(colour) != value(colour, ANIME)) return false;
        }
        c.setColour(Colour.NIGHT_SKY, value(Colour.NIGHT_SKY, ANIME));
        return true;
    }

    /** A palette id revisions 8 to 27 saved, in today's numbering (Realistic and Cinematic, gone, as Custom; moveRetired runs first). */
    static int fromOldId(int old) {
        return switch (old) {
            case DEFAULT -> DEFAULT;
            case OLD_ANIME -> ANIME;
            case OLD_HORROR -> HORROR;
            default -> CUSTOM;
        };
    }

    /**
     * 2026-11-24: a file saved before revision 28 takes today's palette id (fromOldId). One on Realistic or Cinematic whose colours are
     * still all that palette's (or, missing, at their defaults: a file older than those colours) takes the Default palette: the player
     * chose the palette, not those numbers, and it is gone. Its cloud style and star type stay as they were. Any other file on them keeps
     * its colours and shows Custom. Returns whether it moved a file to Default.
     */
    static boolean moveRetired(VisualsConfig c, java.util.Set<Colour> missing) {
        int old = c.colourPreset;
        if (old != OLD_REALISTIC && old != OLD_CINEMATIC) {
            c.colourPreset = fromOldId(old);
            return false;
        }
        int[] row = RETIRED[old - OLD_REALISTIC];
        for (Colour colour : Colour.values()) {
            boolean absent = missing.contains(colour) && c.colour(colour) == colour.defaultRgb();
            if (!absent && c.colour(colour) != row[colour.ordinal()]) {
                c.colourPreset = CUSTOM;
                return false;
            }
        }
        apply(c, DEFAULT);
        return true;
    }

    /** A colour of the Realistic or Cinematic palette (OLD_REALISTIC, OLD_CINEMATIC), for the tests. */
    static int retiredColour(Colour c, int old) {
        return RETIRED[old - OLD_REALISTIC][c.ordinal()];
    }

    /** The look row in LOOK_KEYS order, for the tests. */
    static int[] looks(int level) {
        return LOOK[level].clone();
    }
}
