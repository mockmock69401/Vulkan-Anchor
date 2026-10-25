package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PresentLogTest {
    private static void record(PresentLog log, long n) {
        log.record(n % 2, n, n + 1, n + 2, n + 3, n + 4, n + 5, n + 6);
    }

    @Test void drainReturnsRecordsInOrderAndOnlyOnce() {
        PresentLog log = new PresentLog();
        for (long n = 0; n < 5; n++) record(log, n);
        List<long[]> out = new ArrayList<>();
        log.drain(out::add);
        assertEquals(5, out.size());
        for (int n = 0; n < 5; n++) {
            assertArrayEquals(new long[]{n % 2, n, n + 1, n + 2, n + 3, n + 4, n + 5, n + 6}, out.get(n));
        }
        out.clear();
        log.drain(out::add);
        assertEquals(0, out.size(), "drained records are not returned again");
        record(log, 9);
        log.drain(out::add);
        assertEquals(1, out.size());
        assertEquals(9, out.get(0)[1]);
    }

    @Test void aFullRingDropsTheOldest() {
        PresentLog log = new PresentLog();
        for (long n = 0; n < PresentLog.CAPACITY + 1; n++) record(log, n);
        List<long[]> out = new ArrayList<>();
        log.drain(out::add);
        assertEquals(4096, PresentLog.CAPACITY);
        assertEquals(PresentLog.CAPACITY, out.size());
        assertEquals(1, out.get(0)[1], "the first record was overwritten");
        assertEquals(PresentLog.CAPACITY, out.get(out.size() - 1)[1]);
    }
}
