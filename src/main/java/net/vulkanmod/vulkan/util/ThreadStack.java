package net.vulkanmod.vulkan.util;

import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;

/**
 * Runs a task on a new thread whose LWJGL MemoryStack holds the given number of bytes. LWJGL 3.3.1 sizes every thread's stack from
 * Configuration.STACK_SIZE (-Dorg.lwjgl.system.stackSize, default 64 KiB) once, when MemoryStack loads, and Forge's early loading
 * window loads it before any mod runs: setting the size from the mod changes nothing. LWJGL's VkInstance constructor lists every
 * device's extensions on the stack (some 200 on NVIDIA), past 64 KiB, so without the launcher flag the instance could not be made.
 * The new thread has no frames yet, so its thread-local stack is replaced before the task runs (MemoryStack's TLS, by reflection or,
 * where the module is closed to it, Unsafe); if neither reaches it the task runs on the default stack.
 */
public final class ThreadStack {
    private ThreadStack() {
    }

    /** The task's result, run on a new thread named name with a stack of at least bytes; its exception is rethrown wrapped. */
    public static <T> T call(String name, int bytes, Callable<T> task) {
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        boolean[] installed = new boolean[1];
        Thread thread = new Thread(() -> {
            installed[0] = install(bytes);
            try {
                result[0] = task.call();
            } catch (Throwable t) {
                error[0] = t;
            }
        }, name);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for " + name, e);
        }
        if (error[0] != null) {
            throw new RuntimeException(name + " failed" + (installed[0] ? "" : " (on LWJGL's default stack: the larger one could not be installed)"), error[0]);
        }
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    /** On a thread without stack frames: its MemoryStack replaced by one of bytes; whether the thread's stack now has that much. */
    @SuppressWarnings("unchecked")
    static boolean install(int bytes) {
        try {
            Field f = MemoryStack.class.getDeclaredField("TLS");
            ThreadLocal<MemoryStack> tls;
            try {
                f.setAccessible(true);
                tls = (ThreadLocal<MemoryStack>) f.get(null);
            } catch (RuntimeException | IllegalAccessException e) {
                tls = (ThreadLocal<MemoryStack>) VUtil.UNSAFE.getObject(VUtil.UNSAFE.staticFieldBase(f), VUtil.UNSAFE.staticFieldOffset(f));
            }
            tls.set(MemoryStack.create(bytes));
            return MemoryStack.stackGet().getSize() >= bytes;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
