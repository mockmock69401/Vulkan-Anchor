package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.ShaderColours;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-10 (session 8): the colour palettes (ColourPresets): every colour, distinct, Custom when changed, saved and read back. */
class ColourPresetsTest {
    @Test
    void everyPaletteSetsEveryColourAndTheyDiffer() {
        assertArrayEquals(ShaderColours.defaults(), ColourPresets.palette(ColourPresets.DEFAULT), "Default is the look before the pickers");
        Set<String> seen = new HashSet<>();
        for (int level = 0; level < ColourPresets.LEVELS; level++) {
            int[] p = ColourPresets.palette(level);
            assertEquals(ShaderColours.COUNT, p.length, "level " + level);
            for (int v : p) assertTrue(v >= 0 && v <= 0xFFFFFF);
            assertTrue(seen.add(java.util.Arrays.toString(p)), "level " + level + " is its own");
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, level);
            assertArrayEquals(p, c.colours());
            assertEquals(level, c.colourPreset);
            assertEquals(level, ColourPresets.detect(c::colour));
        }
        //The horror palette is darker than the anime one (night, day sky), the anime sky bluer than the default
        assertTrue(lum(ColourPresets.value(Colour.NIGHT_SKY, ColourPresets.HORROR)) < lum(ColourPresets.value(Colour.NIGHT_SKY, ColourPresets.ANIME)));
        assertTrue(lum(ColourPresets.value(Colour.DAY_SKY, ColourPresets.HORROR)) < lum(ColourPresets.value(Colour.DAY_SKY, ColourPresets.ANIME)));
    }

    private static float lum(int rgb) {
        return 0.2126f * ((rgb >> 16) & 0xFF) + 0.7152f * ((rgb >> 8) & 0xFF) + 0.0722f * (rgb & 0xFF);
    }

    @Test
    void changingOneColourIsCustomAndCustomChangesNothing() {
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.CINEMATIC);
        c.setColour(Colour.WATER, 0x123456);
        assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(c::colour));
        int[] before = c.colours();
        ColourPresets.apply(c, ColourPresets.CUSTOM);
        assertArrayEquals(before, c.colours());
        assertEquals(ColourPresets.CUSTOM, c.colourPreset);
        //the performance presets leave the palette and the colours alone
        VisualsPresets.apply(c, VisualsPresets.POTATO);
        assertArrayEquals(before, c.colours());
    }

    @Test
    void theFileKeepsThePaletteAndAnOlderOneIsDefault(@TempDir Path dir) throws Exception {
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.HORROR);
        Path f = dir.resolve("v.json");
        c.write(f);
        VisualsConfig back = VisualsConfig.read(f);
        assertEquals(ColourPresets.HORROR, back.colourPreset);
        assertArrayEquals(c.colours(), back.colours());
        //a palette whose colours were edited by hand reads as Custom
        Files.writeString(f, Files.readString(f).replace("\"colourWater\": " + ColourPresets.value(Colour.WATER, ColourPresets.HORROR), "\"colourWater\": 1"));
        assertEquals(ColourPresets.CUSTOM, VisualsConfig.read(f).colourPreset);
        Path old = dir.resolve("old.json");
        Files.writeString(old, "{\"enabled\": true, \"revision\": 6}");
        assertEquals(ColourPresets.DEFAULT, VisualsConfig.read(old).colourPreset);
    }

    @Test
    void thePaletteIsFirstOnTheColoursPageWithItsLabels() throws Exception {
        VisualsOptionLayout.Item item = VisualsOptionLayout.item(VisualsOptionLayout.COLOUR_PRESET);
        assertEquals(VisualsOptionLayout.Kind.CHOICE, item.kind());
        assertEquals(ColourPresets.CUSTOM, item.max());
        VisualsOptionLayout.Block first = VisualsOptionLayout.PAGES.stream().filter(p -> p.key().equals("colours")).findFirst().orElseThrow().blocks().get(0);
        assertEquals(VisualsOptionLayout.COLOUR_PRESET, first.items().get(0).key());
        VisualsConfig c = new VisualsConfig();
        VisualsOptionLayout.set(c, item, ColourPresets.ANIME);
        assertEquals(ColourPresets.value(Colour.WATER, ColourPresets.ANIME), c.colour(Colour.WATER));
        assertEquals(ColourPresets.ANIME, VisualsOptionLayout.get(c, item));
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            String text = Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/" + lang + ".json"));
            for (int v = 0; v <= ColourPresets.CUSTOM; v++) assertTrue(text.contains("\"vulkanmod.visuals.colour_preset." + v + "\""), lang + " " + v);
            assertTrue(text.contains("\"vulkanmod.visuals.colour_preset.tooltip\""));
        }
    }
}
