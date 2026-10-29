package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotLedgerTest {
    @Test void theRenderThreadWaitsForTheLastReservedRead() {
        SlotLedger l = new SlotLedger(2);
        l.reserveRead(0);
        l.reserveRead(0);
        long third = l.reserveRead(0);
        assertEquals(third, l.readValue(0));
        assertEquals(0, l.readValue(1), "a slot the pacer never read needs no wait (timeline value 0 is always reached)");
        assertEquals(third, l.lastValue());
    }

    @Test void valuesOnlyGrow() {
        SlotLedger l = new SlotLedger(2);
        long previous = 0;
        for (int i = 0; i < 10; i++) {
            long v = l.reserveRead(i % 3 == 0 ? 1 : 0);
            assertTrue(v > previous, "value " + v + " after " + previous);
            previous = v;
        }
        assertEquals(previous, l.lastValue());
    }

    /** 2026-10-30 (audit): the slot the render thread last asked to write; debug view 1 warps the previous frame only while it is the newest's. */
    @Test
    void theLastWrittenSlotIsTheRenderThreadsLatestAsk() {
        SlotLedger l = new SlotLedger(2);
        assertEquals(-1, l.lastWrittenSlot());
        l.readValue(0);
        assertEquals(0, l.lastWrittenSlot());
        l.reserveRead(1);
        assertEquals(0, l.lastWrittenSlot(), "a pacer read is no write");
        l.readValue(1);
        assertEquals(1, l.lastWrittenSlot());
    }
}
