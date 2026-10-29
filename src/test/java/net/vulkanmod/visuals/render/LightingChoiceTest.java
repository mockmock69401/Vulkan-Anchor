package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7d design 3.3: the lighting is on only when the post-process will apply it this frame. */
class LightingChoiceTest {
    private static final PostProcessSettings DEFAULTS = PostProcessSettings.from(new VisualsConfig());

    @Test
    void onWithTheDefaults() {
        LightingChoice c = LightingChoice.of(7, DEFAULTS, true, true, false, true);
        assertEquals(7, c.serial());
        assertTrue(c.ao() && c.sun());
    }

    @Test
    void offUnderEveryAtmosphereOffCondition() {
        assertFalse(LightingChoice.of(7, DEFAULTS, false, true, false, true).ao(), "no post-process");
        assertFalse(LightingChoice.of(7, DEFAULTS, true, false, false, true).ao(), "no atmosphere capture");
        assertFalse(LightingChoice.of(7, DEFAULTS, true, true, true, true).ao(), "fog covers everything");
        assertFalse(LightingChoice.of(7, DEFAULTS, true, true, false, false).sun(), "no sky light");
        VisualsConfig off = new VisualsConfig();
        off.enabled = false;
        assertFalse(LightingChoice.of(7, PostProcessSettings.from(off), true, true, false, true).sun(), "post-process off");
    }

    @Test
    void eachEffectFollowsItsStrength() {
        VisualsConfig c = new VisualsConfig();
        c.aoStrength = 0;
        LightingChoice choice = LightingChoice.of(7, PostProcessSettings.from(c), true, true, false, true);
        assertFalse(choice.ao());
        assertTrue(choice.sun());
        assertFalse(LightingChoice.NONE.ao() || LightingChoice.NONE.sun());
    }

    /** P7e-1 design 3.4: the water depth colour is a third effect with its own strength; the pass runs for any of them. */
    @Test
    void waterFollowsItsStrengthAndCountsForThePass() {
        assertTrue(LightingChoice.of(7, DEFAULTS, true, true, false, true).water());
        VisualsConfig c = new VisualsConfig();
        c.aoStrength = 0;
        c.sunShading = 0;
        LightingChoice waterOnly = LightingChoice.of(7, PostProcessSettings.from(c), true, true, false, true);
        assertTrue(waterOnly.water() && waterOnly.any());
        c.waterDepth = 0;
        c.waterReflections = 0;
        c.blockReflections = 0;
        assertFalse(LightingChoice.of(7, PostProcessSettings.from(c), true, true, false, true).any());
        assertFalse(LightingChoice.of(7, DEFAULTS, true, true, true, true).water(), "fog covers everything");
        assertFalse(LightingChoice.NONE.any());
    }

    /** P7e-2 design 3.4: reflections are a fourth effect with their own strength; the pass runs for any of them. */
    @Test
    void reflectionsFollowTheirStrengthAndCountForThePass() {
        assertTrue(LightingChoice.of(7, DEFAULTS, true, true, false, true).reflections());
        VisualsConfig c = new VisualsConfig();
        c.aoStrength = 0;
        c.sunShading = 0;
        c.waterDepth = 0;
        LightingChoice reflectionsOnly = LightingChoice.of(7, PostProcessSettings.from(c), true, true, false, true);
        assertTrue(reflectionsOnly.reflections() && reflectionsOnly.any());
        c.waterReflections = 0;
        c.blockReflections = 0;
        assertFalse(LightingChoice.of(7, PostProcessSettings.from(c), true, true, false, true).any());
        assertFalse(LightingChoice.of(7, DEFAULTS, true, true, true, true).reflections(), "fog covers everything");
    }
}
