package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FSR uncapped hang (2026-10-25): the wait FSR's next interpolation puts on the game queue, read from FSR's swapchain object, so
 * the render thread can let that wait's value be signalled before FSR submits it (no wait-before-signal); and the checks that keep
 * the gate off when the memory does not have the layout the offsets assume.
 */
class FsrInterpolationGateTest {
    private static final long SC = 0x10_0000L, PRESENT_SEM = 0xAAA0L, COMPOSITION_SEM = 0xBBB0L;

    /** FSR's swapchain object as a sparse map of 8- and 4-byte fields. */
    private static final class Fake implements FsrInterpolationGate.Memory {
        final Map<Long, Long> longs = new HashMap<>();
        final Map<Long, Integer> ints = new HashMap<>();

        Fake(int mode, int bufferIndex, long avail0, long avail1) {
            ints.put(SC + FsrInterpolationGate.OFF_COMPOSITION_MODE, mode);
            longs.put(SC + FsrInterpolationGate.OFF_PRESENT_SEMAPHORE, PRESENT_SEM);
            longs.put(SC + FsrInterpolationGate.OFF_COMPOSITION_SEMAPHORE, COMPOSITION_SEM);
            avail(0, avail0);
            avail(1, avail1);
            frame(bufferIndex, 1_000_000L);
        }

        void avail(int output, long value) {
            longs.put(SC + FsrInterpolationGate.OFF_INTERPOLATION_OUTPUT_AVAILABILITY + (long) output * FsrInterpolationGate.INTERPOLATION_OUTPUT_STRIDE, value);
        }

        /** FSR's state before a present: presentCount (its low bit is the output index) and framesSentForPresentation. */
        void frame(long presentCount, long framesSent) {
            longs.put(SC + FsrInterpolationGate.OFF_PRESENT_COUNT, presentCount);
            ints.put(SC + FsrInterpolationGate.OFF_INTERPOLATION_BUFFER_INDEX, (int) (presentCount & 1));
            longs.put(SC + FsrInterpolationGate.OFF_FRAMES_SENT, framesSent);
        }

        @Override
        public long getLong(long address) {
            return longs.getOrDefault(address, 0L);
        }

        @Override
        public int getInt(long address) {
            return ints.getOrDefault(address, 0);
        }

        @Override
        public byte getByte(long address) {
            return 0;
        }
    }

    /** A module mapped at base: the PE image as the Windows loader lays it out (headers, then each section at its RVA). */
    private static final class Image implements FsrInterpolationGate.Memory {
        final long base;
        final byte[] bytes;

        Image(long base, byte[] bytes) {
            this.base = base;
            this.bytes = bytes;
        }

        static Image map(long base, byte[] file) {
            ByteBuffer f = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            int pe = f.getInt(0x3C);
            int sections = f.getShort(pe + 6) & 0xFFFF;
            int optional = pe + 24;
            int sectionTable = optional + (f.getShort(pe + 20) & 0xFFFF);
            byte[] image = new byte[f.getInt(optional + 56)];
            System.arraycopy(file, 0, image, 0, f.getInt(optional + 60));//SizeOfHeaders
            for (int i = 0; i < sections; i++) {
                int s = sectionTable + i * 40;
                int va = f.getInt(s + 12), rawSize = f.getInt(s + 16), raw = f.getInt(s + 20);
                System.arraycopy(file, raw, image, va, Math.min(rawSize, image.length - va));
            }
            return new Image(base, image);
        }

        ByteBuffer at(long address, int size) {
            return ByteBuffer.wrap(bytes, (int) (address - base), size).order(ByteOrder.LITTLE_ENDIAN);
        }

        @Override
        public long getLong(long address) {
            return at(address, 8).getLong();
        }

        @Override
        public int getInt(long address) {
            return at(address, 4).getInt();
        }

        @Override
        public byte getByte(long address) {
            return bytes[(int) (address - base)];
        }
    }

    private static byte[] bundledDll() throws IOException {
        try (InputStream in = FsrInterpolationGateTest.class.getResourceAsStream(FsrNative.RESOURCE)) {
            assertNotNull(in, "the bundled DLL is on the test classpath");
            return in.readAllBytes();
        }
    }

