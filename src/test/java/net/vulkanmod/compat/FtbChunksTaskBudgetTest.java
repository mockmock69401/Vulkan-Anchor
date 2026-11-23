package net.vulkanmod.compat;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-23: FTB Chunks' map tasks drained in order, under its cap, stopping after a task that cancels the others or past the budget. */
class FtbChunksTaskBudgetTest {
    /** A clock each task advances by its cost (ms). */
    private static final class Clock implements LongSupplier {
        long now;

        @Override
        public long getAsLong() {
            return this.now;
        }
    }

    private record Task(String name, long nanos, boolean cancels) {}

    private static List<String> drain(ArrayDeque<Task> q, int max, long budget, Clock clock) {
        List<String> ran = new ArrayList<>();
        FtbChunksTaskBudget.drain(q, max, budget, clock, Task::cancels, t -> {
            ran.add(t.name());
            clock.now += t.nanos();
        });
        return ran;
    }

    @Test
    void cheapTasksRunInOrderUpToTheCap() {
        ArrayDeque<Task> q = new ArrayDeque<>();
        for (int i = 0; i < 5; i++) q.add(new Task("t" + i, 1000, false));
        assertEquals(List.of("t0", "t1", "t2"), drain(q, 3, FtbChunksTaskBudget.BUDGET_NANOS, new Clock()));
        assertEquals(2, q.size(), "the rest waits, as FTB Chunks' own cap leaves it");
    }

    @Test
    void aTaskThatCancelsTheOthersEndsTheBatchAfterItRuns() {
        ArrayDeque<Task> q = new ArrayDeque<>(List.of(new Task("a", 1000, false), new Task("b", 1000, true), new Task("c", 1000, false)));
        assertEquals(List.of("a", "b"), drain(q, 100, FtbChunksTaskBudget.BUDGET_NANOS, new Clock()));
        assertEquals("c", q.peekFirst().name());
    }

    @Test
    void theBudgetStopsTheBatchAndAlwaysRunsOne() {
        ArrayDeque<Task> q = new ArrayDeque<>();
        for (int i = 0; i < 20; i++) q.add(new Task("save" + i, 1_400_000, false));//a region save, about 1.4 ms
        assertEquals(List.of("save0", "save1", "save2"), drain(q, 100, 3_000_000, new Clock()), "past 3 ms after the third");
        ArrayDeque<Task> slow = new ArrayDeque<>(List.of(new Task("big", 10_000_000, false), new Task("next", 1000, false)));
        assertEquals(List.of("big"), drain(slow, 100, 3_000_000, new Clock()));
        assertEquals(List.of(), drain(new ArrayDeque<>(), 100, 3_000_000, new Clock()));
    }

    @Test
    void tasksQueuedWhileDrainingWaitForTheNextCall() {
        ArrayDeque<Task> q = new ArrayDeque<>(List.of(new Task("a", 1000, false), new Task("b", 1000, false)));
        List<String> ran = new ArrayList<>();
        Clock clock = new Clock();
        FtbChunksTaskBudget.drain(q, 100, FtbChunksTaskBudget.BUDGET_NANOS, clock, Task::cancels, t -> {
            ran.add(t.name());
            if (t.name().equals("a")) q.add(new Task("added", 1000, false));
        });
        assertEquals(List.of("a", "b"), ran, "only what was queued when the call started, as FTB Chunks takes its batch first");
        assertEquals("added", q.peekFirst().name());
    }
}
