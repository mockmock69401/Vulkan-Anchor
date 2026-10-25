package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review I1: copies in one commit that write the same bytes need a barrier between them (Vulkan does not order them). */
class OverlapTrackerTest {
    @Test
    void disjointWritesNeedNoBarrier() {
        OverlapTracker t = new OverlapTracker();
        assertFalse(t.needsBarrier(1, 0, 64));
        assertFalse(t.needsBarrier(1, 64, 64));
        assertFalse(t.needsBarrier(2, 0, 64));
    }

    @Test
    void anOverlappingWriteNeedsOneAndStartsAFreshScope() {
        OverlapTracker t = new OverlapTracker();
        assertFalse(t.needsBarrier(1, 0, 64));
        assertTrue(t.needsBarrier(1, 28, 4));
        assertFalse(t.needsBarrier(1, 0, 4));//the barrier ordered everything before it; only the 28..32 write is tracked now
        assertTrue(t.needsBarrier(1, 30, 8));
    }
}
