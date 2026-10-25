package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged waits on a frame slot's fence inside beginFrame, recurses into beginFrame when acquire fails, and skips the
 * submit while the swapchain is being recreated; frame completion must follow those events, not a frame counter.
 */
class FrameTrackerTest {
    @Test
    void aFrameCompletesOnlyOnceItsSlotsFenceIsWaited() {
        FrameTracker t = new FrameTracker();
        long first = t.recordingStarted(0);
        t.submitted();
        long second = t.recordingStarted(1);
        t.submitted();
        assertEquals(0, t.completedSerial(), "nothing waited yet");
        t.fenceWaited(0);
        assertEquals(first, t.completedSerial());
        t.fenceWaited(1);
        assertEquals(second, t.completedSerial());
    }

    @Test
    void waitingASlotAgainWithoutNewWorkDoesNotAdvance() {
        FrameTracker t = new FrameTracker();
        t.recordingStarted(0);
        t.submitted();
        t.recordingStarted(1);
        t.submitted();
        t.fenceWaited(0);
        t.fenceWaited(0); // beginFrame recursing after a failed acquire
        assertEquals(1, t.completedSerial());
    }

    @Test
    void aFrameThatIsNeverSubmittedIsDroppedNotCompleted() {
        FrameTracker t = new FrameTracker();
        long dropped = t.recordingStarted(0);
        long next = t.recordingStarted(0); // swapchain update: endFrame without submit, then a new frame
        assertTrue(t.isDropped(dropped));
        assertFalse(t.isDropped(next));
        t.submitted();
        t.fenceWaited(0);
        assertEquals(next, t.completedSerial());
    }

    @Test
    void deviceIdleCompletesEverythingSubmittedAndForgetsSlots() {
        FrameTracker t = new FrameTracker();
        t.recordingStarted(0);
        t.submitted();
        long last = t.recordingStarted(1);
        t.submitted();
        t.deviceIdle();
        assertEquals(last, t.completedSerial());
        long afterResize = t.recordingStarted(0);
        t.fenceWaited(1); // slot 1 holds nothing submitted after the idle
        assertEquals(last, t.completedSerial());
        t.submitted();
        t.fenceWaited(0);
        assertEquals(afterResize, t.completedSerial());
    }

    @Test
    void recordingSlotIsTheSlotOfTheFrameBeingRecorded() {
        FrameTracker t = new FrameTracker();
        t.recordingStarted(2);
        assertEquals(2, t.recordingSlot());
    }

    @Test
    void recordingSerialIsTheFrameBeingRecorded() {
        FrameTracker t = new FrameTracker();
        assertEquals(0, t.recordingSerial());
        long s = t.recordingStarted(2);
        assertEquals(s, t.recordingSerial());
    }
}
