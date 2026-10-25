package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacerStateTest {
    @Test void stopFromRunningGoesThroughStopping() {
        PacerState s = new PacerState();
        assertTrue(s.start());
        assertTrue(s.running());
        assertTrue(s.requestStop());
        assertFalse(s.running(), "STOPPING is not running");
        assertFalse(s.start(), "no restart before the pacer reported stopped");
        s.stopped();
        assertTrue(s.start(), "STOPPED starts again");
        assertTrue(s.running());
    }

    @Test void stopWhenStoppedIsANoOp() {
        PacerState s = new PacerState();
        assertFalse(s.requestStop());
        assertFalse(s.running());
    }

    @Test void startTwiceIsRefused() {
        PacerState s = new PacerState();
        assertTrue(s.start());
        assertFalse(s.start());
        assertTrue(s.running());
    }
}
