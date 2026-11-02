package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.fsr.FsrInterpolationGate.Memory;
import net.vulkanmod.visuals.fsr.FsrInterpolationGate.Signature;

import java.util.Arrays;

/**
 * FSR command list crash (2026-11-02, BMC5: EXCEPTION_ACCESS_VIOLATION at amd_fidelityfx_vk.dll+0x281e9). Every command list FSR's
 * swapchain records (the game queue's composition and interpolation, the presenter thread's passthrough, UI composition and ownership
 * transfers) comes from one pool in its swapchain object, eight per queue family. A list is free once its timeline semaphore reached
 * the value of its last submit; taking one increments that value. With all eight of a family busy FSR takes none and writes through
 * NULL. Our game, present and acquire queues could share one family (the RTX 5070: all in family 0), where one present takes up to
 * 7 lists together with the presenter thread; FsrQueuePlan.SEPARATE_PRESENT_FAMILY now gives the present queue a family of its own.
 * Before FSR's present the render thread still waits until a few lists of each of FSR's families are free, and lists that never
 * free turn FSR off at the next recreation.
 *
 * <p>Like {@link FsrInterpolationGate}, the pool is not in FSR's API: its layout was read from the disassembly of the bundled DLL
 * (FsrInterpolationGate.DLL_SHA256), and the gate is only used when the loaded module holds exactly those instructions
 * ({@link #SIGNATURES}) and every read is plausible ({@link Checker}). Pure: FsrSwapchain reads the memory, the counters and waits.
 */
public final class FsrCommandPoolGate {
    /** The pool inside FSR's swapchain object (FrameInterpolationSwapChainVK*). */
    static final int OFF_POOL = 0x28;
    /** The pool's queue family per slot set (uint32, NO_FAMILY when unused). */
    static final int OFF_FAMILIES = 0x28, FAMILY_SETS = 3;
    static final int NO_FAMILY = -1;
    /** The command lists: set k's list i at OFF_LISTS + (k * LISTS + i) * LIST_STRIDE. */
    static final int OFF_LISTS = 0x38, LISTS = 8, LIST_STRIDE = 0x40;
    /** A list's VkDevice, VkCommandPool (0 until made), timeline VkSemaphore and the value its last submit signals (uint64). */
    static final int LIST_DEVICE = 0x00, LIST_COMMAND_POOL = 0x20, LIST_SEMAPHORE = 0x30, LIST_VALUE = 0x38;

    /** The instructions the layout above was read from (dumpbin /disasm of the bundled DLL). */
    static final Signature[] SIGNATURES = {
            //the pool's get: the slot set whose family matches (or the first unused), of FAMILY_SETS at OFF_FAMILIES
            new Signature(0x28063, "48 8D 43 28 45 8B F0 66 0F 1F 44 00 00 8B 10 41 3B D5 74 1C 83 FA FF 74 0F 41 FF C6 48 83 C0 04 "
                    + "41 83 FE 03", "the pool's family sets"),
            //the list of the set: OFF_LISTS + (set * LISTS + i) * LIST_STRIDE, made when its command pool is 0
            new Signature(0x280AD, "48 83 C3 38 BF F3 FF FF FF 49 8D 0C C7 48 C1 E1 06 48 03 D9 48 8B 43 20 48 85 C0",
                    "the pool's lists"),
            //free when vkGetSemaphoreCounterValue(device, semaphore) >= value, of LISTS; the one taken gets value + 1
            new Signature(0x2819E, "48 8B 53 30 4C 8D 45 A0 48 8B 0B 48 C7 45 A0 00 00 00 00 E8 B6 51 01 00 85 C0 75 11 48 8B 43 38 "
                    + "48 39 45 A0 0F 93 C0 84 C0 48 0F 45 F3 48 8B 5D 40 49 FF C7 41 B8 00 00 00 00 49 83 FF 08 0F 82 BF FE FF FF "
                    + "48 8B 45 50 48 8B CB 48 FF 46 38", "a list's availability"),
            //presentPassthrough: the pool at OFF_POOL of the swapchain object
            new Signature(0x2B87E, "48 8B 97 40 0C 00 00 F2 0F 10 8F 60 0D 00 00 4C 8D 44 24 60 48 8D 4F 28 0F 29 44 24 60 F2 0F 11 "
                    + "4C 24 70 E8 8A C7 FF FF", "the pool in the swapchain"),
    };

    private FsrCommandPoolGate() {
    }

    /** The first signature the module at imageBase does not hold, or null when it holds them all. */
    static String codeMismatch(Memory m, long imageBase) {
        return FsrInterpolationGate.codeMismatch(m, imageBase, SIGNATURES);
    }

