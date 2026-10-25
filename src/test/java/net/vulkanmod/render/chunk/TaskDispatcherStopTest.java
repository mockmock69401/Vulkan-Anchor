package net.vulkanmod.render.chunk;

import net.vulkanmod.render.chunk.build.TaskDispatcher;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractQueue;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A builder thread that found no task must not miss stopThreads' wake-up (2026-10-24, a level change hung the render thread in
 * TaskDispatcher.stopThreads' join: the stop came between the thread's empty poll and its wait, so its notifyAll was lost).
 */
class TaskDispatcherStopTest {
    @Test
    void aStopBetweenTheEmptyPollAndTheWaitEndsTheThread() throws Exception {
        TaskDispatcher d = allocate();
        //the poll finds nothing, and stopThreads() runs right after it, before the thread waits
        set(d, "highPriorityTasks", new AbstractQueue<Object>() {
            @Override
            public Object poll() {
                try {
                    set(d, "stopThreads", true);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                synchronized (d) {
                    d.notifyAll();
                }
                return null;
            }

            @Override
            public Iterator<Object> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public boolean offer(Object o) {
                return false;
            }

            @Override
            public Object peek() {
                return null;
            }
        });
        set(d, "lowPriorityTasks", new ConcurrentLinkedQueue<>());
        set(d, "stopThreads", false);

        Method run = TaskDispatcher.class.getDeclaredMethod("runTaskThread", Class.forName("net.vulkanmod.render.chunk.build.thread.BuilderResources"));
        run.setAccessible(true);
        Thread t = new Thread(() -> {
            try {
                run.invoke(d, (Object) null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }, "test-builder");
        t.setDaemon(true);
        t.start();
        t.join(2000);
        boolean stuck = t.isAlive();
        if (stuck) {
            //release it so the test JVM does not keep a parked thread
            synchronized (d) {
                d.notifyAll();
            }
        }
        assertFalse(stuck, "the builder thread waits forever after a stop that came between its poll and its wait");
    }

    private static TaskDispatcher allocate() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (TaskDispatcher) ((Unsafe) f.get(null)).allocateInstance(TaskDispatcher.class);
    }

    private static void set(Object o, String name, Object value) throws ReflectiveOperationException {
        Field f = TaskDispatcher.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }
}
