package net.vulkanmod.render.chunk;

import net.vulkanmod.render.chunk.build.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.BuildTask;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.build.task.CompileResult;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.vulkanmod.render.vertex.TerrainRenderType;

import java.util.EnumMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: doors open and close late in Deceased): a section the player changed jumps both queues, the builders' and
 * the finished meshes' (128 waiting, 3 uploaded a frame, so a door's mesh waited behind a loading area's).
 */
class TaskDispatcherUrgentTest {
    @Test
    void urgentTasksAreBuiltFirst() throws Exception {
        TaskDispatcher d = dispatcher();
        ChunkTask low = task(false, false), high = task(true, false), urgent = task(true, true);
        d.schedule(low);
        d.schedule(high);
        d.schedule(urgent);
        Method poll = TaskDispatcher.class.getDeclaredMethod("pollTask");
        poll.setAccessible(true);
        assertSame(urgent, poll.invoke(d));
        assertSame(high, poll.invoke(d));
        assertSame(low, poll.invoke(d));
    }

    @Test
    void anUrgentMeshNeverWaitsForRoomAndIsUploadedFirst() throws Exception {
        TaskDispatcher d = dispatcher();
        @SuppressWarnings("unchecked") Queue<CompileResult> results = (Queue<CompileResult>) get(d, "compileResults");
        for (int i = 0; i < 128; i++) results.add(result(false));
        CompileResult urgent = result(true);
        Thread t = new Thread(() -> d.scheduleSectionUpdate(urgent), "test-builder");
        t.setDaemon(true);
        t.start();
        t.join(2000);
        boolean parked = t.isAlive();
        set(d, "stopThreads", true);
        assertFalse(parked, "an urgent mesh waited for room behind 128 others");
        Method next = TaskDispatcher.class.getDeclaredMethod("nextResult", boolean.class);
        next.setAccessible(true);
        assertSame(urgent, next.invoke(d, false), "uploaded even with this frame's budget spent");
        assertNull(next.invoke(d, false), "the others wait for the budget");
        assertNotNull(next.invoke(d, true));
        assertEquals(127 + 0, d.pendingUploads());
    }

    @Test
    void clearingDropsUrgentWork() throws Exception {
        TaskDispatcher d = dispatcher();
        d.schedule(task(true, true));
        d.scheduleSectionUpdate(result(true));
        d.clearBatchQueue();
        assertEquals(0, d.pendingUploads());
        Method poll = TaskDispatcher.class.getDeclaredMethod("pollTask");
        poll.setAccessible(true);
        assertNull(poll.invoke(d));
    }

    private static TaskDispatcher dispatcher() throws ReflectiveOperationException {
        TaskDispatcher d = (TaskDispatcher) unsafe().allocateInstance(TaskDispatcher.class);
        for (String q : new String[]{"highPriorityTasks", "lowPriorityTasks", "urgentTasks"}) set(d, q, new ConcurrentLinkedQueue<>());
        set(d, "compileResults", new LinkedBlockingDeque<>());
        set(d, "urgentResults", new ConcurrentLinkedQueue<>());
        set(d, "stopThreads", false);
        return d;
    }

    private static ChunkTask task(boolean high, boolean urgent) throws ReflectiveOperationException {
        BuildTask t = (BuildTask) unsafe().allocateInstance(BuildTask.class);
        t.highPriority = high;
        t.urgent = urgent;
        for (String flag : new String[]{"cancelled", "started"}) setIn(ChunkTask.class, t, flag, new AtomicBoolean());
        return t;
    }

    private static CompileResult result(boolean urgent) throws ReflectiveOperationException {
        CompileResult r = (CompileResult) unsafe().allocateInstance(CompileResult.class);
        r.urgent = urgent;
        setIn(CompileResult.class, r, "renderedLayers", new EnumMap<>(TerrainRenderType.class));
        return r;
    }

    private static void setIn(Class<?> c, Object o, String name, Object value) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static Object get(Object o, String name) throws ReflectiveOperationException {
        Field f = TaskDispatcher.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void set(Object o, String name, Object value) throws ReflectiveOperationException {
        Field f = TaskDispatcher.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }
}
