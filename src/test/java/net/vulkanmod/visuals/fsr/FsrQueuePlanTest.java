package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FSR design 4.2: where FSR's present, acquire and async compute queues go, beside the queues VulkanMod uses. */
class FsrQueuePlanTest {
    private static final int G = 0x1, C = 0x2, T = 0x4;

    private static Optional<FsrQueuePlan> nvidiaLike() {
        return FsrQueuePlan.plan(new int[]{G | C | T, T, C | T}, new int[]{16, 2, 8},
                new boolean[]{true, false, false}, new int[]{0, 1, 2});
    }

    @Test void nvidiaLikeFamiliesPlaceAllThree() {
        assertEquals(Optional.of(new FsrQueuePlan(0, 1, 0, 2, 2, 1)), nvidiaLike());
    }

    @Test void aSeparatePresentFamilyMovesThePresentQueueOutOfTheGameFamily() {
        //2026-11-02: the compute family can present, so the presenter thread's command lists get their own eight
        Optional<FsrQueuePlan> plan = FsrQueuePlan.plan(new int[]{G | C | T, T, C | T}, new int[]{16, 2, 8},
                new boolean[]{true, false, true}, new int[]{0, 1, 2}, 0);
        assertEquals(Optional.of(new FsrQueuePlan(2, 1, 0, 1, 2, 2)), plan);
        //without the option the first family that can present takes it, as before
        assertEquals(Optional.of(new FsrQueuePlan(0, 1, 0, 2, 2, 1)), FsrQueuePlan.plan(new int[]{G | C | T, T, C | T},
                new int[]{16, 2, 8}, new boolean[]{true, false, true}, new int[]{0, 1, 2}));
    }

    @Test void aSeparatePresentFamilyFallsBackToTheGameFamily() {
        //only the game family can present (the NVIDIA-like layout above): the present queue stays there
        Optional<FsrQueuePlan> plan = FsrQueuePlan.plan(new int[]{G | C | T, T, C | T}, new int[]{16, 2, 8},
                new boolean[]{true, false, false}, new int[]{0, 1, 2}, 0);
        assertEquals(nvidiaLike(), plan);
    }

    @Test void amdLikeFamiliesPlaceAllThree() {
        Optional<FsrQueuePlan> plan = FsrQueuePlan.plan(new int[]{G | C | T, C | T, T}, new int[]{1, 4, 2},
                new boolean[]{true, true, false}, new int[]{0, 1, 2});
        assertEquals(Optional.of(new FsrQueuePlan(1, 1, 1, 2, 1, 3)), plan);
    }

    @Test void singleQueueFamiliesGiveNoPlan() {
        assertTrue(FsrQueuePlan.plan(new int[]{G | C | T}, new int[]{1}, new boolean[]{true}, new int[]{0}).isEmpty());
    }

    @Test void computeIsOptional() {
        Optional<FsrQueuePlan> plan = FsrQueuePlan.plan(new int[]{G | C | T}, new int[]{3}, new boolean[]{true}, new int[]{0});
        assertEquals(Optional.of(new FsrQueuePlan(0, 1, 0, 2, -1, -1)), plan);
    }

    @Test void queueCountsCoverThePlan() {
        assertArrayEquals(new int[]{3, 1, 2}, FsrQueuePlan.queueCounts(nvidiaLike(), new int[]{0, 1, 2}));
    }
}
