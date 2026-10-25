package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.VisualsLog;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libffi.FFICIF;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.APIUtil.apiClosureRet;
import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.Pointer.POINTER_SIZE;
import static org.lwjgl.system.libffi.LibFFI.*;

/**
 * FSR design 4.3: calls into the Vulkan functions FSR's swapchain replaces, and the queue submit callback FSR calls
 * back. Function pointers returned by ffxQuery are called through libffi with one prepared call interface per
 * signature; handles are 64-bit (pointers or uint64) on x64. All calls are safe from any thread.
 */
public final class FfxCalls {
    /** PFN_vkQueueSubmitFFXAPI: FSR submits its own work to a queue the game owns through this. */
    @FunctionalInterface
    public interface QueueSubmitter {
        /** vkQueueSubmit without the queue; returns a VkResult. Called from FSR's native threads, possibly concurrently. */
        int submit(int submitCount, long pSubmits, long fence);
    }

    /** ffxApiMessage: FSR's debug checks report through this (FfxApiMsgType, the text). Called from any of FSR's threads. */
    @FunctionalInterface
    public interface MessageSink {
        void message(int type, String text);
    }

    private static final int VK_ERROR_UNKNOWN = -13;

    // VkResult vkAcquireNextImageKHR(VkDevice, VkSwapchainKHR, uint64_t timeout, VkSemaphore, VkFence, uint32_t*)
    private static final FFICIF ACQUIRE_NEXT_IMAGE = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32,
            ffi_type_pointer, ffi_type_uint64, ffi_type_uint64, ffi_type_uint64, ffi_type_uint64, ffi_type_pointer);
    // VkResult vkQueuePresentKHR(VkQueue, const VkPresentInfoKHR*)
    private static final FFICIF QUEUE_PRESENT = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32,
            ffi_type_pointer, ffi_type_pointer);
    // VkResult vkGetSwapchainImagesKHR(VkDevice, VkSwapchainKHR, uint32_t*, VkImage*)
    private static final FFICIF GET_SWAPCHAIN_IMAGES = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32,
            ffi_type_pointer, ffi_type_uint64, ffi_type_pointer, ffi_type_pointer);
    // VkResult PFN_vkCreateSwapchainFFXAPI(VkDevice, const VkSwapchainCreateInfoKHR*, const VkAllocationCallbacks*, VkSwapchainKHR*, void*)
    private static final FFICIF CREATE_SWAPCHAIN = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32,
            ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer);
    // void PFN_vkDestroySwapchainFFXAPI(VkDevice, VkSwapchainKHR, const VkAllocationCallbacks*, void*)
    private static final FFICIF DESTROY_SWAPCHAIN = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_void,
            ffi_type_pointer, ffi_type_uint64, ffi_type_pointer, ffi_type_pointer);
    // uint64_t PFN_getLastPresentCountFFXAPI(VkSwapchainKHR)
    private static final FFICIF LAST_PRESENT_COUNT = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_uint64,
            ffi_type_uint64);
    // VkResult PFN_vkQueueSubmitFFXAPI(uint32_t submitCount, const VkSubmitInfo*, VkFence)
    static final FFICIF QUEUE_SUBMIT = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_sint32,
            ffi_type_uint32, ffi_type_pointer, ffi_type_uint64);

    // void ffxApiMessage(uint32_t type, const wchar_t* message)
    static final FFICIF MESSAGE = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_void,
            ffi_type_uint32, ffi_type_pointer);

    /** Live submit callbacks by closure address; the strong reference keeps each alive while FSR holds its address. */
    private static final Map<Long, SubmitCallback> CALLBACKS = new ConcurrentHashMap<>();

    private FfxCalls() {}

    public static int acquireNextImage(long fn, long device, long swapchain, long timeout, long semaphore, long fence, long pIndex) {
        return (int) call(ACQUIRE_NEXT_IMAGE, fn, device, swapchain, timeout, semaphore, fence, pIndex);
    }

    public static int queuePresent(long fn, long queue, long pPresentInfo) {
        return (int) call(QUEUE_PRESENT, fn, queue, pPresentInfo);
    }

    public static int getSwapchainImages(long fn, long device, long swapchain, long pCount, long pImages) {
        return (int) call(GET_SWAPCHAIN_IMAGES, fn, device, swapchain, pCount, pImages);
    }

    public static int createSwapchain(long fn, long device, long pCreateInfo, long pAllocator, long pSwapchain, long pContext) {
        return (int) call(CREATE_SWAPCHAIN, fn, device, pCreateInfo, pAllocator, pSwapchain, pContext);
    }

    public static void destroySwapchain(long fn, long device, long swapchain, long pAllocator, long pContext) {
        call(DESTROY_SWAPCHAIN, fn, device, swapchain, pAllocator, pContext);
    }

    public static long lastPresentCount(long fn, long swapchain) {
        return call(LAST_PRESENT_COUNT, fn, swapchain);
    }

    /** A native PFN_vkQueueSubmitFFXAPI that calls {@code s}; stays valid until {@link #freeCallback}. */
    public static long submitCallback(QueueSubmitter s) {
        SubmitCallback callback = new SubmitCallback(s);
        CALLBACKS.put(callback.address(), callback);
        return callback.address();
    }

    /** Frees a closure made by {@link #submitCallback}; unknown or already freed addresses are ignored. */
    public static void freeCallback(long address) {
        SubmitCallback callback = CALLBACKS.remove(address);
        if (callback != null) {
            callback.free();
        }
    }

    /**
     * A native ffxApiMessage that calls {@code sink}. FSR keeps the debug message function for as long as the DLL is loaded, so the
     * closure is never freed (one per session).
     */
    public static long messageCallback(MessageSink sink) {
        return new MessageCallback(sink).address();
    }

    /**
     * Calls {@code fn} with every argument widened to 64 bits; libffi reads each argument at the width its call
     * interface declares (little-endian, so a uint32 is the low half). Returns the raw 64-bit return slot.
     * A NULL {@code fn} throws instead of crashing the process.
     */
    static long call(FFICIF cif, long fn, long... args) {
        if (fn == NULL) {
            throw new FsrException("FSR returned no function to call");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long values = stack.nmalloc(8, args.length * 8);
            long pointers = stack.nmalloc(POINTER_SIZE, args.length * POINTER_SIZE);
            for (int i = 0; i < args.length; i++) {
                memPutLong(values + i * 8L, args[i]);
                memPutAddress(pointers + (long) i * POINTER_SIZE, values + i * 8L);
            }
            long ret = stack.nmalloc(8, 8);
            memPutLong(ret, 0L);
            nffi_call(cif.address(), fn, ret, pointers);
            return memGetLong(ret);
        }
    }

    /** LWJGL's closure machinery attaches FSR's native threads to the JVM for the duration of each call. */
    private static final class SubmitCallback extends Callback implements CallbackI {
        private final QueueSubmitter submitter;

        SubmitCallback(QueueSubmitter submitter) {
            super(QUEUE_SUBMIT);
            this.submitter = submitter;
        }

        @Override
        public FFICIF getCallInterface() {
            return QUEUE_SUBMIT;
        }

        @Override
        public void callback(long ret, long args) {
            int result;
            try {
                result = submitter.submit(
                        memGetInt(memGetAddress(args)),
                        memGetAddress(memGetAddress(args + POINTER_SIZE)),
                        memGetLong(memGetAddress(args + 2L * POINTER_SIZE)));
            } catch (Throwable t) {
                //an exception must not unwind into FSR's native thread
                VisualsLog.error("Visuals FSR: queue submit callback failed", t);
                result = VK_ERROR_UNKNOWN;
            }
            apiClosureRet(ret, result);
        }
    }

    private static final class MessageCallback extends Callback implements CallbackI {
        private final MessageSink sink;

        MessageCallback(MessageSink sink) {
            super(MESSAGE);
            this.sink = sink;
        }

        @Override
        public FFICIF getCallInterface() {
            return MESSAGE;
        }

        @Override
        public void callback(long ret, long args) {
            try {
                long text = memGetAddress(memGetAddress(args + POINTER_SIZE));
                //wchar_t is UTF-16 on Windows
                sink.message(memGetInt(memGetAddress(args)), text == NULL ? "" : memUTF16(text));
            } catch (Throwable t) {
                //an exception must not unwind into FSR's native thread
                VisualsLog.error("Visuals FSR: debug message callback failed", t);
            }
        }
    }
}
