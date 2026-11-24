package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CloudMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-16 (the user: "only the Anime preset should change the clouds; choosing another preset resets the cloud choice", then "the
 * Realistic and Cinematic presets should pick the realistic clouds"): Realistic and Cinematic pick the realistic clouds, Anime the anime
 * ones, and Default and Horror leave the style as the player set it; the performance presets keep a palette's clouds. 2026-11-23 (the
 * user: "애니메이션 색상 프리셋에서 현실적 구름 사용"): Anime picks the realistic clouds too.
 */
class PaletteCloudStyleTest {
    private static final int[] STYLES = {CloudMath.STYLE_VANILLA, CloudMath.STYLE_REALISTIC, CloudMath.STYLE_ANIME};
    private static final int[] PALETTES = {ColourPresets.DEFAULT, ColourPresets.ANIME, ColourPresets.HORROR};
    private static final int[] PRESETS = {VisualsPresets.POTATO, VisualsPresets.LOW, VisualsPresets.MEDIUM, VisualsPresets.HIGH,
            VisualsPresets.ULTRA, VisualsPresets.CUSTOM};

    private static int expected(int palette, int style) {
        return switch (palette) {
            case ColourPresets.ANIME -> CloudMath.STYLE_REALISTIC;
            default -> style;
        };
    }

    /** Every palette from every palette, cloud style and performance preset: its own clouds, or the style as it was. */
    @Test
    void eachPaletteSetsItsCloudsOrLeavesThem() {
        for (int preset : PRESETS) {
            for (int from : PALETTES) {
                for (int style : STYLES) {
                    for (int to : PALETTES) {
                        VisualsConfig c = new VisualsConfig();
                        VisualsPresets.apply(c, preset);
                        ColourPresets.apply(c, from);
                        c.cloudStyle = style;
                        int shownPreset = VisualsPresets.detect(c);
                        ColourPresets.apply(c, to);
                        String what = "palette " + from + " -> " + to + ", style " + style + ", preset " + preset;
                        assertEquals(expected(to, style), c.cloudStyle, what);
                        assertEquals(to, ColourPresets.detect(c), what);
                        //the performance preset still shows what it did, unless the clouds changed or became (or stopped being) a
                        // palette's own (Realistic's and Cinematic's realistic clouds count as any level's: keepsLook)
                        //(leaving Horror's auroras off under a Custom preset is ColourPresets.aurora's, not the clouds')
                        boolean palettesOwn = ColourPresets.clouds(from) == CloudMath.STYLE_REALISTIC || ColourPresets.clouds(to) == CloudMath.STYLE_REALISTIC
                                || from == ColourPresets.HORROR;
                        if (c.cloudStyle == style && !palettesOwn) assertEquals(shownPreset, VisualsPresets.detect(c), what);
                    }
                }
            }
        }
    }

    /** The user's report: leaving Anime for Default or Horror kept wiping the cloud choice (back to the vanilla shape at High). */
    @Test
    void defaultAndHorrorKeepTheChoice() {
        for (int to : new int[]{ColourPresets.DEFAULT, ColourPresets.HORROR}) {
            for (int style : STYLES) {
                VisualsConfig c = new VisualsConfig();
                c.cloudStyle = style;
                ColourPresets.apply(c, to);
                assertEquals(style, c.cloudStyle, "palette " + to);
                assertEquals(to, ColourPresets.detect(c), "any cloud style is on palette " + to);
            }
            //the clouds Anime brought stay too: Default and Horror never change the clouds
            VisualsConfig d = new VisualsConfig();
            ColourPresets.apply(d, ColourPresets.ANIME);
            ColourPresets.apply(d, to);
            assertEquals(CloudMath.STYLE_REALISTIC, d.cloudStyle);
        }
    }

