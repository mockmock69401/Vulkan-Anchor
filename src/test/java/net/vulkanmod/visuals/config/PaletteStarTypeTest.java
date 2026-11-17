package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.StarMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-17 (shader roadmap session 12 follow-up, the user: "Yes, link them"): the Anime palette picks the anime stars as it picks the
 * anime clouds, and shows only while they are on; every other palette and the performance presets leave the star type as the player set
 * it.
 */
class PaletteStarTypeTest {
    private static final int[] TYPES = {StarMath.TYPE_VANILLA, StarMath.TYPE_ROUND, StarMath.TYPE_ANIME};
    private static final int[] PALETTES = {ColourPresets.DEFAULT, ColourPresets.REALISTIC, ColourPresets.CINEMATIC, ColourPresets.ANIME,
            ColourPresets.HORROR};
    private static final int[] PRESETS = {VisualsPresets.POTATO, VisualsPresets.LOW, VisualsPresets.MEDIUM, VisualsPresets.HIGH,
            VisualsPresets.ULTRA, VisualsPresets.CUSTOM};

    private static int expected(int palette, int type) {
        return palette == ColourPresets.ANIME ? StarMath.TYPE_ANIME : type;
    }

    /** Every palette from every palette, star type and performance preset: Anime's stars, or the type as it was. */
    @Test
    void animePicksTheAnimeStarsAndTheOthersLeaveTheType() {
        for (int preset : PRESETS) {
            for (int from : PALETTES) {
                for (int type : TYPES) {
                    for (int to : PALETTES) {
                        VisualsConfig c = new VisualsConfig();
                        VisualsPresets.apply(c, preset);
                        ColourPresets.apply(c, from);
                        c.starType = type;
                        int shownPreset = VisualsPresets.detect(c);
                        ColourPresets.apply(c, to);
                        String what = "palette " + from + " -> " + to + ", type " + type + ", preset " + preset;
                        assertEquals(expected(to, type), c.starType, what);
                        assertEquals(to, ColourPresets.detect(c), what);
                        //the star type is no performance setting: the level shown is the same whenever the clouds and auroras stayed
                        boolean otherLooks = ColourPresets.clouds(from) != ColourPresets.ANY_CLOUDS || ColourPresets.clouds(to) != ColourPresets.ANY_CLOUDS
                                || from == ColourPresets.HORROR || to == ColourPresets.HORROR;
                        if (!otherLooks) assertEquals(shownPreset, VisualsPresets.detect(c), what);
                    }
                }
            }
        }
        assertEquals(ColourPresets.ANY_STARS, ColourPresets.stars(ColourPresets.CUSTOM));
        for (int type : TYPES) assertEquals(type, ColourPresets.starType(ColourPresets.CUSTOM, type), "Custom changes nothing");
    }

    /** Anime shows only while the anime stars are on (changing the type by hand shows Custom); the others show with any type. */
    @Test
    void animeNeedsTheAnimeStars() {
        for (int palette : PALETTES) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, palette);
            for (int type : TYPES) {
                c.starType = type;
                int shown = palette != ColourPresets.ANIME || type == StarMath.TYPE_ANIME ? palette : ColourPresets.CUSTOM;
                assertEquals(shown, ColourPresets.detect(c), "palette " + palette + ", type " + type);
            }
        }
        //the default config is on Default with vanilla's stars, as before
        assertEquals(ColourPresets.DEFAULT, ColourPresets.detect(new VisualsConfig()));
        assertEquals(StarMath.TYPE_VANILLA, new VisualsConfig().starType);
    }

    /** The performance presets never touch the star type: the anime stars Anime set stay, and the palette and level stay shown. */
    @Test
    void thePerformancePresetsKeepTheStarType() {
        assertFalse(VisualsPresets.VALUES.containsKey(ColourPresets.STAR_KEY), "the star type is no performance setting");
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            for (int palette : PALETTES) {
                for (int type : TYPES) {
                    VisualsConfig c = new VisualsConfig();
                    ColourPresets.apply(c, palette);
                    if (palette != ColourPresets.ANIME) c.starType = type;
                    int before = c.starType;
                    VisualsPresets.apply(c, level);
                    assertEquals(before, c.starType, "palette " + palette + ", level " + level);
                    assertEquals(palette, ColourPresets.detect(c), "the palette stays");
                    assertEquals(level, VisualsPresets.detect(c), "the level stays");
                }
            }
        }
    }

    /** A file on Anime from before it picked the anime stars takes them and stays on the palette and level; others keep their type. */
    @Test
    void anOlderFileOnAnimeTakesTheAnimeStars(@TempDir Path dir) throws Exception {
        for (int palette : PALETTES) {
            for (int preset : new int[]{VisualsPresets.HIGH, VisualsPresets.CUSTOM}) {
                for (int type : new int[]{StarMath.TYPE_VANILLA, StarMath.TYPE_ROUND}) {
                    VisualsConfig c = new VisualsConfig();
                    VisualsPresets.apply(c, preset);
                    ColourPresets.apply(c, palette);
                    c.starType = type;
                    c.colourPreset = palette;
                    Path f = dir.resolve("visuals-" + palette + "-" + preset + "-" + type + ".json");
                    c.write(f);
                    Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 24"));
                    VisualsConfig read = VisualsConfig.read(f);
                    String what = "palette " + palette + ", preset " + preset + ", type " + type;
                    assertEquals(expected(palette, type), read.starType, what);
                    assertEquals(palette, read.colourPreset, what);
                    assertEquals(preset, read.preset, what);
                    assertEquals(VisualsConfig.currentRevision(), read.revision);
                }
            }
        }
        //a file of the current revision on Anime with vanilla's stars is Custom: the player changed the star type
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.ANIME);
        c.starType = StarMath.TYPE_VANILLA;
        Path f = dir.resolve("now.json");
        c.write(f);
        VisualsConfig read = VisualsConfig.read(f);
        assertEquals(StarMath.TYPE_VANILLA, read.starType);
        assertEquals(ColourPresets.CUSTOM, read.colourPreset);
        //an older file on Anime with colours of its own (so Custom) keeps its stars
        VisualsConfig own = new VisualsConfig();
        ColourPresets.apply(own, ColourPresets.ANIME);
        own.starType = StarMath.TYPE_ROUND;
        own.saturation += 7;
        own.colourPreset = ColourPresets.ANIME;
        Path g = dir.resolve("own.json");
        own.write(g);
        Files.writeString(g, Files.readString(g).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 24"));
        VisualsConfig ownRead = VisualsConfig.read(g);
        assertEquals(StarMath.TYPE_ROUND, ownRead.starType);
        assertEquals(ColourPresets.CUSTOM, ownRead.colourPreset);
    }
}
