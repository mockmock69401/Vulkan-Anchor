package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.vulkanmod.visuals.config.VisualsPresets.*;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-10 (shader roadmap session 4): the performance presets, the Custom rule and the revision-6 migration. */
class VisualsPresetsTest {
    private static List<Field> settingFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : VisualsConfig.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers())) out.add(f);
        }
        return out;
    }

    private static List<String> presetFields() {
        List<String> out = new ArrayList<>();
        for (String key : VALUES.keySet()) {
            VisualsConfig a = new VisualsConfig(), b = new VisualsConfig();
            VisualsOptionLayout.Item item = VisualsOptionLayout.item(key);
            VisualsOptionLayout.set(b, item, VisualsOptionLayout.get(a, item) == item.max() ? item.min() : item.max());
            for (Field f : settingFields()) {
                try {
                    if (!f.get(a).equals(f.get(b))) out.add(f.getName());
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return out;
    }

    /** A config far from every level in the look-only settings and the preset ones. */
    private static VisualsConfig odd() {
        VisualsConfig c = new VisualsConfig();
        c.exposure = 130;
        c.celestialShape = 1;
        c.waterRippleDensity = 180;
        c.cloudSize = 60;
        c.cloudCount = 150;
        c.auroraStrength = 170;
        c.weatherDensity = 40;
        c.antiAliasing = 3;
        c.shadowDistance = 7;
        c.taa = false;
        c.preset = CUSTOM;
        return c;
    }

    @Test
    void aFreshConfigIsTheDefaultLevelAndItsValuesAreTheDefaults() {
        VisualsConfig c = new VisualsConfig();
        assertEquals(DEFAULT, c.preset);
        assertEquals(HIGH, DEFAULT);
        assertTrue(matches(c, DEFAULT), "the High level's values are VisualsConfig's defaults");
        assertEquals(DEFAULT, detect(c));
        assertEquals(DEFAULT, validated(c));
    }

    @Test
    void everyPresetSettingIsAnItemWithFiveValuesInRange() {
        assertFalse(VALUES.isEmpty());
        for (Map.Entry<String, int[]> e : VALUES.entrySet()) {
            VisualsOptionLayout.Item item = VisualsOptionLayout.item(e.getKey());
            assertEquals(LEVELS, e.getValue().length, e.getKey());
            for (int v : e.getValue()) assertTrue(v >= item.min() && v <= item.max(), e.getKey() + " " + v);
        }
        //one field behind each, none of them the preset itself
        List<String> fields = presetFields();
        assertEquals(VALUES.size(), fields.size(), fields.toString());
        assertEquals(VALUES.size(), new java.util.HashSet<>(fields).size(), fields.toString());
        assertFalse(fields.contains("preset"));
    }

    /** A higher level never costs less: each setting's value only grows from Potato to Ultra (all of them are on/off or more-is-dearer). */
    @Test
    void aHigherLevelNeverTurnsACostDown() {
        for (Map.Entry<String, int[]> e : VALUES.entrySet()) {
            for (int level = 1; level < LEVELS; level++) {
                assertTrue(e.getValue()[level] >= e.getValue()[level - 1], e.getKey() + " at level " + level);
            }
        }
    }

    @Test
    void theLevelsAreDistinct() {
        for (int a = 0; a < LEVELS; a++) {
            for (int b = a + 1; b < LEVELS; b++) {
                boolean differ = false;
                for (int[] v : VALUES.values()) differ |= v[a] != v[b];
                assertTrue(differ, a + " and " + b);
            }
        }
    }

    /** Applying a level sets exactly the preset settings (and the preset); the look-only settings keep the player's values. */
    @Test
    void applyingEachLevelSetsItsValuesAndNothingElse() throws Exception {
        List<String> touched = presetFields();
        for (int level = POTATO; level < LEVELS; level++) {
            VisualsConfig before = odd(), c = odd();
            apply(c, level);
            assertEquals(level, c.preset);
            for (String key : VALUES.keySet()) {
                assertEquals(value(key, level), VisualsOptionLayout.get(c, VisualsOptionLayout.item(key)), key + " at " + level);
            }
            for (Field f : settingFields()) {
                if (touched.contains(f.getName()) || f.getName().equals("preset")) continue;
                assertEquals(f.get(before), f.get(c), f.getName() + " at " + level);
            }
            assertEquals(level, detect(c));
            assertEquals(level, validated(c));
            //the same through the page's item (VisualsOptionLayout.set), as the screen's Apply writes it
            VisualsConfig viaItem = odd();
            VisualsOptionLayout.set(viaItem, VisualsOptionLayout.item(VisualsOptionLayout.PRESET), level);
            for (Field f : settingFields()) assertEquals(f.get(c), f.get(viaItem), f.getName() + " at " + level);
        }
    }

    @Test
    void choosingCustomKeepsTheSettings() throws Exception {
        VisualsConfig c = new VisualsConfig(), before = new VisualsConfig();
        apply(c, CUSTOM);
        assertEquals(CUSTOM, c.preset);
        for (Field f : settingFields()) if (!f.getName().equals("preset")) assertEquals(f.get(before), f.get(c), f.getName());
        assertEquals(CUSTOM, validated(c), "a chosen Custom stays Custom though the values are High's");
    }

    /** The Custom rule: changing any preset setting to another value leaves no level matching; a look-only setting does not count. */
    @Test
    void changingAnyPresetSettingMakesItCustom() {
        for (int level = POTATO; level < LEVELS; level++) {
            for (String key : VALUES.keySet()) {
                VisualsOptionLayout.Item item = VisualsOptionLayout.item(key);
                for (int v = item.min(); v <= item.max(); v++) {
                    if (v == value(key, level)) continue;
                    VisualsConfig c = new VisualsConfig();
                    apply(c, level);
                    VisualsOptionLayout.set(c, item, v);
                    assertFalse(matches(c, level), key + "=" + v + " at " + level);
                    assertEquals(CUSTOM, validated(c), key + "=" + v + " at " + level);
                    int seen = detect(c);
                    assertTrue(seen == CUSTOM || seen != level, key);
                }
            }
            VisualsConfig c = new VisualsConfig();
            apply(c, level);
            c.celestialShape = 1;
            c.waterRippleDensity = 25;
            c.cloudCount = 0;
            c.auroraStrength = 200;
            c.weatherDensity = 200;
            c.exposure = 150;
            assertEquals(level, validated(c), "look-only settings keep the level");
            assertEquals(level, detect(c));
        }
    }

    /** The page hides the Distant Horizons block without DH: a setting it does not have is not compared. */
    @Test
    void aHiddenSettingIsNotCompared() {
        for (int level = POTATO; level < LEVELS; level++) {
            int l = level;
            assertEquals(level, detect(key -> key.equals("dh_shadows") ? Integer.MIN_VALUE : value(key, l)));
            assertEquals(CUSTOM, detect(key -> key.equals("dh_shadows") ? Integer.MIN_VALUE : key.equals("anti_aliasing") ? -1 : value(key, l)));
        }
    }

    @Test
    void thePresetIsTheFirstItemOfTheFirstPage() {
        VisualsOptionLayout.Item first = VisualsOptionLayout.PAGES.get(0).blocks().get(0).items().get(0);
        assertEquals(VisualsOptionLayout.PRESET, first.key());
        assertEquals(VisualsOptionLayout.GENERAL, VisualsOptionLayout.PAGES.get(0).blocks().get(0).key());
        assertEquals(VisualsOptionLayout.Kind.CHOICE, first.kind());
        assertEquals(POTATO, first.min());
        assertEquals(CUSTOM, first.max());
        assertEquals(DEFAULT, VisualsOptionLayout.defaultValue(first));
    }

    /** The page links the preset to its settings (VisualsSettingsPage.linkPreset) and shows the reset in the preset's block. */
    @Test
    void thePageLinksThePreset() throws Exception {
        List<String> calls = Bytecode.calls("net/vulkanmod/visuals/config/VisualsSettingsPage", "pages");
        assertTrue(calls.contains("VisualsSettingsPage.linkPreset"), calls.toString());
        List<String> link = Bytecode.calls("net/vulkanmod/visuals/config/VisualsSettingsPage", "linkPreset");
        assertTrue(link.toString().contains("VisualsPresets"), link.toString());
    }

    // --- saving and the revision-6 migration ---

    private static VisualsConfig readJson(Path dir, String json) throws Exception {
        Path f = dir.resolve("c.json");
        Files.writeString(f, json);
        return VisualsConfig.read(f);
    }

    @Test
    void aFileOlderThanThePresetLoadsAsCustomWithItsValues(@TempDir Path dir) throws Exception {
        VisualsConfig c = readJson(dir, "{\"antiAliasing\": 2, \"taa\": true, \"exposure\": 120, \"revision\": 5}");
        assertEquals(CUSTOM, c.preset, "existing players' settings are Custom though they equal High's");
        assertTrue(matches(c, HIGH));
        assertEquals(120, c.exposure);
        assertEquals(VisualsConfig.currentRevision(), c.revision);
        assertTrue(VisualsConfig.currentRevision() >= 6);
        //a revision-6 file without the preset field is Custom too
        assertEquals(CUSTOM, readJson(dir, "{\"exposure\": 120, \"revision\": 6}").preset);
        //written back, the Custom stays
        Path out = dir.resolve("out.json");
        c.write(out);
        assertTrue(Files.readString(out).contains("\"preset\": " + CUSTOM));
        assertEquals(CUSTOM, VisualsConfig.read(out).preset);
    }

    @Test
    void aSavedLevelLoadsAsThatLevelWhileItsValuesHold(@TempDir Path dir) throws Exception {
        for (int level = POTATO; level < LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            apply(c, level);
            Path f = dir.resolve("l" + level + ".json");
            c.write(f);
            VisualsConfig back = VisualsConfig.read(f);
            assertEquals(level, back.preset);
            assertTrue(matches(back, level));
        }
        //a hand-edited value, or a preset out of range, is Custom
        assertEquals(CUSTOM, readJson(dir, "{\"preset\": 3, \"antiAliasing\": 4, \"revision\": 6}").preset);
        assertEquals(CUSTOM, readJson(dir, "{\"preset\": 99, \"revision\": 6}").preset);
        assertEquals(CUSTOM, readJson(dir, "{\"preset\": -1, \"revision\": 6}").preset);
        assertEquals(HIGH, readJson(dir, "{\"preset\": 3, \"exposure\": 140, \"revision\": 6}").preset, "a look-only value keeps the level");
        assertEquals(HIGH, VisualsConfig.read(dir.resolve("missing.json")).preset, "a new player starts at High");
    }
}
