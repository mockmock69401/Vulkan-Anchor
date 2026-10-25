package net.vulkanmod.visuals.dh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.visuals.api.ShadowCascadeContext;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.config.VisualsOptionLayout.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.visuals.render.PostProcessSettings.KEY_PREFIX;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 6: the DH block of the visuals settings (dhLods, dhShadows), what each switch gates, and the reflection-failure dev switch. */
class DhSettingsTest {
    @BeforeEach
    @AfterEach
    void reset() {
        DhInternals.resetForTest();
        System.clearProperty(DhInternals.FORCE_REFLECT_FAIL);
    }

    private static List<Item> dhItems() {
        for (VisualsOptionLayout.Page page : VisualsOptionLayout.PAGES) {
            for (VisualsOptionLayout.Block block : page.blocks()) {
                if (block.key().equals("dh")) return block.items();
            }
        }
        throw new AssertionError("no dh block");
    }

    private static JsonObject lang(String name) throws Exception {
        try (InputStream in = DhSettingsTest.class.getClassLoader().getResourceAsStream("assets/vulkanmod/lang/" + name + ".json")) {
            assertNotNull(in, name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void bothSwitchesAreOnByDefault() {
        VisualsConfig c = new VisualsConfig();
        assertTrue(c.dhLods);
        assertTrue(c.dhShadows);
    }

    @Test
    void theDhBlockHoldsTheTwoSwitchesAndShadowsNeedLods() {
        List<Item> items = dhItems();
        assertEquals(List.of("dh_lods", "dh_shadows"), items.stream().map(Item::key).toList());
        assertEquals("dh_lods", items.get(1).requires());
        VisualsConfig c = new VisualsConfig();
        VisualsOptionLayout.set(c, items.get(0), 0);
        VisualsOptionLayout.set(c, items.get(1), 0);
        assertFalse(c.dhLods);
        assertFalse(c.dhShadows);
        assertEquals(0, VisualsOptionLayout.get(c, items.get(0)));
    }

    @Test
    void theLabelsAreInBothLanguages() throws Exception {
        List<String> keys = new ArrayList<>();
        keys.add(KEY_PREFIX + "block.dh");
        for (Item item : dhItems()) {
            keys.add(KEY_PREFIX + item.key());
            keys.add(KEY_PREFIX + item.key() + ".tooltip");
        }
        for (String name : new String[]{"en_us", "ko_kr"}) {
            JsonObject lang = lang(name);
            for (String key : keys) assertTrue(lang.has(key), name + " lacks " + key);
        }
    }

    @Test
    void lodsOffMeansNoInputsAndNoDrawing() {
        assertTrue(DhExtension.drawsLods(true, false));
        assertFalse(DhExtension.drawsLods(false, false));
        assertFalse(DhExtension.drawsLods(true, true));
        assertTrue(DhExtension.providesInputs(true, 5, 5));
        assertFalse(DhExtension.providesInputs(false, 5, 5), "dhLods off: postInputs is null");
        assertFalse(DhExtension.providesInputs(true, 4, 5), "no frame drawn for this serial");
    }

    @Test
    void shadowsOffMeansNoCasters() {
        assertTrue(DhExtension.castsShadows(true, true, false));
        assertFalse(DhExtension.castsShadows(true, false, false));
        assertFalse(DhExtension.castsShadows(false, true, false), "no LODs, no casters");
        assertFalse(DhExtension.castsShadows(true, true, true));
    }

    private static ShadowCascadeContext cascade(String name, long serial) {
        return new ShadowCascadeContext(name, null, 0, 0, serial, 0, null, null);
    }

    /** R28: a redraw with no DH frame to draw from reports no casters, not the previous draw's counts. */
    @Test
    void anEmptyCasterRedrawClearsTheCascadesStats() {
        DhExtension ext = DhExtension.INSTANCE;
        int[] stats = ext.casterStats("shadow.near");
        stats[0] = 7;
        stats[1] = 900;
        ext.drawShadowCasters(null, 0, cascade("shadow.near", 12));
        assertArrayEquals(new int[]{0, 0}, ext.casterStats("shadow.near"));
    }

    @Test
    void forcedReflectionFailureSwitchesDhOffWithOneWarning() {
        System.setProperty(DhInternals.FORCE_REFLECT_FAIL, "true");
        Object container = new DhInternalsTest.FakeContainer(new DhInternalsTest.FakePos(1, 2, 3), 0L);
        try (LogCapture log = LogCapture.visuals()) {
            assertNull(DhInternals.minCornerBlockPos(container));
            assertNull(DhInternals.minCornerBlockPos(container));
            assertFalse(DhInternals.ok());
            assertTrue(DhRegistration.disabled());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            assertTrue(log.warnings().get(0).contains(DhInternals.FORCE_REFLECT_FAIL), log.warnings().get(0));
        }
    }

    @Test
    void withoutTheSwitchTheContainerReadsNormally() {
        Object container = new DhInternalsTest.FakeContainer(new DhInternalsTest.FakePos(1, 2, 3), 0L);
        assertArrayEquals(new long[]{1, 2, 3}, DhInternals.minCornerBlockPos(container));
        assertTrue(DhInternals.ok());
    }
}
