package net.vulkanmod.visuals.fsr;

import java.util.HexFormat;

/**
 * FSR uncapped hang (2026-10-25, hang-debug-report.md): FSR's swapchain reuses two interpolation outputs, so the interpolation it
 * submits to the game queue inside the present of frame K waits on a timeline semaphore for the present of frame K-2's generated
 * frame, which its presenter thread signals (FrameInterpolationSwapchainVK.cpp, dispatchInterpolationCommands). When the render
 * thread runs one present ahead of the presenter thread (after the frame time drops, e.g. a render distance change), that wait
 * reaches the game queue before its signal was submitted: a wait-before-signal. With one pending, NVIDIA's vkQueuePresentKHR on
 * this machine blocks in the kernel (NtGdiDdDDIPresent), and with it the host-side signal, so the presenter thread never submits
 * the signal: a permanent freeze with an idle GPU. Before FSR's present of a frame that generates, the render thread waits until
 * the semaphore value this wait needs is reached, so FSR never submits a wait-before-signal.
 *
 * <p>The value and the semaphore are not in FSR's API: they are read from FSR's swapchain object (FSR's VkSwapchainKHR is a
 * FrameInterpolationSwapChainVK*), at offsets read from the disassembly of the bundled DLL ({@link #DLL_SHA256}; FsrNative loads no
 * other). Because a wrong layout would hand garbage to the driver, the gate is only used when (a) the loaded module holds, at the
 * RVAs the offsets were read from, exactly the instructions they were read from ({@link #SIGNATURES}), and (b) FSR's replacement
 * functions lie inside that module; and every frame's wait must be plausible ({@link Checker}). Otherwise the gate is off and FSR
 * runs as before it. Pure: FsrSwapchain reads the memory and waits.
 */
public final class FsrInterpolationGate {
    /** amd_fidelityfx_vk.dll (FidelityFX SDK v1.1.4) the offsets below were read from; equals {@link FsrNative#SHA256}. */
    static final String DLL_SHA256 = "a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78";
    /** presentInfo.compositionMode (int): {@link #MODE_PRESENT_QUEUE} or {@link #MODE_GAME_QUEUE}. */
    static final int OFF_COMPOSITION_MODE = 0xE70;
    static final int MODE_PRESENT_QUEUE = 1, MODE_GAME_QUEUE = 2;
    /** presentInfo.presentSemaphore and presentInfo.compositionSemaphore (VkSemaphore, timeline). */
    static final int OFF_PRESENT_SEMAPHORE = 0xD90, OFF_COMPOSITION_SEMAPHORE = 0xDA0;
    /** interpolationBufferIndex (int): the output the next interpolation writes; always presentCount % 2. */
    static final int OFF_INTERPOLATION_BUFFER_INDEX = 0x1204;
    /** interpolationOutputs[0].availabilitySemaphoreValue (uint64); the outputs are INTERPOLATION_OUTPUT_STRIDE bytes apart. */
    static final int OFF_INTERPOLATION_OUTPUT_AVAILABILITY = 0x1158, INTERPOLATION_OUTPUT_STRIDE = 0x40, INTERPOLATION_OUTPUTS = 2;
    /** presentCount and framesSentForPresentation (uint64), for the plausibility checks. */
    static final int OFF_PRESENT_COUNT = 0x1208, OFF_FRAMES_SENT = 0x1258;

    /** Code bytes of the bundled DLL at an RVA (relocation-free). */
    record Signature(int rva, byte[] bytes, String what) {
        Signature(int rva, String hex, String what) {
            this(rva, HexFormat.ofDelimiter(" ").parseHex(hex), what);
        }
    }

    /** The instructions every offset above was read from (dumpbin /disasm of the bundled DLL). */
    static final Signature[] SIGNATURES = {
            //dispatchInterpolationCommands: mode == 1 ? compositionSemaphore : mode == 2 ? presentSemaphore; then
            // interpolationOutputs[interpolationBufferIndex].availabilitySemaphoreValue
            new Signature(0x27556, "8B 87 70 0E 00 00 83 F8 01 75 09 48 8B 8F A0 0D 00 00 EB 0C 83 F8 02 75 45 48 8B 8F 90 0D 00 00 "
                    + "8B 87 04 12 00 00 48 C1 E0 06 48 8B 94 38 58 11 00 00", "the interpolation's wait"),
            //presentInterpolated: interpolationOutputs[interpolationBufferIndex].availabilitySemaphoreValue = ...; mode == 2
            new Signature(0x2A142, "8B 86 04 12 00 00 48 C1 E0 06 4A 8D 0C 0A 44 89 45 70 48 89 8C 30 58 11 00 00 83 BE 70 0E 00 00 02",
                    "the availability write"),
            //queuePresent: framesSentForPresentation
            new Signature(0x2C182, "48 8B 87 58 12 00 00", "framesSentForPresentation"),
            //queuePresent: presentCount++; interpolationBufferIndex = presentCount & 1
            new Signature(0x2C1B1, "48 FF 87 08 12 00 00 48 8B 87 08 12 00 00 44 8B 87 BC 0F 00 00 8B D0 83 E2 01 89 97 04 12 00 00",
                    "presentCount and the buffer index"),
    };

