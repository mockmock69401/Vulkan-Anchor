package net.vulkanmod.render.text;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-30: the text anti-aliasing option (on by default, kept when the settings file predates it, labelled in en and ko). */
class TextAntialiasOptionTest {
    private static final Path LANG = Path.of("src/main/resources/assets/vulkanmod/lang");

    @Test
    void defaultsOn() {
        assertTrue(new Config().textAntialiasing);
    }

    @Test
    void missingFieldKeepsDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vulkanmod_settings.json");
        Files.writeString(file, "{\"fpsOverlay\": 1}");
        Config c = Config.load(file);
        assertEquals(1, c.fpsOverlay);
        assertTrue(c.textAntialiasing);
    }

    @Test
    void bothLanguagesHaveTheLabels() throws Exception {
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            JsonObject json = JsonParser.parseString(Files.readString(LANG.resolve(lang + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(json.has("vulkanmod.options.textAntialiasing"), lang);
            assertTrue(json.has("vulkanmod.options.textAntialiasing.tooltip"), lang);
        }
        JsonObject ko = JsonParser.parseString(Files.readString(LANG.resolve("ko_kr.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("텍스트 안티앨리어싱", ko.get("vulkanmod.options.textAntialiasing").getAsString());
    }
}
