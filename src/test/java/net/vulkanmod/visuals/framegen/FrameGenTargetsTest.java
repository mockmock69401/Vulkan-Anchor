package net.vulkanmod.visuals.framegen;

import net.vulkanmod.config.RenderScale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameGenTargetsTest {
    @Test void fullScaleInWorldIsTheSwapchainSize() {
        assertArrayEquals(new int[]{2560, 1440}, FrameGenTargets.worldSize(2560, 1440, 100, true));
    }

    @Test void renderScaleAppliesOnlyInTheWorld() {
        assertArrayEquals(new int[]{RenderScale.scaleDimension(2560, 50), RenderScale.scaleDimension(1440, 50)},
                FrameGenTargets.worldSize(2560, 1440, 50, true));
        assertArrayEquals(new int[]{2560, 1440}, FrameGenTargets.worldSize(2560, 1440, 50, false));
    }

    /** Fix round 1: the capture reads one entry per render frame in flight (up to the frame queue option's 5), after the pacer and slot entries. */
    @Test void captureEntriesFollowThePacerAndSlotEntries() {
        assertEquals(5, FrameGenTargets.MAX_FRAMES_IN_FLIGHT);
        assertEquals(FrameGenTargets.PACER_PARAMS + FrameGenTargets.SLOTS + 5, FrameGenTargets.PARAMS_COUNT);
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int p = 0; p < FrameGenTargets.PACER_PARAMS; p++) assertTrue(used.add(p));
        for (int s = 0; s < FrameGenTargets.SLOTS; s++) assertTrue(used.add(FrameGenTargets.captureParams(s)));
        for (int f = 0; f < FrameGenTargets.MAX_FRAMES_IN_FLIGHT; f++) {
            int i = FrameGenTargets.captureFrameParams(f);
            assertTrue(i >= 0 && i < FrameGenTargets.PARAMS_COUNT && used.add(i), "entry " + i);
        }
        assertThrows(IllegalArgumentException.class, () -> FrameGenTargets.captureFrameParams(5));
        assertThrows(IllegalArgumentException.class, () -> FrameGenTargets.captureFrameParams(-1));
    }

    @Test void aZeroSwapchainGivesNoTargets() {
        assertArrayEquals(new int[]{0, 0}, FrameGenTargets.worldSize(0, 0, 100, true));
        assertArrayEquals(new int[]{0, 0}, FrameGenTargets.worldSize(0, 0, 50, true));
    }

    /**
     * Final review I3: a change of the world size alone (a screen toggled under render scale, a burst restart) keeps the UI layer's
     * framebuffers, and with them the UI render passes and their compiled pipelines; a new swapchain size replaces everything.
     */
    @Test void onlyASwapchainSizeChangeReplacesTheUiLayer() {
        assertEquals(FrameGenTargets.Rebuild.ALL, FrameGenTargets.rebuild(null, 2560, 1440, 2560, 1440), "first use");
        int[] old = {2560, 1440, 1280, 720};
        assertEquals(FrameGenTargets.Rebuild.NONE, FrameGenTargets.rebuild(old, 2560, 1440, 1280, 720));
        assertEquals(FrameGenTargets.Rebuild.WORLD, FrameGenTargets.rebuild(old, 2560, 1440, 2560, 1440), "a screen opened at render scale 50");
        assertEquals(FrameGenTargets.Rebuild.ALL, FrameGenTargets.rebuild(old, 1920, 1080, 960, 540), "a resize");
        assertEquals(FrameGenTargets.Rebuild.ALL, FrameGenTargets.rebuild(old, 2560, 1080, 1280, 540), "one side changed");
    }

    /**
     * Final review M2: the compose sets are rebuilt when a texture's image object was replaced even if Vulkan reused the view handle
     * value, and otherwise only when a view changed.
     */
    @Test void aReplacedImageRebuildsTheComposeSetsWhateverItsHandle() {
        assertTrue(FrameGenTargets.composeStale(11, 22, 11, 22, true), "same handle values, new images (a reload)");
        assertFalse(FrameGenTargets.composeStale(11, 22, 11, 22, false));
        assertTrue(FrameGenTargets.composeStale(11, 22, 12, 22, false));
        assertTrue(FrameGenTargets.composeStale(11, 22, 11, 23, false));
    }
}
