package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.ShaderColours.Colour;
import net.vulkanmod.visuals.render.StarMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-11-17 (session 18, the user: "match the sky colour too", after Genshin Impact's blue night): the Anime palette's night sky is a deep
 * blue; a file on Anime with the old one takes it and stays on the palette, any other file keeps its night sky.
 */
class PaletteNightSkyTest {
    private static Path older(Path dir, String name, VisualsConfig c, int revision) throws Exception {
        Path f = dir.resolve(name);
        c.write(f);
        OldFile.age(f, revision);
        return f;
    }

    @Test
    void animesNightSkyIsBlue() {
        int rgb = ColourPresets.value(Colour.NIGHT_SKY, ColourPresets.ANIME);
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        assertEquals(0x3A6CFF, rgb);
        assert b > g && g > r : Integer.toHexString(rgb);
    }

    @Test
    void anOlderFileOnAnimeTakesTheBlueNight(@TempDir Path dir) throws Exception {
        for (int revision : new int[]{23, 24, 25}) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, ColourPresets.ANIME);
            c.setColour(Colour.NIGHT_SKY, ColourPresets.ANIME_NIGHT_BEFORE_26);
            if (revision < VisualsConfig.PALETTE_STARS_REVISION) c.starType = StarMath.TYPE_VANILLA;
            VisualsConfig read = VisualsConfig.read(older(dir, "anime-" + revision + ".json", c, revision));
            assertEquals(ColourPresets.value(Colour.NIGHT_SKY, ColourPresets.ANIME), read.colour(Colour.NIGHT_SKY), "revision " + revision);
            assertEquals(ColourPresets.ANIME, read.colourPreset, "revision " + revision);
            assertEquals(StarMath.TYPE_ANIME, read.starType, "revision " + revision + ": the stars' move still finds Anime");
            assertEquals(VisualsConfig.currentRevision(), read.revision);
        }
    }

    @Test
    void otherFilesKeepTheirNightSky(@TempDir Path dir) throws Exception {
        //Anime with a night sky of the player's own
        VisualsConfig own = new VisualsConfig();
        ColourPresets.apply(own, ColourPresets.ANIME);
        own.setColour(Colour.NIGHT_SKY, 0x203050);
        assertEquals(0x203050, VisualsConfig.read(older(dir, "own.json", own, 25)).colour(Colour.NIGHT_SKY));
        //Anime with the old night sky but another colour changed too: Custom, kept as it is
        VisualsConfig changed = new VisualsConfig();
        ColourPresets.apply(changed, ColourPresets.ANIME);
        changed.setColour(Colour.NIGHT_SKY, ColourPresets.ANIME_NIGHT_BEFORE_26);
        changed.setColour(Colour.FOG, 0x102030);
        assertEquals(ColourPresets.ANIME_NIGHT_BEFORE_26, VisualsConfig.read(older(dir, "changed.json", changed, 25)).colour(Colour.NIGHT_SKY));
        //another palette with Anime's old night sky picked by hand
        VisualsConfig other = new VisualsConfig();
        ColourPresets.apply(other, ColourPresets.DEFAULT);
        other.setColour(Colour.NIGHT_SKY, ColourPresets.ANIME_NIGHT_BEFORE_26);
        assertEquals(ColourPresets.ANIME_NIGHT_BEFORE_26, VisualsConfig.read(older(dir, "other.json", other, 25)).colour(Colour.NIGHT_SKY));
        //the current revision is never moved: the player picked the old colour now
        VisualsConfig now = new VisualsConfig();
        ColourPresets.apply(now, ColourPresets.ANIME);
        now.setColour(Colour.NIGHT_SKY, ColourPresets.ANIME_NIGHT_BEFORE_26);
        Path f = dir.resolve("now.json");
        now.write(f);
        assertEquals(ColourPresets.ANIME_NIGHT_BEFORE_26, VisualsConfig.read(f).colour(Colour.NIGHT_SKY));
    }
}