    private FsrInterpolationGate() {
    }

    /** Reads process memory (MemoryUtil in the game, a map or a byte array in tests). */
    interface Memory {
        long getLong(long address);

        int getInt(long address);

        byte getByte(long address);
    }

    /** A timeline semaphore and the value FSR's next interpolation waits for. */
    record Wait(long semaphore, long value) {
    }

    /** The first signature the module at imageBase does not hold, or null when it holds them all. */
    static String codeMismatch(Memory m, long imageBase) {
        return codeMismatch(m, imageBase, SIGNATURES);
    }

    /** The first of signatures the module at imageBase does not hold, or null when it holds them all. */
    static String codeMismatch(Memory m, long imageBase, Signature[] signatures) {
        for (Signature s : signatures) {
            for (int i = 0; i < s.bytes().length; i++) {
                if (m.getByte(imageBase + s.rva() + i) != s.bytes()[i]) return s.what() + " at RVA 0x" + Integer.toHexString(s.rva());
            }
        }
        return null;
    }

    /** SizeOfImage from the PE headers of a loaded module, 0 when imageBase holds no PE image. */
    static long imageSize(Memory m, long imageBase) {
        if (imageBase == 0L || (m.getByte(imageBase) != 'M' || m.getByte(imageBase + 1) != 'Z')) return 0L;
        long pe = imageBase + (m.getInt(imageBase + 0x3C) & 0xFFFFFFFFL);
        if (m.getInt(pe) != 0x00004550) return 0L;//"PE\0\0"
        return m.getInt(pe + 24 + 56) & 0xFFFFFFFFL;
    }

    /** Whether every address lies inside [imageBase, imageBase + imageSize). */
    static boolean inImage(long imageBase, long imageSize, long... addresses) {
        if (imageSize <= 0L) return false;
        for (long a : addresses) {
            if (Long.compareUnsigned(a, imageBase) < 0 || Long.compareUnsigned(a - imageBase, imageSize) >= 0) return false;
        }
        return true;
    }

    /**
     * The wait FSR's next interpolation will submit: on the present semaphore when FSR composes on the game queue, on the
     * composition semaphore when it composes on its present queue, for the current output's availability value. Null without a
     * swapchain, for an unknown mode or output index, without a semaphore, or before anything was presented (value 0).
     */
    static Wait nextInterpolationWait(Memory m, long swapchain) {
        if (swapchain == 0L) return null;
        int mode = m.getInt(swapchain + OFF_COMPOSITION_MODE);
        long semaphore = switch (mode) {
            case MODE_GAME_QUEUE -> m.getLong(swapchain + OFF_PRESENT_SEMAPHORE);
            case MODE_PRESENT_QUEUE -> m.getLong(swapchain + OFF_COMPOSITION_SEMAPHORE);
            default -> 0L;
        };
        int index = m.getInt(swapchain + OFF_INTERPOLATION_BUFFER_INDEX);
        if (semaphore == 0L || index < 0 || index >= INTERPOLATION_OUTPUTS) return null;
        long value = m.getLong(swapchain + OFF_INTERPOLATION_OUTPUT_AVAILABILITY + (long) index * INTERPOLATION_OUTPUT_STRIDE);
        return value == 0L ? null : new Wait(semaphore, value);
    }

    /**
     * Per-frame plausibility of what {@link #nextInterpolationWait} reads, for one swapchain at a time: the semaphore never
     * changes, the output index is presentCount &amp; 1, 0 &lt; value &lt;= framesSentForPresentation, and each output's value
     * never decreases. A violation is reported once ({@link #failure}); the caller then turns the gate off.
     */
    static final class Checker {
        private long swapchain;
        private long semaphore;
        private final long[] lastValue = new long[INTERPOLATION_OUTPUTS];
        private String failure;

        /** A new FSR swapchain (FSR started, resized or remade): nothing is known about it yet. */
        void reset() {
            swapchain = 0L;
            semaphore = 0L;
            java.util.Arrays.fill(lastValue, 0L);
        }

        /** The violation found, or null. */
        String failure() {
            return failure;
        }

        /** The wait to make before this frame's present, or null (nothing to wait for, or a violation: see {@link #failure}). */
        Wait next(Memory m, long sc) {
            if (failure != null) return null;
            Wait w = nextInterpolationWait(m, sc);
            if (w == null) return null;
            if (sc != swapchain) {
                reset();
                swapchain = sc;
                semaphore = w.semaphore();
            } else if (w.semaphore() != semaphore) {
                return fail("the semaphore changed");
            }
            int index = m.getInt(sc + OFF_INTERPOLATION_BUFFER_INDEX);
            if (index != (int) (m.getLong(sc + OFF_PRESENT_COUNT) & 1L)) return fail("the output index is not presentCount & 1");
            if (w.value() <= 0L || w.value() > m.getLong(sc + OFF_FRAMES_SENT)) return fail("the value " + w.value() + " is out of range");
            if (w.value() < lastValue[index]) return fail("the value went back from " + lastValue[index] + " to " + w.value());
            lastValue[index] = w.value();
            return w;
        }

        private Wait fail(String why) {
            failure = why;
            return null;
        }
    }
}
