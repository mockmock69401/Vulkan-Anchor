package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-24 (the user: "모두 커밋, 애니는 수채화"): the Anime palette picks the anime (watercolour) clouds again (revision 29); a file
 * saved at 27 or 28 on Anime with the realistic clouds it picked then takes them and stays on the palette and its performance level.
 */
class AnimeWatercolourPaletteTest {
    private static VisualsConfig savedOnAnime(int preset) {
        VisualsConfig c = new VisualsConfig();
        VisualsPresets.apply(c, preset);
        ColourPresets.apply(c, ColourPresets.ANIME);
        //what revisions 27 and 28 gave the palette
        c.cloudStyle = CloudMath.STYLE_REALISTIC;
        return c;
    }

    private static VisualsConfig read(Path dir, VisualsConfig c, int revision) throws Exception {
        Path f = dir.resolve("visuals-" + revision + ".json");
        c.write(f);
        OldFile.age(f, revision);
        return VisualsConfig.read(f);
    }

    @Test
    void theAnimePalettePicksTheAnimeClouds() {
        assertEquals(CloudMath.STYLE_ANIME, ColourPresets.clouds(ColourPresets.ANIME));
        assertEquals(ColourPresets.ANY_CLOUDS, ColourPresets.clouds(ColourPresets.DEFAULT));
        assertEquals(net.vulkanmod.visuals.render.CloudMath.STYLE_REALISTIC, ColourPresets.clouds(ColourPresets.HORROR), "2026-11-24: Horror's realistic clouds");
        assertTrue(VisualsConfig.currentRevision() >= VisualsConfig.ANIME_WATERCOLOUR_REVISION);
    }

    @Test
    void aFileOnAnimeWithTheRealisticCloudsTakesTheAnimeOnes(@TempDir Path dir) throws Exception {
        for (int revision : new int[]{27, 28}) {
            for (int preset : new int[]{VisualsPresets.HIGH, VisualsPresets.MEDIUM, VisualsPresets.CUSTOM}) {
                VisualsConfig read = read(dir, savedOnAnime(preset), revision);
                String what = "revision " + revision + ", preset " + preset;
                assertEquals(CloudMath.STYLE_ANIME, read.cloudStyle, what);
                assertEquals(ColourPresets.ANIME, read.colourPreset, what);
                assertEquals(preset, read.preset, what);
                assertEquals(VisualsConfig.currentRevision(), read.revision, what);
            }
        }
    }

    @Test
    void aFileChangedByHandKeepsItsClouds(@TempDir Path dir) throws Exception {
        for (int revision : new int[]{27, 28}) {
            //its clouds changed by hand
            VisualsConfig vanilla = savedOnAnime(VisualsPresets.HIGH);
            vanilla.cloudStyle = CloudMath.STYLE_VANILLA;
            VisualsConfig read = read(dir, vanilla, revision);
            assertEquals(CloudMath.STYLE_VANILLA, read.cloudStyle);
            assertEquals(ColourPresets.CUSTOM, read.colourPreset);
            //a colour changed by hand: it was Custom then, and keeps its realistic clouds
            VisualsConfig colour = savedOnAnime(VisualsPresets.HIGH);
            colour.setColour(Colour.WATER, 0x123456);
            read = read(dir, colour, revision);
            assertEquals(CloudMath.STYLE_REALISTIC, read.cloudStyle);
            assertEquals(ColourPresets.CUSTOM, read.colourPreset);
            //another palette keeps the realistic clouds
            VisualsConfig other = new VisualsConfig();
            ColourPresets.apply(other, ColourPresets.HORROR);
            other.cloudStyle = CloudMath.STYLE_REALISTIC;
            read = read(dir, other, revision);
            assertEquals(CloudMath.STYLE_REALISTIC, read.cloudStyle);
            assertEquals(ColourPresets.HORROR, read.colourPreset);
        }
        //a file of the current revision on Anime with the realistic clouds was changed by hand: it keeps them and shows Custom
        Path f = dir.resolve("now.json");
        savedOnAnime(VisualsPresets.HIGH).write(f);
        VisualsConfig now = VisualsConfig.read(f);
        assertEquals(CloudMath.STYLE_REALISTIC, now.cloudStyle);
        assertEquals(ColourPresets.CUSTOM, now.colourPreset);
    }
}
