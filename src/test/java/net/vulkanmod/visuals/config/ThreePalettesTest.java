package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-24 (the user: "기존 기본과 애니메이션, 공포 프리셋만 남기고 나머지 두 개의 프리셋은 지워줘"): three palettes are left, renumbered;
 * an older file keeps its palette, and one on Realistic or Cinematic takes Default.
 */
class ThreePalettesTest {
    @Test
    void threePalettesAndCustom() {
        assertEquals(3, ColourPresets.LEVELS);
        assertEquals(ColourPresets.LEVELS, ColourPresets.CUSTOM);
        for (int level = 0; level < ColourPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, level);
            assertEquals(level, ColourPresets.detect(c), "palette " + level);
        }
        assertEquals(ColourPresets.CUSTOM, VisualsOptionLayout.item(VisualsOptionLayout.COLOUR_PRESET).max());
    }

    private static VisualsConfig readOld(Path dir, String name, VisualsConfig c, int oldId) throws Exception {
        Path f = dir.resolve(name + ".json");
        c.write(f);
        Files.writeString(f, Files.readString(f)
                .replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 27")
                .replaceAll("\"colourPreset\": [0-9]+", "\"colourPreset\": " + oldId));
        return VisualsConfig.read(f);
    }

    /** A revision 27 file keeps Default, Anime, Horror and Custom under their new numbers. */
    @Test
    void anOlderFileKeepsItsPalette(@TempDir Path dir) throws Exception {
        int[][] cases = {{ColourPresets.DEFAULT, 0}, {ColourPresets.ANIME, ColourPresets.OLD_ANIME}, {ColourPresets.HORROR, ColourPresets.OLD_HORROR}};
        for (int[] k : cases) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, k[0]);
            VisualsConfig read = readOld(dir, "p" + k[0], c, k[1]);
            assertEquals(k[0], read.colourPreset, "palette " + k[0]);
            for (Colour colour : Colour.values()) assertEquals(ColourPresets.value(colour, k[0]), read.colour(colour), colour.name());
        }
        VisualsConfig custom = new VisualsConfig();
        ColourPresets.apply(custom, ColourPresets.ANIME);
        custom.setColour(Colour.WATER, 0x123456);
        assertEquals(ColourPresets.CUSTOM, readOld(dir, "custom", custom, ColourPresets.OLD_CUSTOM).colourPreset);
    }

    /** A file still on Realistic or Cinematic takes Default and keeps its clouds; one changed by hand keeps its colours as Custom. */
    @Test
    void anOlderFileOnARemovedPaletteTakesDefault(@TempDir Path dir) throws Exception {
        for (int old : new int[]{ColourPresets.OLD_REALISTIC, ColourPresets.OLD_CINEMATIC}) {
            VisualsConfig c = new VisualsConfig();
            for (Colour colour : Colour.values()) c.setColour(colour, ColourPresets.retiredColour(colour, old));
            c.cloudStyle = CloudMath.STYLE_REALISTIC;
            c.fogStrength = 40;
            VisualsConfig read = readOld(dir, "r" + old, c, old);
            assertEquals(ColourPresets.DEFAULT, read.colourPreset, "palette " + old);
            for (Colour colour : Colour.values()) assertEquals(ColourPresets.value(colour, ColourPresets.DEFAULT), read.colour(colour), colour.name());
            assertEquals(ColourPresets.look("fog_strength", ColourPresets.DEFAULT), read.fogStrength);
            assertEquals(CloudMath.STYLE_REALISTIC, read.cloudStyle, "the clouds stay");

            c.setColour(Colour.WATER, 0x123456);
            VisualsConfig mine = readOld(dir, "m" + old, c, old);
            assertEquals(ColourPresets.CUSTOM, mine.colourPreset);
            assertEquals(0x123456, mine.colour(Colour.WATER));
            assertEquals(ColourPresets.retiredColour(Colour.DAY_SKY, old), mine.colour(Colour.DAY_SKY));
        }
    }
}
