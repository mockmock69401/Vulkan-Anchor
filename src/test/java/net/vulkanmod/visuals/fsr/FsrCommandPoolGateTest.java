package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FSR command list crash (2026-11-02): FSR's command list pool as the render thread reads it before a present, how a list's counter
 * tells free from busy, and the checks that keep the gate off when the memory or the module is not what the layout assumes.
 */
class FsrCommandPoolGateTest {
    private static final long SC = 0x20_0000L, DEVICE = 0xDE00L;

    /** FSR's swapchain object as a sparse map: the pool's family sets and its lists. */
    private static final class Fake implements FsrInterpolationGate.Memory {
        final Map<Long, Long> longs = new HashMap<>();
        final Map<Long, Integer> ints = new HashMap<>();

        Fake(int... families) {
            for (int k = 0; k < FsrCommandPoolGate.FAMILY_SETS; k++) {
                family(k, k < families.length ? families[k] : FsrCommandPoolGate.NO_FAMILY);
            }
        }

        void family(int set, int family) {
            ints.put(SC + FsrCommandPoolGate.OFF_POOL + FsrCommandPoolGate.OFF_FAMILIES + 4L * set, family);
        }

        /** A made list: our device, a command pool, its semaphore and value (semaphore 0: FSR is making it). */
        Fake made(int set, int list, long semaphore, long value) {
            long a = FsrCommandPoolGate.list(SC, set, list);
            longs.put(a + FsrCommandPoolGate.LIST_DEVICE, DEVICE);
            longs.put(a + FsrCommandPoolGate.LIST_COMMAND_POOL, 0xC0L + list);
            longs.put(a + FsrCommandPoolGate.LIST_SEMAPHORE, semaphore);
            longs.put(a + FsrCommandPoolGate.LIST_VALUE, value);
            return this;
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

    @Test void listsAreSixtyFourBytesEightPerFamilyAfterTheFamilySets() {
        assertEquals(SC + 0x28 + 0x38, FsrCommandPoolGate.list(SC, 0, 0));
        assertEquals(SC + 0x28 + 0x38 + 7 * 0x40, FsrCommandPoolGate.list(SC, 0, 7));
        assertEquals(SC + 0x28 + 0x38 + 8 * 0x40, FsrCommandPoolGate.list(SC, 1, 0));
    }

    @Test void aFreeListHasReachedItsValueABusyOneIsOneShort() {
        assertEquals(Boolean.FALSE, FsrCommandPoolGate.busy(42, 42));
        assertEquals(Boolean.TRUE, FsrCommandPoolGate.busy(43, 42));
        assertEquals(Boolean.FALSE, FsrCommandPoolGate.busy(0, 0));
        //FSR takes a list one value at a time and its submit signals exactly that value
        assertNull(FsrCommandPoolGate.busy(44, 42));
        assertNull(FsrCommandPoolGate.busy(42, 43));
    }

    @Test void freeCountsListsNeverMadeAsFree() {
        assertEquals(8, FsrCommandPoolGate.free(0, 0));
        assertEquals(3, FsrCommandPoolGate.free(4, 1));
        assertEquals(0, FsrCommandPoolGate.free(8, 0));
    }

    @Test void readsTheWatchedFamiliesMadeLists() {
        Fake m = new Fake(0, 2).made(0, 0, 0x5A0L, 10).made(0, 3, 0x5A3L, 7).made(0, 5, 0L, 0).made(1, 1, 0x5B1L, 4);
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        FsrCommandPoolGate.Family[] f = c.read(m, SC, DEVICE, 5, new int[]{0, 2});
        assertNotNull(f, c.failure());
        assertEquals(2, f.length);
        assertEquals(0, f[0].family());
        assertArrayEquals(new FsrCommandPoolGate.CommandList[]{new FsrCommandPoolGate.CommandList(0x5A0L, 10),
                new FsrCommandPoolGate.CommandList(0x5A3L, 7)}, f[0].made());
        assertEquals(1, f[0].making());
        assertEquals(2, f[1].family());
        assertEquals(1, f[1].made().length);
    }

    @Test void aWatchedFamilyWithoutASetHasAllListsFree() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        FsrCommandPoolGate.Family[] f = c.read(new Fake(0), SC, DEVICE, 3, new int[]{0, 2});
        assertNotNull(f);
        assertEquals(1, f.length);
        assertEquals(0, f[0].made().length);
    }

