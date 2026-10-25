package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** P7l design 3.1: the ring of section slots around the camera. */
class LightVolumeMathTest {
    private static final LightVolumeMath.Layout L = LightVolumeMath.layout(12, -4, 24);

    @Test
    void twelveChunksMakeA400Block61MiBRing() {
        assertEquals(new LightVolumeMath.Layout(25, 24, 25, -4), L);
        assertEquals(15000, L.slots());
        assertEquals(15000L * 4096, L.bytes());
        assertEquals(12, L.half());
        assertEquals(new LightVolumeMath.Layout(5, 128, 5, 0), LightVolumeMath.layout(1, 0, 300));
    }

    /** Final review: the data buffer is bound whole as a storage buffer, so it must fit maxStorageBufferRange (128 MiB is the spec minimum). */
    @Test
    void theDataBufferMustFitTheStorageBufferRange() {
        long minimum = 128L << 20;
        assertTrue(LightVolumeMath.fitsStorageRange(L, minimum), "render distance 12 is 61 MiB");
        assertTrue(LightVolumeMath.fitsStorageRange(LightVolumeMath.layout(16, -4, 24), minimum), "render distance 16 is 102 MiB");
        LightVolumeMath.Layout far = LightVolumeMath.layout(32, -4, 24);
        assertTrue(far.bytes() > minimum, "render distance 32 is about 415 MiB");
        assertFalse(LightVolumeMath.fitsStorageRange(far, minimum));
        assertTrue(LightVolumeMath.fitsStorageRange(far, far.bytes()), "exactly the limit fits");
        assertFalse(LightVolumeMath.fitsStorageRange(far, far.bytes() - 1));
    }

    /** Review focus 1: every slot holds exactly one in-range section, and sectionAt finds it. */
    @Test
    void aSlotHoldsOneSectionAroundTheCamera() {
        int cx = 37, cz = -91;
        Set<Integer> slots = new HashSet<>();
        for (int x = cx - 12; x <= cx + 12; x++) for (int z = cz - 12; z <= cz + 12; z++) for (int y = -4; y < 20; y++) {
            assertTrue(L.inRange(x, y, z, cx, cz));
            int slot = L.slot(x, y, z);
            assertTrue(slots.add(slot), "slot " + slot + " twice");
            assertArrayEquals(new int[]{x, y, z}, L.sectionAt(slot, cx, cz));
        }
        assertEquals(L.slots(), slots.size());
        assertFalse(L.inRange(cx + 13, 0, cz, cx, cz));
        assertFalse(L.inRange(cx, 20, cz, cx, cz));
        assertFalse(L.inRange(cx, -5, cz, cx, cz));
    }

    /** Review focus 2. */
    @Test
    void negativeCoordinatesWrapIntoTheRing() {
        assertEquals(L.slot(-1, -4, -1), L.slot(24, -4, 24));
        assertEquals(24 + 25 * 24, L.slot(-1, -4, -1));
        int a = L.key(-1, -4, -1), b = L.key(24, -4, 24), c = L.key(-1, 19, -1);
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a < 0, "bit 31 marks a held slot");
        assertEquals(0xFFF | 0xFFF << 12 | 1 << 31, a);
    }
}
