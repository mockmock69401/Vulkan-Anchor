package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Review focus 1: GPU updates recorded into a frame Reforged never submitted must run again, first and in order. */
class FrameWorkTest {
    @Test
    void finishedWorkIsHandedBack() {
        FrameWork<String> w = new FrameWork<>();
        w.queue("a");
        assertEquals(List.of("a"), w.record(5));
        List<String> done = new ArrayList<>();
        w.tick(4, s -> false, done::add, s -> true);
        assertEquals(List.of(), done);
        w.tick(5, s -> false, done::add, s -> true);
        assertEquals(List.of("a"), done);
        assertFalse(w.hasPending());
    }

    @Test
    void droppedWorkIsRecordedAgainBeforeNewerWork() {
        FrameWork<String> w = new FrameWork<>();
        w.queue("a");
        w.queue("b");
        w.record(5);
        w.queue("c");
        w.tick(6, s -> s == 5, s -> { }, s -> true);
        assertEquals(List.of("a", "b", "c"), w.record(7));
    }

    @Test
    void droppedWorkNotRequeuedIsDiscarded() {
        FrameWork<String> w = new FrameWork<>();
        w.queue("a");
        w.record(5);
        w.tick(6, s -> s == 5, s -> { }, s -> false);
        assertFalse(w.hasPending());
    }
}
