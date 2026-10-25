package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.libffi.LibFFI.*;

/** FSR design 4.3: calling native function pointers through libffi and being called back by FSR's threads. */
class FfxCallsTest {
    private static final long INFINITE = 0xFFFFFFFFL;

    private static void assumeWindows() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
    }

    private static SharedLibrary load(String dll) {
        return Library.loadNative(FfxCallsTest.class, "org.lwjgl", dll);
    }

    @Test void callsANativeFunctionThroughLibffi() {
        assumeWindows();
        try (SharedLibrary msvcrt = load("msvcrt.dll")) {
            long labs = msvcrt.getFunctionAddress("labs");
            assertNotEquals(0L, labs);
            assertEquals(7L, FfxCalls.lastPresentCount(labs, -7L));
        }
    }

    @Test void nullFunctionThrowsInsteadOfCrashing() {
        assertThrows(FsrException.class, () -> FfxCalls.lastPresentCount(0L, 1L));
    }

    @Test void closureRoundTrips() {
        long[] seen = new long[3];
        long callback = FfxCalls.submitCallback((count, pSubmits, fence) -> {
            seen[0] = count;
            seen[1] = pSubmits;
            seen[2] = fence;
            return -13;
        });
        try {
            assertNotEquals(0L, callback);
            assertEquals(-13, (int) FfxCalls.call(FfxCalls.QUEUE_SUBMIT, callback, 3, 0x1000, 0x2000));
            assertArrayEquals(new long[]{3, 0x1000, 0x2000}, seen);
        } finally {
            FfxCalls.freeCallback(callback);
        }
    }

    /** FSR submits from its own native threads; CreateThread starts the closure on a thread the JVM has never seen. */
    @Test void closureRunsOnANativeThread() {
        assumeWindows();
        AtomicReference<Thread> caller = new AtomicReference<>();
        AtomicLong count = new AtomicLong();
        long callback = FfxCalls.submitCallback((c, pSubmits, fence) -> {
            caller.set(Thread.currentThread());
            count.set(c);
            return 42;
        });
        FFICIF createThread = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_pointer,
                ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_uint32, ffi_type_pointer);
        FFICIF waitForSingleObject = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_uint32, ffi_type_pointer, ffi_type_uint32);
        FFICIF handleAndPointer = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32, ffi_type_pointer, ffi_type_pointer);
        FFICIF closeHandle = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32, ffi_type_pointer);
        try (SharedLibrary kernel32 = load("kernel32.dll"); MemoryStack stack = MemoryStack.stackPush()) {
            //the thread start routine receives its parameter (3) as the first argument, the submit count
            long thread = FfxCalls.call(createThread, kernel32.getFunctionAddress("CreateThread"), 0, 0, callback, 3, 0, 0);
            assertNotEquals(0L, thread);
            assertEquals(0L, FfxCalls.call(waitForSingleObject, kernel32.getFunctionAddress("WaitForSingleObject"), thread, INFINITE));
            IntBuffer exitCode = stack.mallocInt(1);
            assertNotEquals(0, (int) FfxCalls.call(handleAndPointer, kernel32.getFunctionAddress("GetExitCodeThread"),
                    thread, memAddress(exitCode)));
            FfxCalls.call(closeHandle, kernel32.getFunctionAddress("CloseHandle"), thread);
            assertEquals(42, exitCode.get(0));
            assertEquals(3L, count.get());
            assertNotNull(caller.get());
            assertNotSame(Thread.currentThread(), caller.get());
        } finally {
            FfxCalls.freeCallback(callback);
        }
    }
}
