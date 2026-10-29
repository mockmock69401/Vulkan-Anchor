package net.vulkanmod.visuals;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.PostProcessSettings;
import net.vulkanmod.visuals.render.VisualsRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.vulkanmod.visuals.Bytecode.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review focus 1: the Nether, the End and modded dimensions without sky light lose the shadows, the sky and the clouds only. */
class NoSkyDimensionTest {
    private static final String RENDERER = "net/vulkanmod/visuals/render/VisualsRenderer";

    @Test
    void withoutSkyLightTheShadowsCloudsAndSkyAreOff() {
        PostProcessSettings s = PostProcessSettings.from(new VisualsConfig());
        VisualsRenderer.Plan p = VisualsRenderer.Plan.of(false, s);
        assertFalse(p.shadows());
        assertFalse(p.clouds());
        assertFalse(p.sky());
        assertEquals(s.perPixelLight(), p.lighting());
        assertEquals(s.waterReflections() > 0, p.reflections());
        assertTrue(p.lighting() && p.reflections(), "on with the defaults");
    }

    @Test
    void theLightingAndReflectionsFollowTheSettings() {
        VisualsConfig c = new VisualsConfig();
        c.perPixelLight = false;
        c.waterReflections = 0;
        c.blockReflections = 0;
        PostProcessSettings s = PostProcessSettings.from(c);
        for (boolean sky : new boolean[]{false, true}) {
            VisualsRenderer.Plan p = VisualsRenderer.Plan.of(sky, s);
            assertFalse(p.lighting());
            assertFalse(p.reflections());
        }
    }

    @Test
    void withSkyLightTheSettingsDecide() {
        VisualsRenderer.Plan on = VisualsRenderer.Plan.of(true, PostProcessSettings.from(new VisualsConfig()));
        assertTrue(on.shadows() && on.clouds() && on.sky() && on.lighting() && on.reflections());
        VisualsConfig c = new VisualsConfig();
        c.sunShadows = false;
        c.farClouds = false;
        VisualsRenderer.Plan off = VisualsRenderer.Plan.of(true, PostProcessSettings.from(c));
        assertFalse(off.shadows());
        assertFalse(off.clouds());
        assertTrue(off.sky());
        //2026-10-19 (clouds dark with Sun Shading): vanilla's cloud layer is taken with sky light even with Far Clouds off, for the copy
        assertTrue(off.cloudLayer(), "the vanilla layer without the far clouds");
        assertTrue(on.cloudLayer());
        assertFalse(VisualsRenderer.Plan.of(false, PostProcessSettings.from(new VisualsConfig())).cloudLayer(), "no sky light, no clouds");
    }

    /** The frame and the post-process both decide by the plan; the shadow maps are only built in a dimension with sky light. */
    @Test
    void theRendererFollowsThePlan() throws Exception {
        assertTrue(calls(RENDERER, "frame").contains("VisualsRenderer$Plan.of"), calls(RENDERER, "frame").toString());
        assertTrue(calls(RENDERER, "postProcess").contains("VisualsRenderer$Plan.of"), calls(RENDERER, "postProcess").toString());
        List<String> init = calls(RENDERER, "<init>");
        assertTrue(Bytecode.inOrder(init, List.of("DimensionType.hasSkyLight", "VisualsRenderer.createShadowMaps")), init.toString());
        assertTrue(calls(RENDERER, "createShadowMaps").contains("VkShadowMaps.<init>"));
    }
}
