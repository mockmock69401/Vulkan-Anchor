package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyLedgerTest {
    private static final java.util.function.LongPredicate NONE_DROPPED = s -> false;

    /** Review I2: a region reserved in an earlier frame must stay live until the frame that records its copy completes. */
    @Test
    void ringRegionsBelongToTheFrameThatRecordsTheCopy() {
        FrameRing ring = new FrameRing(256);
        CopyLedger<String> ledger = new CopyLedger<>(ring);
        long offset = ledger.reserve(64, 16);
        ledger.queue("a", offset);
        List<String> done = new ArrayList<>();
        ledger.tick(10, NONE_DROPPED, done::add, s -> false);
        assertEquals(1, ring.liveCount(), "not recorded yet, so not retired by older completed frames");
        assertEquals(List.of("a"), ledger.commit(12));
        ledger.tick(11, NONE_DROPPED, done::add, s -> false);
        assertEquals(List.of(), done);
        assertEquals(1, ring.liveCount());
        ledger.tick(12, NONE_DROPPED, done::add, s -> false);
        assertEquals(List.of("a"), done);
        assertEquals(0, ring.liveCount());
    }

    /** Review I1: copies recorded into a frame Reforged never submitted did not run. */
    @Test
    void droppedCopiesAreRequeuedWhenAsked() {
        FrameRing ring = new FrameRing(256);
        CopyLedger<String> ledger = new CopyLedger<>(ring);
        ledger.queue("up", ledger.reserve(32, 16));
        ledger.commit(5);
        List<String> done = new ArrayList<>();
        ledger.tick(6, s -> s == 5, done::add, s -> true);
        assertEquals(List.of(), done, "a dropped copy never completes");
        assertTrue(ledger.hasPending());
        assertEquals(1, ring.liveCount(), "requeued data keeps its staging region");
        assertEquals(List.of("up"), ledger.commit(7));
        ledger.tick(7, s -> s == 5, done::add, s -> true);
        assertEquals(List.of("up"), done);
    }

    @Test
    void droppedCopiesThatAreNotRequeuedAreReportedAndReleased() {
        FrameRing ring = new FrameRing(256);
        CopyLedger<String> ledger = new CopyLedger<>(ring);
        ledger.queue("down", ledger.reserve(32, 16));
        ledger.commit(5);
        List<String> reported = new ArrayList<>();
        ledger.tick(6, s -> s == 5, s -> { }, s -> reported.add(s) && false);
        assertEquals(List.of("down"), reported);
        assertEquals(false, ledger.hasPending());
        assertEquals(0, ring.liveCount());
    }
}
