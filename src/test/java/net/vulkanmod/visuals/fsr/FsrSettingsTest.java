package net.vulkanmod.visuals.fsr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.fsr.FsrSettings.Active;
import net.vulkanmod.visuals.fsr.FsrSupport.Reason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FSR design 7: which frame generation runs, their exclusion, and the texts of the greyed-out reasons. */
class FsrSettingsTest {
    @Test void bothSavedOnRunsOne() {
        assertEquals(Active.FSR, FsrSettings.active(true, true, Reason.OK));
        assertEquals(Active.OPTICAL_FLOW, FsrSettings.active(true, true, Reason.DLL));
        assertEquals(Active.OPTICAL_FLOW, FsrSettings.active(true, false, Reason.OK));
        assertEquals(Active.FSR, FsrSettings.active(false, true, Reason.OK));
        assertEquals(Active.NONE, FsrSettings.active(false, false, Reason.OK));
    }

    @Test void fsrAloneUnavailableRunsNothing() {
        assertEquals(Active.NONE, FsrSettings.active(false, true, Reason.QUEUES));
    }

    @Test void turningOneOnTurnsTheOtherOff() {
        VisualsConfig c = new VisualsConfig();
        FsrSettings.onToggle(c, VisualsOptionLayout.FRAME_GENERATION, true);
        assertTrue(c.frameGeneration);
        assertFalse(c.fsrFrameGeneration);
        FsrSettings.onToggle(c, VisualsOptionLayout.FSR_FRAME_GENERATION, true);
        assertTrue(c.fsrFrameGeneration);
        assertFalse(c.frameGeneration);
        FsrSettings.onToggle(c, VisualsOptionLayout.FSR_FRAME_GENERATION, false);
        assertFalse(c.fsrFrameGeneration);
        assertFalse(c.frameGeneration);
        FsrSettings.onToggle(c, VisualsOptionLayout.FRAME_GENERATION, true);
        VisualsOptionLayout.set(c, itemFor(VisualsOptionLayout.FSR_FRAME_GENERATION), 1);
        assertTrue(c.fsrFrameGeneration);
        assertFalse(c.frameGeneration);
    }

    @Test void turningAGeneratorOnLimitsTheFrameRateOnce() {
        assertEquals(60, FsrSettings.FRAME_LIMIT_ON);
        assertTrue(FsrSettings.limitsFrameRate(VisualsOptionLayout.FRAME_GENERATION, false, true));
        assertTrue(FsrSettings.limitsFrameRate(VisualsOptionLayout.FSR_FRAME_GENERATION, false, true));
        //already on (an Apply of other options), turning off, and other switches leave the user's limit alone
        assertFalse(FsrSettings.limitsFrameRate(VisualsOptionLayout.FSR_FRAME_GENERATION, true, true));
        assertFalse(FsrSettings.limitsFrameRate(VisualsOptionLayout.FRAME_GENERATION, true, false));
        assertFalse(FsrSettings.limitsFrameRate("taa", false, true));
    }

    @Test void theGeneratorBlockHasItsWarningInBothLanguages() throws IOException {
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            JsonObject json = lang(lang);
            String key = "vulkanmod.visuals.block." + VisualsOptionLayout.FRAME_GENERATION_BLOCK;
            assertTrue(json.has(key), lang + " " + key);
            assertTrue(json.has(key + ".note") && json.get(key + ".note").getAsString().contains("60"), lang + " " + key + ".note");
        }
    }

    @Test void everyReasonHasLangKeys() throws IOException {
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            JsonObject json = lang(lang);
            assertTrue(json.has("vulkanmod.visuals.fsr_frame_generation"), lang);
            assertTrue(json.has("vulkanmod.visuals.fsr_frame_generation.tooltip"), lang);
            for (Reason r : Reason.values()) {
                if (r == Reason.OK) continue;
                assertTrue(json.has(FsrSettings.unavailableKey(r)), lang + " " + r);
            }
        }
    }

    private static VisualsOptionLayout.Item itemFor(String key) {
        return VisualsOptionLayout.PAGES.stream().flatMap(p -> p.blocks().stream()).flatMap(b -> b.items().stream())
                .filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    private static JsonObject lang(String name) throws IOException {
        try (InputStream in = FsrSettingsTest.class.getResourceAsStream("/assets/vulkanmod/lang/" + name + ".json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