    /** Realistic, Cinematic and Anime show only while their clouds are on; changing the style by hand shows Custom. */
    @Test
    void aPaletteWithCloudsNeedsThem() {
        for (int palette : new int[]{ColourPresets.ANIME}) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, palette);
            for (int style : STYLES) {
                c.cloudStyle = style;
                assertEquals(style == ColourPresets.clouds(palette) ? palette : ColourPresets.CUSTOM, ColourPresets.detect(c),
                        "palette " + palette + ", style " + style);
            }
        }
        assertEquals(ColourPresets.ANY_CLOUDS, ColourPresets.clouds(ColourPresets.CUSTOM));
        for (int style : STYLES) assertEquals(style, ColourPresets.cloudStyle(ColourPresets.CUSTOM, style), "Custom changes nothing");
    }

    /**
     * The performance presets keep a palette's clouds and count them as the level's own; without such a palette the realistic clouds
     * are the cost they were (a level below Ultra sets the vanilla shape), and the anime clouds stay as before.
     */
    @Test
    void thePerformancePresetsKeepAPalettesClouds() {
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            for (int palette : PALETTES) {
                VisualsConfig c = new VisualsConfig();
                ColourPresets.apply(c, palette);
                VisualsPresets.apply(c, level);
                int own = VisualsPresets.value(ColourPresets.CLOUD_KEY, level);
                assertEquals(ColourPresets.clouds(palette) == ColourPresets.ANY_CLOUDS ? own : ColourPresets.clouds(palette), c.cloudStyle,
                        "palette " + palette + ", level " + level);
                assertEquals(palette, ColourPresets.detect(c), "the palette stays");
                assertEquals(level, VisualsPresets.detect(c), "the level stays");
            }
            //the realistic clouds by hand under Default or Horror: a level's own style
            for (int palette : new int[]{ColourPresets.DEFAULT, ColourPresets.HORROR}) {
                VisualsConfig d = new VisualsConfig();
                ColourPresets.apply(d, palette);
                d.cloudStyle = CloudMath.STYLE_REALISTIC;
                VisualsPresets.apply(d, level);
                assertEquals(VisualsPresets.value(ColourPresets.CLOUD_KEY, level), d.cloudStyle);
            }
        }
        assertFalse(VisualsPresets.keepsLook(ColourPresets.CLOUD_KEY, CloudMath.STYLE_REALISTIC), "no palette known: not kept");
        assertTrue(VisualsPresets.keepsLook(ColourPresets.CLOUD_KEY, CloudMath.STYLE_ANIME));
    }

    /** A file on Realistic or Cinematic from before they picked the realistic clouds takes them and stays on the palette and level. */
    @Test
    void anOlderFileOnRealisticOrCinematicTakesTheRealisticClouds(@TempDir Path dir) throws Exception {
        for (int palette : PALETTES) {
            for (int preset : new int[]{VisualsPresets.HIGH, VisualsPresets.CUSTOM}) {
                VisualsConfig c = new VisualsConfig();
                VisualsPresets.apply(c, preset);
                ColourPresets.apply(c, palette);
                //Anime then picked the anime clouds (until revision 27)
                c.cloudStyle = palette == ColourPresets.ANIME ? CloudMath.STYLE_ANIME : CloudMath.STYLE_VANILLA;
                c.colourPreset = palette;
                Path f = dir.resolve("visuals-" + palette + "-" + preset + ".json");
                c.write(f);
                OldFile.age(f, 23);
                VisualsConfig read = VisualsConfig.read(f);
                assertEquals(expected(palette, CloudMath.STYLE_VANILLA), read.cloudStyle, "palette " + palette);
                assertEquals(palette, read.colourPreset);
                assertEquals(preset, read.preset);
                assertEquals(VisualsConfig.currentRevision(), read.revision);
            }
        }
        //a file of the current revision on Anime with the vanilla shape is Custom: the player changed the clouds
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.ANIME);
        c.cloudStyle = CloudMath.STYLE_VANILLA;
        Path f = dir.resolve("now.json");
        c.write(f);
        VisualsConfig read = VisualsConfig.read(f);
        assertEquals(CloudMath.STYLE_VANILLA, read.cloudStyle);
        assertEquals(ColourPresets.CUSTOM, read.colourPreset);
    }
}