    @Test void aFamilyBeyondTheGpusIsImplausible() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        assertNull(c.read(new Fake(0, 9), SC, DEVICE, 3, new int[]{0}));
        assertEquals("set 1 has family 9", c.failure());
        //once failed it stays failed
        assertNull(c.read(new Fake(0), SC, DEVICE, 3, new int[]{0}));
    }

    @Test void aFamilyInTwoSetsIsImplausible() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        assertNull(c.read(new Fake(0, 0), SC, DEVICE, 3, new int[]{0}));
        assertEquals("family 0 is in two sets", c.failure());
    }

    @Test void aListOnAnotherDeviceIsImplausible() {
        Fake m = new Fake(0).made(0, 2, 0x5A2L, 1);
        m.longs.put(FsrCommandPoolGate.list(SC, 0, 2) + FsrCommandPoolGate.LIST_DEVICE, 0xBADL);
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        assertNull(c.read(m, SC, DEVICE, 3, new int[]{0}));
        assertEquals("list 0/2 is not on our device", c.failure());
    }

    @Test void aValueGoingBackIsImplausibleUnlessTheListWasRemade() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        assertNotNull(c.read(new Fake(0).made(0, 0, 0x5A0L, 10), SC, DEVICE, 3, new int[]{0}));
        //remade by FSR: a new semaphore starts over
        assertNotNull(c.read(new Fake(0).made(0, 0, 0x6A0L, 1), SC, DEVICE, 3, new int[]{0}));
        assertNull(c.read(new Fake(0).made(0, 0, 0x6A0L, 0), SC, DEVICE, 3, new int[]{0}));
        assertEquals("list 0/0 went back from 1 to 0", c.failure());
    }

    @Test void takenCountsHowFarTheValuesMoved() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        assertNotNull(c.read(new Fake(0).made(0, 0, 0x5A0L, 10).made(0, 1, 0x5A1L, 4), SC, DEVICE, 3, new int[]{0}));
        assertEquals(0, c.takeTaken());
        assertNotNull(c.read(new Fake(0).made(0, 0, 0x5A0L, 12).made(0, 1, 0x5A1L, 5), SC, DEVICE, 3, new int[]{0}));
        assertEquals(3, c.takeTaken());
        assertEquals(0, c.takeTaken());
        //a remade list starts over without counting
        assertNotNull(c.read(new Fake(0).made(0, 0, 0x6A0L, 1).made(0, 1, 0x5A1L, 5), SC, DEVICE, 3, new int[]{0}));
        assertEquals(0, c.takeTaken());
    }

    @Test void anotherSwapchainStartsOver() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        assertNotNull(c.read(new Fake(0).made(0, 0, 0x5A0L, 10), SC, DEVICE, 3, new int[]{0}));
        c.reset();
        assertNotNull(c.read(new Fake(0).made(0, 0, 0x5A0L, 2), SC, DEVICE, 3, new int[]{0}));
    }

    @Test void noSwapchainReadsNothing() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        assertNull(c.read(new Fake(0), 0L, DEVICE, 3, new int[]{0}));
        assertNull(c.failure());
    }

    @Test void anImplausibleCounterFailsTheChecker() {
        FsrCommandPoolGate.Checker c = new FsrCommandPoolGate.Checker();
        c.implausibleCounter(new FsrCommandPoolGate.CommandList(0x5A0L, 10), 3);
        assertEquals("a list's counter 3 does not fit its value 10", c.failure());
        assertNull(c.read(new Fake(0), SC, DEVICE, 3, new int[]{0}));
    }

    @Test void theBundledDllHoldsTheInstructionsThePoolLayoutComesFrom() throws IOException {
        FsrInterpolationGateTest.Image dll = FsrInterpolationGateTest.Image.map(0x7FF8_1234_0000L, FsrInterpolationGateTest.bundledDll());
        assertNull(FsrCommandPoolGate.codeMismatch(dll, dll.base));
    }

    @Test void aChangedInstructionTurnsTheGateOff() throws IOException {
        FsrInterpolationGateTest.Image dll = FsrInterpolationGateTest.Image.map(0x1_8000_0000L, FsrInterpolationGateTest.bundledDll());
        FsrInterpolationGate.Signature s = FsrCommandPoolGate.SIGNATURES[2];
        dll.bytes[s.rva() + s.bytes().length - 1] ^= 0x01;
        assertEquals("a list's availability at RVA 0x2819e", FsrCommandPoolGate.codeMismatch(dll, dll.base));
    }
}
