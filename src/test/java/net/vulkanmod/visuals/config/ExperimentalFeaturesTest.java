package net.vulkanmod.visuals.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.visuals.render.PostProcessSettings;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-16 (session 25 follow-up): the Experimental page (ExperimentalFeatures): its blocks and settings, their fields, and what they
 * change (the storm's particles: StormMath.Tuning through WeatherUniforms; the footprints' depth and duration: FootprintMath).
 */
class ExperimentalFeaturesTest {
    private static final float EPS = 1e-5f;

    @Test
    void thePageIsLastWithItsWeatherAndInteractionBlocks() {
        VisualsOptionLayout.Page page = VisualsOptionLayout.PAGES.get(VisualsOptionLayout.PAGES.size() - 1);
        assertEquals(ExperimentalFeatures.PAGE, page.key());
        assertEquals(List.of(ExperimentalFeatures.WEATHER, ExperimentalFeatures.INTERACTION, ExperimentalFeatures.SKY), page.blocks().stream().map(VisualsOptionLayout.Block::key).toList());
        assertEquals(List.of("sandstorm_grain_size", "sandstorm_grain_amount", "blizzard_flake_size", "blizzard_flake_amount"),
                page.blocks().get(0).items().stream().map(VisualsOptionLayout.Item::key).toList());
        assertEquals(List.of("footprint_depth", "footprint_duration", "water_wakes", "water_wake_strength", "lens_water", "lens_water_strength"), page.blocks().get(1).items().stream().map(VisualsOptionLayout.Item::key).toList());
        //2026-11-21: the night sky's events
        assertEquals(List.of("star_twinkle", "star_twinkle_strength", "meteors", "meteor_amount", "meteor_brightness"),
                page.blocks().get(2).items().stream().map(VisualsOptionLayout.Item::key).toList());
        //each greys out with the effect it tunes
        assertEquals("storm_weather", VisualsOptionLayout.item("sandstorm_grain_size").requires());
        assertEquals("footprints", VisualsOptionLayout.item("footprint_duration").requires());
        //every entry sits in a known block, once
        for (ExperimentalFeatures.Entry e : ExperimentalFeatures.ENTRIES) {
            assertTrue(ExperimentalFeatures.BLOCKS.contains(e.block()), e.item().key());
            assertEquals(1, ExperimentalFeatures.ENTRIES.stream().filter(o -> o.item().key().equals(e.item().key())).count());
        }
        //not in the performance presets: the levels leave them alone
        for (ExperimentalFeatures.Entry e : ExperimentalFeatures.ENTRIES) assertTrue(!VisualsPresets.VALUES.containsKey(e.item().key()), e.item().key());
    }

    @Test
    void eachSettingReadsAndWritesItsFieldAndDefaultsTo100() {
        for (ExperimentalFeatures.Entry e : ExperimentalFeatures.ENTRIES) {
            VisualsOptionLayout.Item item = VisualsOptionLayout.item(e.item().key());
            //session 26: the switches (wakes, lens water) default to on
            assertEquals(item.kind() == VisualsOptionLayout.Kind.SWITCH ? 1 : 100, VisualsOptionLayout.defaultValue(item), item.key());
            VisualsConfig c = new VisualsConfig();
            int v = Math.max(item.min(), Math.min(item.max(), 137));
            VisualsOptionLayout.set(c, item, v);
            assertEquals(v, e.get().applyAsInt(c), item.key());
            assertEquals(v, VisualsOptionLayout.get(c, item), item.key());
            //kept in range
            VisualsOptionLayout.set(c, item, 100000);
            assertEquals(item.max(), VisualsOptionLayout.get(c, item), item.key());
        }
        VisualsConfig c = new VisualsConfig();
        VisualsOptionLayout.set(c, VisualsOptionLayout.item("sandstorm_grain_amount"), 40);
        assertEquals(40, c.sandstormGrainAmount);
        VisualsOptionLayout.set(c, VisualsOptionLayout.item("footprint_duration"), 300);
        assertEquals(300, c.footprintDuration);
    }

    @Test
    void theSettingsSurviveASaveAndLoad(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        VisualsConfig c = new VisualsConfig();
        c.sandstormGrainSize = 50;
        c.blizzardFlakeAmount = 0;
        c.footprintDepth = 250;
        java.nio.file.Path file = dir.resolve("visuals.json");
        c.write(file);
        VisualsConfig back = VisualsConfig.read(file);
        assertEquals(50, back.sandstormGrainSize);
        assertEquals(0, back.blizzardFlakeAmount);
        assertEquals(250, back.footprintDepth);
        //a file from before the page: the defaults
        java.nio.file.Files.writeString(file, "{\"revision\": " + VisualsConfig.currentRevision() + "}");
        VisualsConfig old = VisualsConfig.read(file);
        assertEquals(100, old.sandstormGrainSize);
        assertEquals(100, old.footprintDuration);
    }

    @Test
    void thePageAndBlocksHaveNamesInBothLanguages() {
        for (String lang : List.of("en_us", "ko_kr")) {
            JsonObject json;
            try (var in = ExperimentalFeaturesTest.class.getResourceAsStream("/assets/vulkanmod/lang/" + lang + ".json")) {
                assertNotNull(in, lang);
                json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            assertTrue(json.has(PostProcessSettings.KEY_PREFIX + "page." + ExperimentalFeatures.PAGE), lang);
            for (String b : ExperimentalFeatures.BLOCKS) assertTrue(json.has(PostProcessSettings.KEY_PREFIX + "block." + b), lang + " " + b);
        }
    }
}
