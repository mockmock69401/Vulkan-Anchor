package net.vulkanmod.visuals.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.visuals.render.ParticleStyles;
import net.vulkanmod.visuals.render.PostProcessSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-11: the particle effects' shared option pattern (ParticleEffect): fields, rows, strings, defaults, presets and the old density. */
class ParticleEffectTest {
    private static JsonObject lang(String name) {
        return JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(ParticleEffectTest.class.getResourceAsStream(
                "/assets/vulkanmod/lang/" + name + ".json")), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void everyEffectHasItsFieldsRowsAndStrings() {
        VisualsConfig c = new VisualsConfig();
        JsonObject en = lang("en_us"), ko = lang("ko_kr");
        for (ParticleEffect e : ParticleEffect.values()) {
            assertEquals(0, e.get(c, ParticleEffect.Part.TYPE), e + ": round, the look before the setting");
            assertEquals(ParticleEffect.DEFAULT_PERCENT, e.get(c, ParticleEffect.Part.SIZE));
            assertEquals(ParticleEffect.DEFAULT_PERCENT, e.get(c, ParticleEffect.Part.AMOUNT));
            for (ParticleEffect.Part p : ParticleEffect.Part.values()) {
                VisualsOptionLayout.Item item = VisualsOptionLayout.item(e.itemKey(p));
                assertEquals(e.requires(), item.requires());
                assertEquals(new ParticleEffect.Target(e, p), ParticleEffect.byItemKey(item.key()));
                //Through the layout as any setting, clamped to its range
                VisualsOptionLayout.set(c, item, 9999);
                assertEquals(item.max(), VisualsOptionLayout.get(c, item));
                assertEquals(item.max(), e.get(c, p));
                VisualsOptionLayout.set(c, item, VisualsOptionLayout.defaultValue(item));
                for (JsonObject l : new JsonObject[]{en, ko}) {
                    assertTrue(l.has(PostProcessSettings.KEY_PREFIX + item.key()), item.key());
                    assertTrue(l.has(PostProcessSettings.KEY_PREFIX + item.key() + ".tooltip"), item.key());
                }
                assertTrue(PostProcessSettings.OPTION_NAMES.contains(item.key()), item.key());
            }
            VisualsOptionLayout.Item type = VisualsOptionLayout.item(e.itemKey(ParticleEffect.Part.TYPE));
            assertEquals(VisualsOptionLayout.Kind.CHOICE, type.kind());
            for (int t = 0; t <= e.typeMax(); t++) {
                assertTrue(en.has(PostProcessSettings.KEY_PREFIX + type.key() + "." + t) && ko.has(PostProcessSettings.KEY_PREFIX + type.key() + "." + t));
            }
            VisualsOptionLayout.Item size = VisualsOptionLayout.item(e.itemKey(ParticleEffect.Part.SIZE));
            assertEquals(ParticleEffect.SIZE_MIN, size.min());
            assertEquals(ParticleEffect.SIZE_MAX, size.max());
        }
        assertNull(ParticleEffect.byItemKey("snow"));
        assertNull(ParticleEffect.byItemKey("cloud_size"));
        //The old single density is gone from the page
        assertThrows(IllegalArgumentException.class, () -> VisualsOptionLayout.item("weather_density"));
        assertTrue(en.has(PostProcessSettings.KEY_PREFIX + "block." + VisualsOptionLayout.WEATHER_BLOCK));
    }

    @Test
    void thePresetsAndPalettesNeverSetThem() {
        for (ParticleEffect e : ParticleEffect.values()) {
            for (ParticleEffect.Part p : ParticleEffect.Part.values()) {
                assertFalse(VisualsPresets.VALUES.containsKey(e.itemKey(p)), e.itemKey(p));
                assertFalse(Arrays.asList(ColourPresets.LOOK_KEYS).contains(e.itemKey(p)), e.itemKey(p));
            }
        }
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            c.snowType = 1;
            c.snowSize = 220;
            c.rainAmount = 30;
            VisualsPresets.apply(c, level);
            ColourPresets.apply(c, ColourPresets.HORROR);
            assertEquals(1, c.snowType);
            assertEquals(220, c.snowSize);
            assertEquals(30, c.rainAmount);
            assertEquals(level, VisualsPresets.validated(c), "a look setting keeps the level");
        }
    }

    @Test
    void anOlderFilesDensityBecomesBothAmounts(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old.json");
        Files.writeString(old, "{\"weatherDensity\": 40, \"shaderWeather\": true, \"revision\": 12}");
        VisualsConfig c = VisualsConfig.read(old);
        assertEquals(40, c.snowAmount);
        assertEquals(40, c.rainAmount);
        assertEquals(0, c.snowType);
        assertEquals(100, c.rainSize);
        assertEquals(VisualsConfig.currentRevision(), c.revision);
        //A file without the field keeps the defaults; one of this revision never reads it
        Files.writeString(old, "{\"revision\": 12}");
        assertEquals(100, VisualsConfig.read(old).snowAmount);
        Files.writeString(old, "{\"weatherDensity\": 40, \"snowAmount\": 150, \"rainAmount\": 70, \"revision\": 13}");
        VisualsConfig now = VisualsConfig.read(old);
        assertEquals(150, now.snowAmount);
        assertEquals(70, now.rainAmount);
        //A broken old value keeps the defaults
        Files.writeString(old, "{\"weatherDensity\": \"lots\", \"revision\": 12}");
        assertEquals(100, VisualsConfig.read(old).rainAmount);
    }

    @Test
    void theShadersTakeThemClampedAndTheDefaultsAreTheDefaultObject() {
        assertSame(ParticleStyles.DEFAULT, ParticleStyles.of(new VisualsConfig()));
        assertSame(ParticleStyles.DEFAULT, PostProcessSettings.from(new VisualsConfig()).particles());
        VisualsConfig c = new VisualsConfig();
        c.snowType = 7;
        c.snowSize = 1;
        c.rainAmount = 900;
        c.rainSize = 150;
        ParticleStyles s = ParticleStyles.of(c);
        assertEquals(ParticleEffect.SNOW.typeMax(), s.type(ParticleEffect.SNOW));
        assertEquals(ParticleEffect.SIZE_MIN / 100f, s.size(ParticleEffect.SNOW), 1e-6f);
        assertEquals(ParticleEffect.AMOUNT_MAX / 100f, s.amount(ParticleEffect.RAIN), 1e-6f);
        assertEquals(1.5f, s.size(ParticleEffect.RAIN), 1e-6f);
        assertEquals(s, ParticleStyles.of(c), "equal values, equal objects (the post-process compares its settings)");
        assertEquals(s.hashCode(), ParticleStyles.of(c).hashCode());
        assertNotEquals(ParticleStyles.DEFAULT, s);
    }
}
