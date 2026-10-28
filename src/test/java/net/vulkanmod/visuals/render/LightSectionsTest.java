package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P7l design 3.1: the sections the volume still needs, nearest first. */
class LightSectionsTest {
    private static final LightVolumeMath.Layout L = LightVolumeMath.layout(2, 0, 4);//5 x 4 x 5 = 100 slots

    private static LightSections filled(int cx, int cy, int cz) {
        LightSections s = new LightSections();
        s.reset(L);
        s.centre(cx, cy, cz, false, (x, z) -> true);
        for (int[] p : s.next(1000)) s.uploaded(p[0], p[1], p[2]);
        return s;
    }

    @Test
    void packRoundTripsNegativeSections() {
        for (int[] p : new int[][]{{0, 0, 0}, {-1, -4, -1}, {1874999, 19, -1874999}, {-37, 300, 12}}) {
            assertArrayEquals(p, LightSections.unpack(LightSections.pack(p[0], p[1], p[2])));
        }
    }

    @Test
    void theFirstCentreQueuesEveryLoadedSectionNearestFirst() {
        LightSections s = new LightSections();
        s.reset(L);
        s.centre(10, 1, -3, false, (x, z) -> x != 12);
        assertEquals(100 - 20, s.queued());
        List<int[]> first = s.next(3);
        assertArrayEquals(new int[]{10, 1, -3}, first.get(0));
        assertEquals(3, first.size());
        assertEquals(77, s.queued());
    }

    /**
     * 2026-10-28 (NightfallCraft): next picks the budget nearest without sorting the whole queue. Each call takes exactly the
     * sections a full stable sort by distance would (ties in queue order), nearest first, until the queue is empty.
     */
    @Test
    void eachCallTakesTheNearestOfTheQueueInDistanceThenQueueOrder() {
        LightVolumeMath.Layout big = LightVolumeMath.layout(8, 0, 24);//17 x 24 x 17 slots
        LightSections s = new LightSections();
        s.reset(big);
        s.centre(3, 7, -2, false, (x, z) -> true);
        java.util.List<int[]> expected = new java.util.ArrayList<>();
        for (int slot = 0; slot < big.slots(); slot++) {
            int[] p = big.sectionAt(slot, 3, -2);
            if (big.inRange(p[0], p[1], p[2], 3, -2)) expected.add(p);//out-of-range slots are dropped, not taken
        }
        //the queue order is the centre scan's slot order; a stable sort by distance keeps it for ties
        expected.sort(java.util.Comparator.comparingLong(p -> {
            long dx = p[0] - 3, dy = p[1] - 7, dz = p[2] - (-2);
            return dx * dx + dy * dy + dz * dz;
        }));
        int taken = 0;
        for (int budget : new int[]{64, 1, 300, 64, 1000, 10000}) {
            List<int[]> got = s.next(budget);
            assertEquals(Math.min(budget, expected.size() - taken), got.size());
            for (int[] p : got) assertArrayEquals(expected.get(taken++), p);
        }
        assertEquals(expected.size(), taken);
        assertEquals(0, s.queued());
    }

    /** Review focus 1: moving one section along x re-queues only the new column of slots. */
    @Test
    void movingQueuesTheSectionsThatEnteredOnly() {
        LightSections s = filled(0, 0, 0);
        assertEquals(100, s.ready());
        assertEquals(0, s.queued());
        s.centre(1, 0, 0, false, (x, z) -> true);
        assertEquals(20, s.queued());
        for (int[] p : s.next(100)) assertEquals(3, p[0]);
        s.centre(1, 0, 0, false, (x, z) -> true);
        assertEquals(0, s.queued());
    }

    @Test
    void aLightChangeInRangeIsQueuedOnceAndOutOfRangeIgnored() {
        LightSections s = filled(0, 0, 0);
        s.changed(1, 2, -1);
        s.changed(1, 2, -1);
        s.changed(9, 0, 0);
        s.changed(0, 9, 0);
        assertEquals(1, s.queued());
        assertArrayEquals(new int[]{1, 2, -1}, s.next(10).get(0));
    }

    @Test
    void queuedSectionsThatLeftTheRangeAreDropped() {
        LightSections s = filled(0, 0, 0);
        s.changed(-2, 0, 0);
        s.centre(1, 0, 0, false, (x, z) -> true);
        for (int[] p : s.next(1000)) assertNotEquals(-2, p[0]);
        assertEquals(0, s.queued());
    }

    /** A change dropped as out of range must not leave the slot ready: the section comes back stale otherwise. */
    @Test
    void aDroppedChangeIsQueuedAgainWhenTheSectionIsBackInRange() {
        LightSections s = filled(0, 0, 0);
        s.changed(-2, 0, 0);
        s.centre(1, 0, 0, false, (x, z) -> x != 3);//the column that replaces -2 is not loaded yet
        assertTrue(s.next(1000).stream().noneMatch(p -> p[0] == -2));//the change to -2 is dropped here
        assertEquals(99, s.ready());
        s.centre(0, 0, 0, false, (x, z) -> x != 3);
        assertTrue(s.next(1000).stream().anyMatch(p -> p[0] == -2 && p[1] == 0 && p[2] == 0));
    }

    @Test
    void aChangeJustOutOfRangeOnAHeldSectionIsQueuedAgainWhenItIsBackInRange() {
        LightSections s = filled(0, 0, 0);
        s.centre(1, 0, 0, false, (x, z) -> x != 3);//slot of -2 still holds -2
        s.changed(-2, 0, 0);
        assertEquals(99, s.ready());
        s.centre(0, 0, 0, false, (x, z) -> true);
        assertTrue(s.next(1000).stream().anyMatch(p -> p[0] == -2 && p[1] == 0 && p[2] == 0));
    }

    @Test
    void forgetAllQueuesEverythingOnTheNextRescan() {
        LightSections s = filled(0, 0, 0);
        s.forgetAll();
        assertEquals(0, s.ready());
        s.centre(0, 0, 0, true, (x, z) -> true);
        assertEquals(100, s.queued());
    }
}
