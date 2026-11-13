package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CloudMath;
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
            assertEquals(level, ColourPresets.detect(c));
            //session 8 follow-up: and its bloom, saturation and fog
            int[] look = ColourPresets.looks(level);
            for (int i = 0; i < ColourPresets.LOOK_KEYS.length; i++) {
                VisualsOptionLayout.Item item = VisualsOptionLayout.item(ColourPresets.LOOK_KEYS[i]);
                assertEquals(look[i], VisualsOptionLayout.get(c, item), level + " " + item.key());
                assertTrue(look[i] >= item.min() && look[i] <= item.max(), level + " " + item.key());
            }
        }
        //Default's look is the config's defaults (a fresh config is the Default palette)
        for (String key : ColourPresets.LOOK_KEYS) {
            assertEquals(VisualsOptionLayout.defaultValue(VisualsOptionLayout.item(key)), ColourPresets.look(key, ColourPresets.DEFAULT), key);
        }
        assertEquals(ColourPresets.DEFAULT, ColourPresets.detect(new VisualsConfig()));
        assertTrue(ColourPresets.look("saturation", ColourPresets.ANIME) > ColourPresets.look("saturation", ColourPresets.DEFAULT));
        assertTrue(ColourPresets.look("fog_strength", ColourPresets.HORROR) > ColourPresets.look("fog_strength", ColourPresets.DEFAULT));
        assertTrue(ColourPresets.look("saturation", ColourPresets.HORROR) < ColourPresets.look("saturation", ColourPresets.DEFAULT));
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
        assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(c));
        int[] before = c.colours();
        ColourPresets.apply(c, ColourPresets.CUSTOM);
        assertArrayEquals(before, c.colours());
        assertEquals(ColourPresets.CUSTOM, c.colourPreset);
        //the performance presets leave the palette and the colours alone
        VisualsPresets.apply(c, VisualsPresets.POTATO);
        assertArrayEquals(before, c.colours());
        //session 8 follow-up: changing a look setting is Custom too, and back on the palette's value the palette again
        VisualsConfig look = new VisualsConfig();
        ColourPresets.apply(look, ColourPresets.ANIME);
        look.bloomStrength = 10;
        assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(look));
        look.bloomStrength = ColourPresets.look("bloom_strength", ColourPresets.ANIME);
        assertEquals(ColourPresets.ANIME, ColourPresets.detect(look));
    }

    /**
     * Session 8 follow-up: the palettes' look settings and the performance presets' settings are disjoint, so neither kind of preset ever
     * sets what the other set: every level of one leaves the other's values (and shown preset) as they were.
     */
    @Test
    void thePalettesAndThePerformancePresetsNeverSetTheSameSetting() {
        for (String key : ColourPresets.LOOK_KEYS) {
            assertFalse(VisualsPresets.VALUES.containsKey(key), key);
            assertNotNull(VisualsOptionLayout.item(key));
        }
        for (int palette = 0; palette < ColourPresets.LEVELS; palette++) {
            for (int level = VisualsPresets.POTATO; level <= VisualsPresets.ULTRA; level++) {
                VisualsConfig c = new VisualsConfig();
                ColourPresets.apply(c, palette);
                VisualsPresets.apply(c, level);
                assertEquals(palette, ColourPresets.detect(c), palette + " after performance " + level);
                ColourPresets.apply(c, (palette + 1) % ColourPresets.LEVELS);
                assertEquals(level, VisualsPresets.detect(c), "performance " + level + " after palette " + palette);
            }
        }
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
        //Session 8 follow-up: a revision 8 file keeps its look values (the picture it had), so a palette chosen before the palettes set them
        // shows Custom unless they happen to be the palette's; its colours (without the new two: their defaults) are kept too
        Path eight = dir.resolve("eight.json");
        VisualsConfig before = new VisualsConfig();
        ColourPresets.apply(before, ColourPresets.HORROR);
        before.bloomStrength = 45;
        before.saturation = 94;
        before.fogStrength = 100;
        before.write(eight);
        Files.writeString(eight, Files.readString(eight).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 8")
                .replaceAll("\\s*\"colourSunlight\": \\d+,", "").replaceAll("\\s*\"colourShadeLight\": \\d+,", ""));
        VisualsConfig read8 = VisualsConfig.read(eight);
        assertEquals(45, read8.bloomStrength);
        assertEquals(94, read8.saturation);
        assertEquals(100, read8.fogStrength);
        assertEquals(Colour.SUNLIGHT.defaultRgb(), read8.colour(Colour.SUNLIGHT));
        assertEquals(ColourPresets.value(Colour.MOON, ColourPresets.HORROR), read8.colour(Colour.MOON));
        assertEquals(ColourPresets.CUSTOM, read8.colourPreset);
        assertEquals(VisualsConfig.currentRevision(), read8.revision);
        //an untouched revision 8 config stays the Default palette
        Path plain8 = dir.resolve("plain8.json");
        Files.writeString(plain8, "{\"enabled\": true, \"colourPreset\": 0, \"revision\": 8}");
        assertEquals(ColourPresets.DEFAULT, VisualsConfig.read(plain8).colourPreset);
    }

    /**
     * Session 11: the Anime palette picks the anime clouds and shows only while they are on; another palette leaves the style alone but
     * takes the anime clouds back to the performance preset's style; the default config is unchanged.
     */
    @Test
    void theAnimePaletteBringsTheAnimeClouds() {
        VisualsConfig fresh = new VisualsConfig();
        assertEquals(CloudMath.STYLE_VANILLA, fresh.cloudStyle, "the default sky is unchanged");
        assertEquals(ColourPresets.DEFAULT, ColourPresets.detect(fresh));
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.ANIME);
        assertEquals(CloudMath.STYLE_ANIME, c.cloudStyle);
        assertEquals(ColourPresets.ANIME, ColourPresets.detect(c));
        assertEquals(VisualsPresets.HIGH, VisualsPresets.detect(c), "the performance preset stays (the anime clouds are a look)");
        //The style changed by hand: Custom, as a colour would; back to anime: Anime again
        for (int style : new int[]{CloudMath.STYLE_VANILLA, CloudMath.STYLE_REALISTIC}) {
            c.cloudStyle = style;
            assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(c), "style " + style);
        }
        c.cloudStyle = CloudMath.STYLE_ANIME;
        assertEquals(ColourPresets.ANIME, ColourPresets.detect(c));
        //Another palette: back to the performance level's style (High: the vanilla shape; Ultra: realistic; Custom: the vanilla shape)
        for (int level : new int[]{VisualsPresets.HIGH, VisualsPresets.ULTRA, VisualsPresets.CUSTOM}) {
            for (int palette : new int[]{ColourPresets.DEFAULT, ColourPresets.REALISTIC, ColourPresets.CINEMATIC, ColourPresets.HORROR}) {
                VisualsConfig d = new VisualsConfig();
                VisualsPresets.apply(d, level);
                int before = d.cloudStyle;
                ColourPresets.apply(d, ColourPresets.ANIME);
                ColourPresets.apply(d, palette);
                assertEquals(before, d.cloudStyle, "palette " + palette + " after Anime at preset " + level);
                assertEquals(palette, ColourPresets.detect(d));
                if (VisualsPresets.isLevel(level)) assertEquals(level, VisualsPresets.detect(d));
            }
        }
        //...and leaves a style that is not the anime one alone (the realistic clouds under Custom stay), and the other palettes ignore it
        VisualsConfig e = new VisualsConfig();
        e.cloudStyle = CloudMath.STYLE_REALISTIC;
        ColourPresets.apply(e, ColourPresets.HORROR);
        assertEquals(CloudMath.STYLE_REALISTIC, e.cloudStyle);
        assertEquals(ColourPresets.HORROR, ColourPresets.detect(e));
        e.cloudStyle = CloudMath.STYLE_ANIME;
        assertEquals(ColourPresets.HORROR, ColourPresets.detect(e), "anime clouds by hand under another palette keep it");
        assertEquals(CloudMath.STYLE_ANIME, ColourPresets.cloudStyle(ColourPresets.CUSTOM, CloudMath.STYLE_ANIME, VisualsPresets.HIGH), "Custom changes nothing");
    }

    /** Session 11: a file saved on the Anime palette before it included the clouds takes them and stays Anime; others keep their style. */
    @Test
    void anOlderAnimeFileTakesTheAnimeClouds(@TempDir Path dir) throws Exception {
        VisualsConfig before = new VisualsConfig();
        ColourPresets.apply(before, ColourPresets.ANIME);
        before.cloudStyle = CloudMath.STYLE_VANILLA;
        Path f = dir.resolve("nine.json");
        before.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 9"));
        VisualsConfig read = VisualsConfig.read(f);
        assertEquals(CloudMath.STYLE_ANIME, read.cloudStyle);
        assertEquals(ColourPresets.ANIME, read.colourPreset);
        assertEquals(VisualsConfig.currentRevision(), read.revision);
        //Saved now with another style: Custom (the style was changed by hand after choosing Anime)
        before.revision = VisualsConfig.currentRevision();
        before.write(f);
        VisualsConfig now = VisualsConfig.read(f);
        assertEquals(CloudMath.STYLE_VANILLA, now.cloudStyle);
        assertEquals(ColourPresets.CUSTOM, now.colourPreset);
        //An older Horror file keeps the realistic clouds
        VisualsConfig horror = new VisualsConfig();
        ColourPresets.apply(horror, ColourPresets.HORROR);
        horror.cloudStyle = CloudMath.STYLE_REALISTIC;
        horror.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 9"));
        assertEquals(CloudMath.STYLE_REALISTIC, VisualsConfig.read(f).cloudStyle);
    }

    /** 2026-11-11: a revision 10 file still exactly on a palette's old look values takes the new ones; a changed one keeps its own. */
    @Test
    void anOlderFileOnAPalettesOldLookTakesTheNewOne(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("ten.json");
        VisualsConfig old = new VisualsConfig();
        ColourPresets.apply(old, ColourPresets.REALISTIC);
        old.fogStrength = 110;
        old.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 10"));
        VisualsConfig read = VisualsConfig.read(f);
        assertEquals(40, read.fogStrength);
        assertEquals(ColourPresets.REALISTIC, read.colourPreset);
        old.fogStrength = 90;
        old.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 10"));
        VisualsConfig kept = VisualsConfig.read(f);
        assertEquals(90, kept.fogStrength);
        assertEquals(ColourPresets.CUSTOM, kept.colourPreset);
    }

    /**
     * 2026-11-12: the palettes set the moonlight (the night's brightness) too: Horror's night is the darkest, Anime's the brightest. A file
     * saved before, on a palette with the moonlight still at its old default, takes the palette's and stays on it; a moonlight the player
     * changed is kept (the palette then shows Custom).
     */
    @Test
    void thePalettesSetTheMoonlight(@TempDir Path dir) throws Exception {
        assertTrue(java.util.List.of(ColourPresets.LOOK_KEYS).contains("moonlight"));
        int horror = ColourPresets.look("moonlight", ColourPresets.HORROR), def = ColourPresets.look("moonlight", ColourPresets.DEFAULT),
                anime = ColourPresets.look("moonlight", ColourPresets.ANIME);
        assertTrue(horror < def && def < anime);
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.HORROR);
        assertEquals(horror, c.moonlight);
        c.moonlight = 80;
        assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(c));
        Path f = dir.resolve("fifteen.json");
        for (int palette = 0; palette < ColourPresets.LEVELS; palette++) {
            VisualsConfig old = new VisualsConfig();
            ColourPresets.apply(old, palette);
            old.moonlight = ColourPresets.MOONLIGHT_BEFORE_16;
            old.write(f);
            Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 15"));
            VisualsConfig read = VisualsConfig.read(f);
            assertEquals(ColourPresets.look("moonlight", palette), read.moonlight, "palette " + palette);
            assertEquals(palette, read.colourPreset, "palette " + palette);
        }
        VisualsConfig tuned = new VisualsConfig();
        ColourPresets.apply(tuned, ColourPresets.ANIME);
        tuned.moonlight = 90;
        tuned.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 15"));
        VisualsConfig kept = VisualsConfig.read(f);
        assertEquals(90, kept.moonlight);
        assertEquals(ColourPresets.CUSTOM, kept.colourPreset);
        //an older file on Custom keeps its moonlight
        VisualsConfig custom = new VisualsConfig();
        custom.setColour(Colour.WATER, 0x123456);
        custom.colourPreset = ColourPresets.CUSTOM;
        custom.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 15"));
        assertEquals(ColourPresets.MOONLIGHT_BEFORE_16, VisualsConfig.read(f).moonlight);
    }

    /**
     * 2026-11-13: the palettes set the tone block too (exposure, tone mapping, contrast, warm sun / cool shade): Cinematic has the strongest
     * grade, Horror the hardest contrast and the darkest exposure. A file saved before (revision 17, or 15 with the moonlight too) on a
     * palette with those still at their defaults takes the palette's and stays on it; one the player changed is kept.
     */
    @Test
    void thePalettesSetTheToneBlock(@TempDir Path dir) throws Exception {
        String[] tone = {"exposure", "tone_strength", "contrast", "grade_strength"};
        for (String key : tone) assertTrue(java.util.List.of(ColourPresets.LOOK_KEYS).contains(key), key);
        assertTrue(ColourPresets.look("grade_strength", ColourPresets.CINEMATIC) > ColourPresets.look("grade_strength", ColourPresets.ANIME));
        assertTrue(ColourPresets.look("contrast", ColourPresets.HORROR) > ColourPresets.look("contrast", ColourPresets.CINEMATIC));
        assertTrue(ColourPresets.look("exposure", ColourPresets.HORROR) < ColourPresets.look("exposure", ColourPresets.DEFAULT));
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.CINEMATIC);
        assertEquals(ColourPresets.look("contrast", ColourPresets.CINEMATIC), c.contrast);
        c.gradeStrength = 10;
        assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(c));
        Path f = dir.resolve("old.json");
        for (int revision : new int[]{17, 15}) {
            for (int palette = 0; palette < ColourPresets.LEVELS; palette++) {
                VisualsConfig old = new VisualsConfig();
                ColourPresets.apply(old, palette);
                for (String key : tone) VisualsOptionLayout.set(old, VisualsOptionLayout.item(key), ColourPresets.TONE_BEFORE_18.get(key));
                if (revision < 16) old.moonlight = ColourPresets.MOONLIGHT_BEFORE_16;
                old.write(f);
                Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": " + revision));
                VisualsConfig read = VisualsConfig.read(f);
                for (String key : tone) {
                    assertEquals(ColourPresets.look(key, palette), VisualsOptionLayout.get(read, VisualsOptionLayout.item(key)), key + ", palette " + palette + ", revision " + revision);
                }
                assertEquals(ColourPresets.look("moonlight", palette), read.moonlight, "palette " + palette + ", revision " + revision);
                assertEquals(palette, read.colourPreset, "palette " + palette + ", revision " + revision);
            }
        }
        VisualsConfig tuned = new VisualsConfig();
        ColourPresets.apply(tuned, ColourPresets.HORROR);
        tuned.exposure = 100;
        tuned.contrast = 130;
        tuned.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 17"));
        VisualsConfig kept = VisualsConfig.read(f);
        assertEquals(130, kept.contrast);
        assertEquals(100, kept.exposure);
        assertEquals(ColourPresets.CUSTOM, kept.colourPreset);
    }

    /** Session 8 follow-up: the look block on the colours page holds the same settings as their own pages, with its label. */
    @Test
    void theColoursPageShowsThePalettesLookSettings() throws Exception {
        VisualsOptionLayout.Page colours = VisualsOptionLayout.PAGES.stream().filter(p -> p.key().equals("colours")).findFirst().orElseThrow();
        VisualsOptionLayout.Block look = colours.blocks().stream().filter(b -> b.key().equals(VisualsOptionLayout.COLOURS_LOOK)).findFirst().orElseThrow();
        assertEquals(java.util.List.of(ColourPresets.LOOK_KEYS), look.items().stream().map(VisualsOptionLayout.Item::key).toList());
        for (VisualsOptionLayout.Item item : look.items()) {
            assertEquals(VisualsOptionLayout.item(item.key()), item, "the same item as on its own page");
            int seen = 0;
            for (VisualsOptionLayout.Page p : VisualsOptionLayout.PAGES) {
                for (VisualsOptionLayout.Block b : p.blocks()) seen += (int) b.items().stream().filter(i -> i.key().equals(item.key())).count();
            }
            assertEquals(2, seen, item.key());
        }
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            String text = Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/" + lang + ".json"));
            assertTrue(text.contains("\"vulkanmod.visuals.block." + VisualsOptionLayout.COLOURS_LOOK + "\""), lang);
        }
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
