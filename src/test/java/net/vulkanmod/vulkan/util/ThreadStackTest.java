package net.vulkanmod.vulkan.util;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The task runs on a new thread with the larger LWJGL stack, though MemoryStack was loaded (and sized) before. */
class ThreadStackTest {
    @Test
    void theTaskGetsTheLargerStackAfterMemoryStackLoaded() {
        int defaultSize = MemoryStack.stackGet().getSize();//loads MemoryStack with the default size first, as Forge's early window does
        int size = ThreadStack.call("test-stack", 1 << 20, () -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                stack.malloc(512 * 1024);//past LWJGL's 64 KiB default
            }
            return MemoryStack.stackGet().getSize();
        });
        assertTrue(size >= 1 << 20, "stack " + size);
        assertEquals(defaultSize, MemoryStack.stackGet().getSize(), "the calling thread's stack is not touched");
    }

    @Test
    void theTasksExceptionIsRethrown() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ThreadStack.call("test-fail", 1 << 20, () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(e.getCause() instanceof IllegalStateException);
    }
}
