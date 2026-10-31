package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.Bytecode;
import net.vulkanmod.visuals.dh.DhPresence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** I1: without DH, or with a DH that has no render API, the settings screen is the fork's own: no Distant Horizons block. */
class VisualsSettingsPageTest {
    private static final String PAGE = "net/vulkanmod/visuals/config/VisualsSettingsPage";

    private static List<String> shown(DhPresence.State dh) {
        List<String> keys = new ArrayList<>();
        for (VisualsOptionLayout.Page page : VisualsOptionLayout.PAGES) {
            for (VisualsOptionLayout.Block block : VisualsSettingsPage.shownBlocks(page, dh)) keys.add(page.key() + "/" + block.key());
        }
        return keys;
    }

    @Test
    void theDhBlockShowsOnlyWhenDhHasItsRenderApi() {
        List<String> all = new ArrayList<>();
        for (VisualsOptionLayout.Page page : VisualsOptionLayout.PAGES) {
            for (VisualsOptionLayout.Block block : page.blocks()) all.add(page.key() + "/" + block.key());
        }
        assertTrue(all.contains("lighting/" + VisualsOptionLayout.DH_BLOCK));
        List<String> withoutDh = new ArrayList<>(all);
        withoutDh.remove("lighting/" + VisualsOptionLayout.DH_BLOCK);

        assertEquals(all, shown(DhPresence.State.RENDER_API));
        assertEquals(withoutDh, shown(DhPresence.State.ABSENT));
        assertEquals(withoutDh, shown(DhPresence.State.NO_RENDER_API), "DH 3.3.4 and older draw nothing through the fork");
    }

    /** Both of pages' loops (the options and their switches) go through shownBlocks, with DH's presence as detected. */
    @Test
    void thePageIsBuiltFromTheShownBlocks() throws Exception {
        List<String> calls = Bytecode.calls(PAGE, "pages");
        assertEquals(2, Bytecode.count(calls, "VisualsSettingsPage.shownBlocks"), calls.toString());
        assertTrue(calls.contains("DhPresence.current"), calls.toString());
        assertFalse(calls.contains("VisualsOptionLayout$Page.blocks"), "every block list is filtered: " + calls);
    }

    /** The page's activation asks the layout which items follow the post-process switch (frame generation does not). */
    @Test
    void theMasterSwitchGateComesFromTheLayout() throws Exception {
        List<String> calls = Bytecode.calls(PAGE, "pages");
        assertTrue(calls.contains("VisualsOptionLayout.followsMasterSwitch"), calls.toString());
    }

    /** 2026-10-31 (the user): the reset to defaults is a button, not a switch that turns itself back off. */
    @Test
    void theResetIsAButton() throws Exception {
        List<String> calls = Bytecode.calls(PAGE, "pages");
        assertTrue(calls.contains("ButtonOption.<init>"), calls.toString());
        assertFalse(calls.contains("SwitchOption.<init>"), calls.toString());
    }

    @Test
    void theDhSettingsStayInTheConfigWhenHidden() {
        VisualsConfig c = new VisualsConfig();
        assertTrue(c.dhLods);
        assertTrue(c.dhShadows);
    }
}
