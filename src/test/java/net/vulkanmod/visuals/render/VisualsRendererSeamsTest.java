package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.api.LodInputs;
import net.vulkanmod.visuals.api.VisualsExtension;
import net.vulkanmod.visuals.api.VisualsExtensions;
import net.vulkanmod.visuals.config.VisualsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure seams of VisualsRenderer's frame: the LOD reach, the edge cover's switches, the extensions' F3 lines and the once-per-serial guard. */
class VisualsRendererSeamsTest {
    private final List<VisualsExtension> added = new ArrayList<>();

    @AfterEach
    void clear() {
        added.forEach(VisualsExtensions::unregister);
        added.clear();
    }

    private static LodInputs lod(float sections) {
        return new LodInputs(null, 1L, 2L, 3L, null, true, sections);
    }

    @Test
    void withoutLodInputsTheFarReachIsTheDefault() {
        assertEquals(16f, VisualsRenderer.farSections(null));
        assertEquals(VisualsRenderer.FAR_SECTIONS, VisualsRenderer.farSections(null));
    }

    @Test
    void anExtensionsLodDistanceSetsTheFarReach() {
        assertEquals(24f, VisualsRenderer.farSections(lod(24f)));
        assertEquals(6f, VisualsRenderer.farSections(lod(6f)));
    }

    @Test
    void theMasterSwitchOffDrawsNoEdgeCover() {
        VisualsConfig c = new VisualsConfig();
        c.edgeCover = true;
        c.enabled = false;
        assertFalse(VisualsRenderer.edgeCoverOn(PostProcessSettings.from(c), ShadingSettings.from(c)));
    }

    @Test
    void theEdgeCoverNeedsBothTheMasterSwitchAndItsOwn() {
        VisualsConfig c = new VisualsConfig();
        c.enabled = true;
        c.edgeCover = true;
        assertTrue(VisualsRenderer.edgeCoverOn(PostProcessSettings.from(c), ShadingSettings.from(c)));
        c.edgeCover = false;
        assertFalse(VisualsRenderer.edgeCoverOn(PostProcessSettings.from(c), ShadingSettings.from(c)));
    }

    @Test
    void anExtensionThatThrowsInItsDebugInfoDoesNotBreakTheOthers() {
        VisualsExtension bad = new VisualsExtension() {
            @Override
            public void addDebugInfo(List<String> lines) {
                throw new IllegalStateException("boom");
            }
        };
        VisualsExtension good = new VisualsExtension() {
            @Override
            public void addDebugInfo(List<String> lines) {
                lines.add("good line");
            }
        };
        added.add(bad);
        added.add(good);
        VisualsExtensions.register(bad);
        VisualsExtensions.register(good);
        List<String> lines = new ArrayList<>();
        VisualsRenderer.addExtensionDebugInfo(VisualsExtensions.all(), lines);
        VisualsRenderer.addExtensionDebugInfo(VisualsExtensions.all(), lines);
        assertEquals(2, lines.stream().filter("Visuals: extension debug info failed"::equals).count(), lines.toString());
        assertEquals(2, lines.stream().filter("good line"::equals).count(), lines.toString());
    }

    @Test
    void aSecondFrameInTheSameSerialIsSkipped() {
        VisualsRenderer r = VisualsRenderer.empty();
        assertTrue(r.claimSerial(7));
        assertFalse(r.claimSerial(7));
        assertTrue(r.claimSerial(8));
        assertFalse(r.claimSerial(8));
    }
}
