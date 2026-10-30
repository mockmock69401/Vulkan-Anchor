package net.vulkanmod.render.chunk;

import net.vulkanmod.render.chunk.build.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.BuildTask;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.build.task.CompileResult;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: doors open and close late in Deceased): a section the player changed jumps the builders' queues and the
 * finished meshes' queue (full, a door's mesh waited for room behind a loading area's), and is scheduled with the build queue full.
 */
class TaskDispatcherUrgentTest {
    @Test
    void urgentTasksAreBuiltFirstEvenWithTheQueueFull() throws Exception {
        TaskDispatcher d = dispatcher();
        ChunkTask low = task(false, false), high = task(true, false), urgent = task(true, true);
        assertTrue(d.schedule(low));
        assertTrue(d.schedule(high));
        set(d, "lowPriorityTasks", full());
        assertFalse(d.hasTaskCapacity());
        assertTrue(d.schedule(urgent), "an urgent task is taken with the build queue full");
        Method poll = TaskDispatcher.class.getDeclaredMethod("pollTask");
        poll.setAccessible(true);
        assertSame(urgent, poll.invoke(d));
        assertSame(high, poll.invoke(d));
    }

    @Test
    void anUrgentMeshNeverWaitsForRoom() throws Exception {
        TaskDispatcher d = dispatcher();
        @SuppressWarnings("unchecked") ArrayBlockingQueue<CompileResult> results = (ArrayBlockingQueue<CompileResult>) get(d, "compileResults");
        while (results.remainingCapacity() > 0) results.add(result(false));
        CompileResult urgent = result(true);
        boolean[] taken = new boolean[1];
        Thread t = new Thread(() -> taken[0] = d.scheduleSectionUpdate(urgent, task(true, true)), "test-builder");
        t.setDaemon(true);
        t.start();
        t.join(2000);
        boolean parked = t.isAlive();
        set(d, "stopThreads", true);
        assertFalse(parked, "an urgent mesh waited for room behind a full queue");
        assertTrue(taken[0]);
        assertEquals(results.size() + 1, d.pendingUploads());
    }

    @Test
    void clearingDropsUrgentWork() throws Exception {
        TaskDispatcher d = dispatcher();
        d.schedule(task(true, true));
        d.scheduleSectionUpdate(result(true), task(true, true));
        assertEquals(1, d.pendingUploads());
        d.clearBatchQueue();
        assertEquals(0, d.pendingUploads());
        Method poll = TaskDispatcher.class.getDeclaredMethod("pollTask");
        poll.setAccessible(true);
        assertNull(poll.invoke(d));
    }

    private static java.util.Queue<ChunkTask> full() {
        ConcurrentLinkedQueue<ChunkTask> q = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 64; i++) q.add(task(false, false));
        return q;
    }

    private static TaskDispatcher dispatcher() throws ReflectiveOperationException {
        TaskDispatcher d = (TaskDispatcher) unsafe().allocateInstance(TaskDispatcher.class);
        for (String q : new String[]{"highPriorityTasks", "lowPriorityTasks", "urgentTasks", "failedTasks", "urgentResults"}) {
            set(d, q, new ConcurrentLinkedQueue<>());
        }
        set(d, "compileResults", new ArrayBlockingQueue<>(8));
        set(d, "activeTasks", ConcurrentHashMap.newKeySet());
        set(d, "taskSignal", new Semaphore(0));
        set(d, "idleThreads", new AtomicInteger());
        set(d, "loggedTaskFailures", new AtomicInteger());
        set(d, "stopThreads", false);
        return d;
    }

    private static ChunkTask task(boolean high, boolean urgent) {
        try {
            BuildTask t = (BuildTask) unsafe().allocateInstance(BuildTask.class);
            t.highPriority = high;
            t.urgent = urgent;
            for (String flag : new String[]{"cancelled", "started"}) setIn(ChunkTask.class, t, flag, new AtomicBoolean());
            return t;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A full result whose section is where it was built (a section with no chunk area at 0, 0, 0, build revision 0). */
    private static CompileResult result(boolean urgent) throws ReflectiveOperationException {
        RenderSection section = (RenderSection) unsafe().allocateInstance(RenderSection.class);
        Class<?> status = Class.forName("net.vulkanmod.render.chunk.RenderSection$CompileStatus");
        setIn(RenderSection.class, section, "compileStatus", unsafe().allocateInstance(status));
        CompileResult r = (CompileResult) unsafe().allocateInstance(CompileResult.class);
        r.urgent = urgent;
        setIn(CompileResult.class, r, "renderSection", section);
        setIn(CompileResult.class, r, "fullUpdate", true);
        setIn(CompileResult.class, r, "renderedLayers", new EnumMap<>(TerrainRenderType.class));
        setIn(CompileResult.class, r, "stagedLayers", new EnumMap<>(TerrainRenderType.class));
        return r;
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static void setIn(Class<?> c, Object o, String name, Object value) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Object get(Object o, String name) throws ReflectiveOperationException {
        Field f = TaskDispatcher.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void set(Object o, String name, Object value) throws ReflectiveOperationException {
        setIn(TaskDispatcher.class, o, name, value);
    }
}
