package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** FG design 3.5: when generation pauses, and which reason names it. */
class PauseRulesTest {
    /** Everything clear (level, supported), with flag i flipped (-1: none), in the parameter order of reason(). */
    private static String reasonWith(int i) {
        boolean[] f = {true, false, false, false, false, false, false, false, false, true, false};
        if (i >= 0) f[i] = !f[i];
        return PauseRules.reason(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10]);
    }

    @Test void everythingClearMeansRun() {
        assertNull(PauseRules.reason(true, false, false, false, false, false, false, false, false, true, false));
        assertNull(reasonWith(-1));
    }

    @Test void unsupportedComesFirst() {
        assertEquals("unsupported", PauseRules.reason(true, true, false, false, false, false, false, false, false, false, false));
        assertEquals("unsupported", PauseRules.reason(false, true, true, true, true, true, true, true, true, false, true));
    }

    @Test void eachRuleNamesItself() {
        //parameter order: level, screen, overlay, screenshot, paused, postEffect, entityOutlines, debugCharts, spectatingOther, supported,
        // forced
        List<String> expected = List.of("no world", "screen", "overlay", "screenshot", "paused", "post effect", "outlines", "debug charts",
                "spectating", "unsupported", "forced");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), reasonWith(i), "flag " + i);
        //the documented priority: every earlier reason wins over a later one
        assertEquals("no world", PauseRules.reason(false, true, true, true, true, true, true, true, true, true, true));
        assertEquals("screen", PauseRules.reason(true, true, true, true, true, true, true, true, true, true, true));
        assertEquals("overlay", PauseRules.reason(true, false, true, true, true, true, true, true, true, true, true));
        assertEquals("screenshot", PauseRules.reason(true, false, false, true, true, true, true, true, true, true, true));
        assertEquals("paused", PauseRules.reason(true, false, false, false, true, true, true, true, true, true, true));
        assertEquals("post effect", PauseRules.reason(true, false, false, false, false, true, true, true, true, true, true));
        assertEquals("outlines", PauseRules.reason(true, false, false, false, false, false, true, true, true, true, true));
        assertEquals("debug charts", PauseRules.reason(true, false, false, false, false, false, false, true, true, true, true));
        assertEquals("spectating", PauseRules.reason(true, false, false, false, false, false, false, false, true, true, true));
        assertEquals("forced", PauseRules.reason(true, false, false, false, false, false, false, false, false, true, true));
    }

    /** "forced" (gate G1.2) keeps the UI layer; every other reason, the one-frame "screenshot" included, draws the GUI into the world. */
    @Test void onlyForcedKeepsTheUiLayer() {
        assertTrue(PauseRules.keepsUiLayer(null));
        assertTrue(PauseRules.keepsUiLayer(PauseRules.FORCED));
        for (String r : List.of("unsupported", "no world", "screen", "overlay", "screenshot", "paused", "post effect", "outlines", "debug charts",
                "spectating")) {
            assertFalse(PauseRules.keepsUiLayer(r), r);
        }
    }
}
