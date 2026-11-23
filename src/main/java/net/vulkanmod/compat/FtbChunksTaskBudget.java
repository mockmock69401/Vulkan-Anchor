package net.vulkanmod.compat;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 2026-11-23 (the user's Sunlit 1% lows; Experimental > Mod Compatibility; NeoForge: FTB Chunks 2101.1.x has the same queue): FTB Chunks runs its map tasks on the client tick, up to
 * task_queue_max (100) of them every task_queue_ticks (4) ticks. A region save fills five 512 x 512 images on that thread, and a batch of
 * them made one tick 28 ms with 109 MB allocated (JFR, MapRegionData.write in ClientTaskQueue.runQueuedTasks).
 *
 * <p>With the switch on, FtbChunksTaskQueueMixin runs the queue here instead. It keeps the same tasks, the same order, the same cap,
 * and stops a batch after a task that cancels the others. A batch also stops once BUDGET_NANOS have passed, and the rest waits in
 * the queue. To keep the same throughput, what is left is run on the ticks in between, under the same budget. FTB Chunks' own code
 * runs unchanged, on the same thread; only when it runs changes.</p>
 */
public final class FtbChunksTaskBudget {
    /** Time a call may spend on tasks (the task that crosses it finishes); twice that while the queue holds more than two batches. */
    static final long BUDGET_NANOS = 3_000_000L;
    private static final long STATS_NANOS = 10_000_000_000L;

    private static Deque<Object> queue;
    private static MethodHandle runTask, cancelsOthers, maxGetter;
    private static boolean broken, listening, ranThisTick;
    private static long statSince, statNanos, statMaxNanos;
    private static int statTasks, statCalls;

    private FtbChunksTaskBudget() {
    }

    /** The switch (Experimental > Mod Compatibility). */
    public static boolean enabled() {
        VisualsConfig c = VisualsConfig.get();
        return !broken && c != null && c.ftbChunksTaskBudget;
    }

    /**
     * The mixin's replacement of ClientTaskQueue.runQueuedTasks: false when it did not run the queue (switch off, or FTB Chunks' classes
     * are not as expected), and FTB Chunks' own loop runs.
     */
    public static boolean runQueued(MethodHandles.Lookup clientTaskQueue) {
        if (!enabled()) return false;
        if (!bind(clientTaskQueue)) return false;
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> ranThisTick = false);
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
                if (!ranThisTick && queue != null && !queue.isEmpty() && enabled()) runBatch();
            });
        }
        runBatch();
        return true;
    }

    private static void runBatch() {
        ranThisTick = true;
        int max = taskQueueMax();
        long budget = queue.size() > 2L * max ? 2 * BUDGET_NANOS : BUDGET_NANOS;
        long start = System.nanoTime();
        int n = drain(queue, max, budget, System::nanoTime, FtbChunksTaskBudget::cancelsOthers, FtbChunksTaskBudget::run);
        stat(n, System.nanoTime() - start);
    }

    /**
     * Runs queued tasks from the front, as FTB Chunks' loop does: at most max of those queued when it starts, stopping after a task that
     * cancels the others; and, here, once budgetNanos passed since the start (checked after each task, so one always runs). Returns how
     * many ran.
     */
    static <T> int drain(Deque<T> queue, int max, long budgetNanos, LongSupplier clock, Predicate<T> cancelsOthers, Consumer<T> run) {
        int limit = Math.min(queue.size(), max);
        long deadline = clock.getAsLong() + budgetNanos;
        int ran = 0;
        while (ran < limit) {
            T task = queue.pollFirst();
            if (task == null) break;
            ran++;
            boolean last = cancelsOthers.test(task);
            run.accept(task);
            if (last || clock.getAsLong() >= deadline) break;
        }
        return ran;
    }

    private static void run(Object task) {
        try {
            runTask.invoke(task);
        } catch (Throwable t) {
            VisualsLog.error("Visuals FTB Chunks task failed: " + task, t);
        }
    }

    private static boolean cancelsOthers(Object task) {
        try {
            return (boolean) cancelsOthers.invoke(task);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int taskQueueMax() {
        try {
            Object v = maxGetter.invoke();
            return v instanceof Integer i && i > 0 ? i : 100;
        } catch (Throwable t) {
            return 100;
        }
    }

    /**
     * FTB Chunks' queue, MapTask and task_queue_max, found once through ClientTaskQueue's own lookup; any mismatch turns this off for
     * the session (FTB Chunks' loop then runs as it is).
     */
    @SuppressWarnings("unchecked")
    private static boolean bind(MethodHandles.Lookup clientTaskQueue) {
        if (runTask != null) return true;
        if (broken) return false;
        try {
            Class<?> owner = clientTaskQueue.lookupClass();
            ClassLoader loader = owner.getClassLoader();
            Object q = clientTaskQueue.findStaticGetter(owner, "taskQueue", java.util.ArrayDeque.class).invoke();
            if (!(q instanceof Deque<?> d)) throw new IllegalStateException("taskQueue is " + q);
            Class<?> task = Class.forName("dev.ftb.mods.ftbchunks.client.map.MapTask", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle run = lookup.findVirtual(task, "runMapTask", MethodType.methodType(void.class));
            MethodHandle cancels = lookup.findVirtual(task, "cancelOtherTasks", MethodType.methodType(boolean.class));
            Class<?> config = Class.forName("dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig", false, loader);
            Object max = config.getField("TASK_QUEUE_MAX").get(null);
            MethodHandle get = lookup.findVirtual(max.getClass(), "get", MethodType.methodType(Object.class)).bindTo(max);
            runTask = run.asType(MethodType.methodType(void.class, Object.class));
            cancelsOthers = cancels.asType(MethodType.methodType(boolean.class, Object.class));
            maxGetter = get;
            queue = (Deque<Object>) d;
            VisualsLog.info("Visuals FTB Chunks map tasks run under a " + BUDGET_NANOS / 1_000_000 + " ms budget a tick (Experimental > Mod Compatibility)");
            return true;
        } catch (Throwable t) {
            broken = true;
            VisualsLog.warn("Visuals FTB Chunks task budget left off, this FTB Chunks differs: " + t);
            return false;
        }
    }

    /** Every STATS_NANOS: tasks run, time spent, the longest call and what is still queued. */
    private static void stat(int tasks, long nanos) {
        statTasks += tasks;
        statCalls++;
        statNanos += nanos;
        statMaxNanos = Math.max(statMaxNanos, nanos);
        long now = System.nanoTime();
        if (statSince == 0) statSince = now;
        if (now - statSince < STATS_NANOS) return;
        VisualsLog.info(String.format("Visuals FTB Chunks tasks: %d in %d calls, %.1f ms, longest call %.2f ms, queued %d", statTasks, statCalls,
                statNanos / 1e6, statMaxNanos / 1e6, queue == null ? 0 : queue.size()));
        statSince = now;
        statTasks = 0;
        statCalls = 0;
        statNanos = 0;
        statMaxNanos = 0;
    }
}
