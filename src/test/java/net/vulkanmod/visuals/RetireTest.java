package net.vulkanmod.visuals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.visuals.Bytecode.calls;
import static net.vulkanmod.visuals.Bytecode.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-31 (the user: turning on a font resource pack in game froze it): the reload's allChanged runs inside a frame, and the old
 * renderer's buffers were destroyed while that frame's unsubmitted commands still used them (VK_ERROR_DEVICE_LOST).
 */
class RetireTest {
    @Test
    void insideAFrameTheFreeWaitsForTheFrameToComplete() {
        List<Runnable> afterFrame = new ArrayList<>();
        int[] freed = {0};
        Visuals.retire(() -> freed[0]++, true, afterFrame::add);
        assertEquals(0, freed[0], "nothing freed while the frame's commands are being recorded");
        assertEquals(1, afterFrame.size());
        afterFrame.get(0).run();
        assertEquals(1, freed[0]);
    }

    @Test
    void outsideAFrameItIsFreedNow() {
        List<Runnable> afterFrame = new ArrayList<>();
        int[] freed = {0};
        Visuals.retire(() -> freed[0]++, false, afterFrame::add);
        assertEquals(1, freed[0]);
        assertTrue(afterFrame.isEmpty());
    }

    /** The old renderer stops taking part in frames at once (its frame listener goes), only its resources wait. */
    @Test
    void shutdownClosesTheRendererBeforeRetiringItsResources() throws Exception {
        List<String> c = calls("net/vulkanmod/visuals/Visuals", "shutdown");
        assertEquals(1, count(c, "VisualsRenderer.close"), c.toString());
        assertEquals(1, count(c, "Visuals.retire"), c.toString());
        assertTrue(c.indexOf("VisualsRenderer.close") < c.indexOf("Visuals.retire"), c.toString());
        assertEquals(0, count(c, "VisualsRenderer.shutdown"), c.toString());
    }
}