    @Test void composingOnTheGameQueueTheInterpolationWaitsForThePresentThatFreedItsOutput() {
        //FSR's copy-and-present mode (FsrSwapchain forces it): the output is free once the presenter signalled its present index
        Fake m = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 1, 5151, 5152);
        assertEquals(new FsrInterpolationGate.Wait(PRESENT_SEM, 5152), FsrInterpolationGate.nextInterpolationWait(m, SC));
        Fake other = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 5153, 5152);
        assertEquals(new FsrInterpolationGate.Wait(PRESENT_SEM, 5153), FsrInterpolationGate.nextInterpolationWait(other, SC));
    }

    @Test void composingOnThePresentQueueItWaitsForTheComposition() {
        Fake m = new Fake(FsrInterpolationGate.MODE_PRESENT_QUEUE, 0, 40, 42);
        assertEquals(new FsrInterpolationGate.Wait(COMPOSITION_SEM, 40), FsrInterpolationGate.nextInterpolationWait(m, SC));
    }

    @Test void anythingUnexpectedMeansNoWait() {
        assertNull(FsrInterpolationGate.nextInterpolationWait(new Fake(0, 0, 1, 2), SC), "unknown composition mode");
        assertNull(FsrInterpolationGate.nextInterpolationWait(new Fake(3, 0, 1, 2), SC), "unknown composition mode");
        Fake third = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 1, 2);
        third.ints.put(SC + FsrInterpolationGate.OFF_INTERPOLATION_BUFFER_INDEX, 2);
        assertNull(FsrInterpolationGate.nextInterpolationWait(third, SC), "no third output");
        third.ints.put(SC + FsrInterpolationGate.OFF_INTERPOLATION_BUFFER_INDEX, -1);
        assertNull(FsrInterpolationGate.nextInterpolationWait(third, SC));
        Fake noSemaphore = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 1, 2);
        noSemaphore.longs.put(SC + FsrInterpolationGate.OFF_PRESENT_SEMAPHORE, 0L);
        assertNull(FsrInterpolationGate.nextInterpolationWait(noSemaphore, SC));
        assertNull(FsrInterpolationGate.nextInterpolationWait(new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 0, 0), SC), "nothing presented yet");
        assertNull(FsrInterpolationGate.nextInterpolationWait(new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 1, 2), 0L), "no swapchain");
    }

    @Test void theOffsetsAreTheBundledDllsOnly() {
        //read from the disassembly of this exact amd_fidelityfx_vk.dll: a new DLL needs them read again
        assertEquals(FsrNative.SHA256, FsrInterpolationGate.DLL_SHA256);
    }

    @Test void theBundledDllHoldsTheInstructionsTheOffsetsComeFrom() throws IOException {
        //the same check the game makes on the loaded module before it uses the gate
        Image dll = Image.map(0x7FF8_1234_0000L, bundledDll());
        assertNull(FsrInterpolationGate.codeMismatch(dll, dll.base));
        assertEquals(dll.bytes.length, FsrInterpolationGate.imageSize(dll, dll.base));
        //each field the gate reads appears in the instructions it was read from (mode, both semaphores, index, availability, counts)
        String code = java.util.HexFormat.ofDelimiter(" ").formatHex(concat()).toUpperCase();
        for (String pattern : new String[]{"8B 87 70 0E 00 00 83 F8 01", "48 8B 8F A0 0D 00 00", "48 8B 8F 90 0D 00 00",
                "8B 87 04 12 00 00 48 C1 E0 06 48 8B 94 38 58 11 00 00", "83 E2 01 89 97 04 12 00 00", "48 FF 87 08 12 00 00",
                "48 8B 87 58 12 00 00"}) {
            assertTrue(code.contains(pattern), pattern);
        }
    }

    private static byte[] concat() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (FsrInterpolationGate.Signature s : FsrInterpolationGate.SIGNATURES) out.writeBytes(s.bytes());
        return out.toByteArray();
    }

    @Test void aModuleWithOtherCodeIsRefused() throws IOException {
        Image dll = Image.map(0x1_8000_0000L, bundledDll());
        FsrInterpolationGate.Signature s = FsrInterpolationGate.SIGNATURES[3];
        dll.bytes[s.rva() + s.bytes().length - 1] ^= 0x01;
        assertEquals("presentCount and the buffer index at RVA 0x2c1b1", FsrInterpolationGate.codeMismatch(dll, dll.base));
        assertEquals(0L, FsrInterpolationGate.imageSize(new Image(0x1000L, new byte[0x100]), 0x1000L), "no PE image there");
        assertEquals(0L, FsrInterpolationGate.imageSize(dll, 0L));
    }

    @Test void functionsMustLieInsideTheModule() {
        long base = 0x7FF8_0000_0000L, size = 0x8E9000L;
        assertTrue(FsrInterpolationGate.inImage(base, size, base + 0x1000, base + size - 1));
        assertFalse(FsrInterpolationGate.inImage(base, size, base + 0x1000, base + size));
        assertFalse(FsrInterpolationGate.inImage(base, size, base - 1));
        assertFalse(FsrInterpolationGate.inImage(base, size, 0L));
        assertFalse(FsrInterpolationGate.inImage(base, 0L, base));
    }

    @Test void plausibleFramesPassTheChecker() {
        FsrInterpolationGate.Checker c = new FsrInterpolationGate.Checker();
        Fake m = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 0, 0);
        //frames 100..103, two presents each: output (presentCount & 1) was freed by the present of two frames before
        long sent = 199;
        for (long k = 100; k < 104; k++) {
            m.frame(k, sent);
            m.avail((int) (k & 1), sent - 2);
            assertEquals(new FsrInterpolationGate.Wait(PRESENT_SEM, sent - 2), c.next(m, SC), "frame " + k);
            sent += 2;
        }
        //a frame without generation leaves its output as it was: the other output's older value is no step back
        m.frame(104, sent + 1);
        assertNotNull(c.next(m, SC));
        assertNull(c.failure());
    }

    @Test void implausibleFramesTurnTheGateOff() {
        //semaphore changed for the same swapchain
        FsrInterpolationGate.Checker c = new FsrInterpolationGate.Checker();
        Fake m = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 10, 12);
        m.frame(6, 14);
        assertNotNull(c.next(m, SC));
        m.longs.put(SC + FsrInterpolationGate.OFF_PRESENT_SEMAPHORE, 0xCCC0L);
        assertNull(c.next(m, SC));
        assertEquals("the semaphore changed", c.failure());
        m.longs.put(SC + FsrInterpolationGate.OFF_PRESENT_SEMAPHORE, PRESENT_SEM);
        assertNull(c.next(m, SC), "a checker that failed stays off");

        //index is not presentCount & 1
        c = new FsrInterpolationGate.Checker();
        m = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 10, 12);
        m.frame(6, 14);
        m.ints.put(SC + FsrInterpolationGate.OFF_INTERPOLATION_BUFFER_INDEX, 1);
        assertNull(c.next(m, SC));
        assertNotNull(c.failure());

        //value above framesSentForPresentation
        c = new FsrInterpolationGate.Checker();
        m = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 10, 12);
        m.frame(6, 9);
        assertNull(c.next(m, SC));
        assertNotNull(c.failure());

        //an output's value went back
        c = new FsrInterpolationGate.Checker();
        m = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 10, 12);
        m.frame(6, 14);
        assertNotNull(c.next(m, SC));
        m.avail(0, 8);
        assertNull(c.next(m, SC));
        assertNotNull(c.failure());
    }

    @Test void aNewSwapchainStartsTheCheckerOver() {
        FsrInterpolationGate.Checker c = new FsrInterpolationGate.Checker();
        Fake m = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 500, 502);
        m.frame(250, 503);
        assertNotNull(c.next(m, SC));
        //FSR remade its swapchain at the same address (resize): new semaphore, counts from the start
        c.reset();
        Fake fresh = new Fake(FsrInterpolationGate.MODE_GAME_QUEUE, 0, 3, 5);
        fresh.longs.put(SC + FsrInterpolationGate.OFF_PRESENT_SEMAPHORE, 0xDDD0L);
        fresh.frame(4, 7);
        assertEquals(new FsrInterpolationGate.Wait(0xDDD0L, 3), c.next(fresh, SC));
        assertNull(c.failure());
    }
}
