package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shading settings keep only the edge cover; the level-of-detail options stay with the mod that draws them. */
class ShadingSettingsTest {
    @Test
    void edgeCoverIsOnByDefault() {
        assertTrue(ShadingSettings.from(new VisualsConfig()).edgeCover());
    }

    @Test
    void nullConfigIsTheDefaults() {
        assertEquals(ShadingSettings.from(new VisualsConfig()), ShadingSettings.from(null));
    }

    @Test
    void theSettingFollowsTheConfig() {
        VisualsConfig c = new VisualsConfig();
        c.edgeCover = false;
        assertFalse(ShadingSettings.from(c).edgeCover());
    }

    @Test
    void describeShowsTheScreenUnits() {
        assertEquals("edge cover on", ShadingSettings.from(new VisualsConfig()).describe());
        assertEquals(java.util.List.of("edge_cover"), ShadingSettings.OPTION_NAMES);
    }
}
