package net.vulkanmod.visuals.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.vulkanmod.visuals.render.PostProcessSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VisualsConfigTest {
    private static final String SAVED_BY_CURRENT_MOD = """
            {"enabled": true, "exposure": 100, "toneStrength": 100, "contrast": 100, "saturation": 107, "gradeStrength": 0,
             "bloomStrength": 12, "vignetteStrength": 100, "fogStrength": 100, "fogHeight": 40, "sunGlow": 50, "sunsetLight": 50,
             "lightShafts": 50, "moonlight": 50, "aoStrength": 100, "sunShading": 100, "waterDepth": 100, "waterReflections": 100,
             "waterRipples": 100, "waterRippleSpeed": 100, "farClouds": true, "sunShadows": true, "vanillaShadows": true,
             "skyLightAmbient": 100, "perPixelLight": true, "lightColour": 70, "heldLight": true, "antiAliasing": 3, "edgeCover": true,
             "taa": true, "dhLods": true, "dhShadows": true, "preset": 5, "colourSunset": 16724838, "pbr": false, "pbrNormalStrength": 150,
             "pbrReflections": true, "pbrParallax": true, "colourSunlight": 16744512, "colourShadeLight": 8438015, "cloudStyle": 2, "sunSize": 70, "moonSize": 150, "snowType": 1, "snowSize": 150, "snowAmount": 60,
             "rainType": 2, "rainSize": 80, "rainAmount": 140, "starCount": 250, "starSize": 60, "starBrightness": 180, "revision": 14}
            """;

    private static List<Field> settingFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : VisualsConfig.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        }
        return out;
    }

    /** Each item once (session 8 follow-up: the colours page's look block repeats bloom, saturation and fog from their own pages). */
    private static List<VisualsOptionLayout.Item> items() {
        java.util.Map<String, VisualsOptionLayout.Item> out = new java.util.LinkedHashMap<>();
        for (var page : VisualsOptionLayout.PAGES) for (var block : page.blocks()) for (var item : block.items()) out.putIfAbsent(item.key(), item);
        return new ArrayList<>(out.values());
    }

    private static void assertSameValues(VisualsConfig a, VisualsConfig b) throws Exception {
        for (Field f : settingFields()) assertEquals(f.get(a), f.get(b), f.getName());
    }

    /** Final fix R3: the look the screen checks were tuned with (the player's settings); every other percentage is 100. */
    private static final java.util.Map<String, Integer> TUNED_DEFAULTS = java.util.Map.of("saturation", 94, "grade_strength", 0,
            "bloom_strength", 45, "sun_glow", 50, "sunset_light", 50, "light_shafts", 50, "moonlight", 50, "light_colour", 70,
            "block_light_colour", 70);

    @Test
    void percentagesDefaultToTheTunedLookAndSwitchesToOn() {
        VisualsConfig c = new VisualsConfig();
        int checked = 0;
        Set<String> tuned = new HashSet<>();
        for (var item : items()) {
            if (item.kind() == VisualsOptionLayout.Kind.PERCENT) {
                assertEquals(TUNED_DEFAULTS.getOrDefault(item.key(), 100), VisualsOptionLayout.get(c, item), item.key());
                if (TUNED_DEFAULTS.containsKey(item.key())) tuned.add(item.key());
                checked++;
            } else if (item.kind() == VisualsOptionLayout.Kind.SWITCH) {
                //FG design 7 / FSR design 7: the two frame generation switches are the ones that start off; session 9: and the parallax
                assertEquals(item.key().equals("frame_generation") || item.key().equals("fsr_frame_generation") || item.key().equals("pbr_parallax") ? 0 : 1,
                        VisualsOptionLayout.get(c, item), item.key());
                checked++;
            }
        }
        assertTrue(checked > 20);
        assertEquals(TUNED_DEFAULTS.keySet(), tuned, "every tuned default is a settings item");
        assertEquals(40, c.fogHeight, "the tuned fog height");
        assertEquals(100, c.aoStrength);
        assertEquals(VisualsConfig.currentRevision(), c.revision);
        assertTrue(c.revision >= 5);
        assertEquals(0, c.lightDebug);
        assertTrue(c.fogHeight >= PostProcessSettings.FOG_HEIGHT_MIN && c.fogHeight <= PostProcessSettings.FOG_HEIGHT_MAX);
        assertTrue(c.antiAliasing >= 0 && c.antiAliasing <= PostProcessSettings.ANTI_ALIASING_MAX);
    }

    @Test
    void outOfRangeValuesAreClamped() {
        VisualsConfig c = new VisualsConfig();
        c.exposure = 10;
        assertEquals(PostProcessSettings.EXPOSURE_MIN / 100f, PostProcessSettings.from(c).exposure());
        c.exposure = 900;
        assertEquals(PostProcessSettings.EXPOSURE_MAX / 100f, PostProcessSettings.from(c).exposure());
        c.fogHeight = -5;
        assertEquals(PostProcessSettings.FOG_HEIGHT_MIN, PostProcessSettings.from(c).fogHeight());
        c.fogHeight = 100000;
        assertEquals(PostProcessSettings.FOG_HEIGHT_MAX, PostProcessSettings.from(c).fogHeight());
        c.antiAliasing = -3;
        assertEquals(0, PostProcessSettings.from(c).antiAliasing());
        c.antiAliasing = 77;
        assertEquals(PostProcessSettings.ANTI_ALIASING_MAX, PostProcessSettings.from(c).antiAliasing());
    }

    @Test
    void aFileSavedByTheCurrentModReadsBackUnchanged(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in.json");
        Files.writeString(in, SAVED_BY_CURRENT_MOD);
        VisualsConfig c = VisualsConfig.read(in);
        JsonObject expected = new Gson().fromJson(SAVED_BY_CURRENT_MOD, JsonObject.class);
        int compared = 0;
        for (Field f : settingFields()) {
            if (!expected.has(f.getName())) continue;
            var e = expected.get(f.getName()).getAsJsonPrimitive();
            Object v = f.get(c);
            if (e.isBoolean()) assertEquals(e.getAsBoolean(), v, f.getName());
            else assertEquals(e.getAsInt(), v, f.getName());
            compared++;
        }
        assertEquals(expected.size(), compared);
        Path out = dir.resolve("out.json");
        c.write(out);
        VisualsConfig again = VisualsConfig.read(out);
        assertSameValues(c, again);
        again.write(out);
        assertSameValues(c, VisualsConfig.read(out));
        JsonObject written = new Gson().fromJson(Files.readString(out), JsonObject.class);
        for (String key : expected.keySet()) assertTrue(written.has(key), key);
    }

    @Test
    void missingPartialAndUnreadableFiles(@TempDir Path dir) throws Exception {
        VisualsConfig defaults = new VisualsConfig();
        VisualsConfig missing = VisualsConfig.read(dir.resolve("none.json"));
        assertSameValues(defaults, missing);
        assertEquals(VisualsConfig.currentRevision(), missing.revision);

        Path partial = dir.resolve("partial.json");
        Files.writeString(partial, "{\"exposure\": 150}");
        VisualsConfig p = VisualsConfig.read(partial);
        assertEquals(150, p.exposure);
        assertEquals(VisualsConfig.currentRevision(), p.revision);
        p.exposure = defaults.exposure;
        //session 4: a file without the preset field is Custom (VisualsPresetsTest)
        assertEquals(VisualsPresets.CUSTOM, p.preset);
        p.preset = defaults.preset;
        assertSameValues(defaults, p);

        Path junk = dir.resolve("junk.json");
        Files.writeString(junk, "this is not json {{{");
        assertSameValues(defaults, VisualsConfig.read(junk));
        Files.writeString(junk, "");
        assertSameValues(defaults, VisualsConfig.read(junk));
        Files.writeString(junk, "[1,2,3]");
        assertSameValues(defaults, VisualsConfig.read(junk));
        Files.write(junk, new byte[]{(byte) 0xff, (byte) 0xfe, 0, 1});
        assertSameValues(defaults, VisualsConfig.read(junk));
        Files.writeString(junk, "{\"exposure\": \"loud\"}");
        VisualsConfig loud = VisualsConfig.read(junk);
        assertEquals(VisualsPresets.CUSTOM, loud.preset, "an object without the preset field is Custom");
        loud.preset = defaults.preset;
        assertSameValues(defaults, loud);
    }

    @Test
    void anOlderRevisionIsUpgradedWithoutChangingPlayerValues(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old.json");
        Files.writeString(old, "{\"exposure\": 130, \"bloomStrength\": 0, \"taa\": false, \"revision\": 1}");
        VisualsConfig c = VisualsConfig.read(old);
        assertEquals(VisualsConfig.currentRevision(), c.revision);
        assertEquals(130, c.exposure);
        assertEquals(0, c.bloomStrength);
        assertFalse(c.taa);
        assertEquals(VisualsPresets.CUSTOM, c.preset, "session 4: a file before the preset is Custom");
        Files.writeString(old, "{\"exposure\": 130, \"revision\": 0}");
        assertEquals(VisualsConfig.currentRevision(), VisualsConfig.read(old).revision);
        Files.writeString(old, SAVED_BY_CURRENT_MOD.replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 99"));
        assertEquals(99, VisualsConfig.read(old).revision, "a newer file is left alone");
    }

    @Test
    void writeThenReadIsEqualAndLightDebugIsNotWritten(@TempDir Path dir) throws Exception {
        VisualsConfig c = new VisualsConfig();
        c.exposure = 123;
        c.farClouds = false;
        c.fogHeight = 77;
        c.antiAliasing = 4;
        //session 4: far clouds and anti-aliasing are preset settings, so these values are Custom (a High left in the file reads back Custom)
        c.preset = VisualsPresets.CUSTOM;
        c.lightDebug = 3;
        Path file = dir.resolve("sub/dir/c.json");
        c.write(file);
        assertFalse(Files.readString(file).contains("lightDebug"));
        VisualsConfig back = VisualsConfig.read(file);
        assertEquals(0, back.lightDebug);
        c.lightDebug = 0;
        assertSameValues(c, back);
        //2026-10-30 (audit): saved through a temporary file moved over the old one, which leaves nothing behind
        c.exposure = 140;
        c.write(file);
        assertEquals(140, VisualsConfig.read(file).exposure);
        assertFalse(Files.exists(file.resolveSibling("c.json.tmp")));
    }

    @Test
    void theOptionLayoutCoversEverySettingFieldOnce() throws Exception {
        Set<String> covered = new HashSet<>();
        for (var item : items()) {
            VisualsConfig base = new VisualsConfig();
            VisualsConfig changed = new VisualsConfig();
            int target = VisualsOptionLayout.get(base, item) == item.max() ? item.min() : item.max();
            VisualsOptionLayout.set(changed, item, target);
            List<String> diff = new ArrayList<>();
            for (Field f : settingFields()) if (!f.get(base).equals(f.get(changed))) diff.add(f.getName());
            assertEquals(1, diff.size(), item.key() + " changes " + diff);
            assertTrue(covered.add(diff.get(0)), "field behind two items: " + diff.get(0));
        }
        Set<String> rest = new HashSet<>();
        for (Field f : settingFields()) rest.add(f.getName());
        rest.removeAll(covered);
        // W10 added aoStrength's item; revision is bookkeeping; the frame generation factor is not on the page in phase 1.
        assertEquals(Set.of("revision", "frameGenerationFactor"), rest);
    }

    /** FG design 7: the frame generation switch sits after TAA, off by default, with the factor at 2. */
    @Test
    void frameGenerationIsAnOffByDefaultToggle() {
        VisualsOptionLayout.Item fg = null, previous = null, beforeFg = null;
        for (var item : items()) {
            if (item.key().equals("frame_generation")) {
                fg = item;
                beforeFg = previous;
            }
            previous = item;
        }
        assertNotNull(fg);
        assertEquals("taa", beforeFg.key());
        assertEquals(VisualsOptionLayout.Kind.SWITCH, fg.kind());
        assertEquals(VisualsOptionLayout.Impact.LOW, fg.impact());
        assertNull(fg.requires());
        VisualsConfig c = new VisualsConfig();
        assertFalse(c.frameGeneration);
        assertEquals(2, c.frameGenerationFactor);
        assertEquals(0, VisualsOptionLayout.get(c, fg));
        VisualsOptionLayout.set(c, fg, 1);
        assertTrue(c.frameGeneration);
        assertEquals(1, VisualsOptionLayout.get(c, fg));
        assertEquals(0, VisualsConfig.fgView);
        assertFalse(VisualsConfig.fgForcePause);
        //frame generation does not depend on the post-process: its switch is not greyed out with the master switch
        assertFalse(VisualsOptionLayout.followsMasterSwitch(fg));
        for (var item : items()) {
            boolean exempt = item.key().equals(VisualsOptionLayout.ENABLED) || item.key().equals("frame_generation")
                    || item.key().equals("fsr_frame_generation");
            assertEquals(!exempt, VisualsOptionLayout.followsMasterSwitch(item), item.key());
        }
    }

    /** W10: the ambient occlusion's strength has exactly one item, a percentage 0..100 behind aoStrength. */
    @Test
    void theAmbientOcclusionItemExistsOnce() {
        List<VisualsOptionLayout.Item> ao = new ArrayList<>();
        for (var item : items()) if (item.key().equals("ao_strength")) ao.add(item);
        assertEquals(1, ao.size());
        assertEquals(VisualsOptionLayout.Kind.PERCENT, ao.get(0).kind());
        assertEquals(0, ao.get(0).min());
        assertEquals(100, ao.get(0).max());
        VisualsConfig c = new VisualsConfig();
        VisualsOptionLayout.set(c, ao.get(0), 30);
        assertEquals(30, c.aoStrength);
        assertEquals(30, VisualsOptionLayout.get(c, ao.get(0)));
    }

    @Test
    void everyItemHasALabelAndTooltipInBothLanguages() throws Exception {
        for (String lang : List.of("en_us", "ko_kr")) {
            String path = "/assets/vulkanmod/lang/" + lang + ".json";
            JsonObject json;
            try (InputStream in = VisualsConfigTest.class.getResourceAsStream(path)) {
                assertNotNull(in, "missing language file " + path);
                json = new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            }
            for (var item : items()) {
                String key = PostProcessSettings.KEY_PREFIX + item.key();
                assertTrue(json.has(key) && !json.get(key).getAsString().isBlank(), lang + " label " + key);
                assertTrue(json.has(key + ".tooltip") && !json.get(key + ".tooltip").getAsString().isBlank(), lang + " tooltip " + key);
            }
        }
    }
}