    /** The address of set's list in the pool of FSR's swapchain object sc. */
    static long list(long sc, int set, int list) {
        return sc + OFF_POOL + OFF_LISTS + (long) (set * LISTS + list) * LIST_STRIDE;
    }

    /** A made list's timeline semaphore and the value it is free at. */
    record CommandList(long semaphore, long value) {
    }

    /**
     * One family's lists as read: those that are made and can be waited on, and how many are being made right now (a command pool
     * but no semaphore yet: FSR takes it, so it counts as busy). Lists never made are free.
     */
    record Family(int family, CommandList[] made, int making) {
    }

    /**
     * Whether a made list is busy, given the counter of its semaphore: FSR increments the value when it takes the list and its submit
     * signals that value, so a free list has counter == value and a busy one counter == value - 1. Null for anything else.
     */
    static Boolean busy(long value, long counter) {
        if (counter == value) return Boolean.FALSE;
        if (counter == value - 1) return Boolean.TRUE;
        return null;
    }

    /** The free lists of a family with busy made lists and making lists being made (lists never made are free). */
    static int free(int busy, int making) {
        return LISTS - busy - making;
    }

    /**
     * Per-frame reads and their plausibility for one swapchain at a time: each family set is unused or a family below the GPU's
     * family count, no family is in two sets, a made list belongs to our device, and a list's value never goes back while its
     * semaphore stays (a list FSR remade gets a new semaphore and starts over). A violation is reported once ({@link #failure}).
     */
    static final class Checker {
        private long swapchain;
        private final long[] semaphores = new long[FAMILY_SETS * LISTS];
        private final long[] values = new long[FAMILY_SETS * LISTS];
        private String failure;
        /** Lists FSR took in the watched families since the last {@link #takeTaken} (their values moved on), for the pilot. */
        private long taken;

        long takeTaken() {
            long t = taken;
            taken = 0L;
            return t;
        }

        void reset() {
            swapchain = 0L;
            Arrays.fill(semaphores, 0L);
            Arrays.fill(values, 0L);
        }

        String failure() {
            return failure;
        }

        /**
         * The lists of each of watched (queue families) that FSR made in the pool of sc; a watched family FSR has no set for yet
         * is left out (all its lists are free). Null on a violation ({@link #failure}) or without a swapchain.
         */
        Family[] read(Memory m, long sc, long device, int familyCount, int[] watched) {
            if (failure != null || sc == 0L) return null;
            if (sc != swapchain) {
                reset();
                swapchain = sc;
            }
            int[] sets = new int[FAMILY_SETS];
            for (int k = 0; k < FAMILY_SETS; k++) {
                int family = m.getInt(sc + OFF_POOL + OFF_FAMILIES + 4L * k);
                if (family != NO_FAMILY && (family < 0 || family >= familyCount)) return fail("set " + k + " has family " + family);
                for (int j = 0; j < k; j++) {
                    if (family != NO_FAMILY && sets[j] == family) return fail("family " + family + " is in two sets");
                }
                sets[k] = family;
            }
            Family[] out = new Family[watched.length];
            int n = 0;
            for (int family : watched) {
                int k = indexOf(sets, family);
                if (k < 0) continue;
                CommandList[] made = new CommandList[LISTS];
                int count = 0;
                int making = 0;
                for (int i = 0; i < LISTS; i++) {
                    long a = list(sc, k, i);
                    if (m.getLong(a + LIST_COMMAND_POOL) == 0L) continue;
                    if (m.getLong(a + LIST_DEVICE) != device) return fail("list " + k + "/" + i + " is not on our device");
                    long semaphore = m.getLong(a + LIST_SEMAPHORE);
                    if (semaphore == 0L) {
                        making++;
                        continue;
                    }
                    long value = m.getLong(a + LIST_VALUE);
                    int s = k * LISTS + i;
                    if (semaphore == semaphores[s] && Long.compareUnsigned(value, values[s]) < 0) {
                        return fail("list " + k + "/" + i + " went back from " + values[s] + " to " + value);
                    }
                    if (semaphore == semaphores[s]) taken += value - values[s];
                    semaphores[s] = semaphore;
                    values[s] = value;
                    made[count++] = new CommandList(semaphore, value);
                }
                out[n++] = new Family(family, Arrays.copyOf(made, count), making);
            }
            return Arrays.copyOf(out, n);
        }

        /** Reports a counter that is neither a free nor a busy list's ({@link #busy}). */
        void implausibleCounter(CommandList l, long counter) {
            fail("a list's counter " + counter + " does not fit its value " + l.value());
        }

        private Family[] fail(String why) {
            failure = why;
            return null;
        }

        private static int indexOf(int[] sets, int family) {
            for (int k = 0; k < sets.length; k++) {
                if (sets[k] == family) return k;
            }
            return -1;
        }
    }
}
