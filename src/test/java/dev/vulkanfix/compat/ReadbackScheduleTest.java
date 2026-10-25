package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;

import static dev.vulkanfix.compat.ReadbackSchedule.State.COMPLETE;
import static dev.vulkanfix.compat.ReadbackSchedule.State.NOT_SUBMITTED;
import static dev.vulkanfix.compat.ReadbackSchedule.State.SUBMITTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A deferred readback is recorded into the frame being built (frame serial S). Reforged's beginFrame waits on the
 * fence of the slot it reuses, so after framesNum more frames have begun, frame S has finished on the GPU.
 */
class ReadbackScheduleTest {
    @Test
    void sameFrameIsNotSubmittedYet() {
        assertEquals(NOT_SUBMITTED, ReadbackSchedule.state(10, 10, 2));
    }

    @Test
    void laterFrameBeforeFenceReuseIsOnlySubmitted() {
        assertEquals(SUBMITTED, ReadbackSchedule.state(10, 11, 2));
        assertEquals(SUBMITTED, ReadbackSchedule.state(10, 12, 3));
    }

    @Test
    void afterFramesNumMoreFramesItIsComplete() {
        assertEquals(COMPLETE, ReadbackSchedule.state(10, 12, 2));
        assertEquals(COMPLETE, ReadbackSchedule.state(10, 40, 2));
    }
}
