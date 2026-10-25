package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Review I2: a layout transition recorded into a frame Reforged never submitted never happened. */
class LayoutTrackerTest {
    private static final int UNDEFINED = 0, TRANSFER_DST = 7, SHADER_READ = 5;

    @Test
    void transitionsOfADroppedFrameAreUndone() {
        LayoutTracker t = new LayoutTracker(UNDEFINED);
        t.recorded(5, TRANSFER_DST);
        t.recorded(5, SHADER_READ);
        assertEquals(SHADER_READ, t.current());
        t.tick(4, s -> s == 5);
        assertEquals(UNDEFINED, t.current());
    }

    @Test
    void completedTransitionsStay() {
        LayoutTracker t = new LayoutTracker(UNDEFINED);
        t.recorded(5, SHADER_READ);
        t.tick(5, s -> false);
        t.recorded(6, TRANSFER_DST);
        t.tick(5, s -> s == 6);
        assertEquals(SHADER_READ, t.current());
    }
}
