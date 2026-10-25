package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameRingTest {
    @Test
    void allocatesSequentiallyWithAlignment() {
        FrameRing ring = new FrameRing(1024);
        assertEquals(0, ring.allocate(10, 16, 1));
        assertEquals(16, ring.allocate(10, 16, 1));
        assertEquals(28, ring.allocate(100, 4, 1), "26 rounded up to 4-byte alignment");
    }

    @Test
    void fullUntilOlderFramesRetire() {
        FrameRing ring = new FrameRing(256);
        assertEquals(0, ring.allocate(200, 16, 1));
        assertEquals(-1, ring.allocate(100, 16, 2), "frame 1 still in flight");
        ring.retire(1);
        assertEquals(0, ring.allocate(100, 16, 2), "wraps to the start once frame 1 completed");
    }

    @Test
    void wrapsWithoutOverlappingLiveAllocations() {
        FrameRing ring = new FrameRing(256);
        assertEquals(0, ring.allocate(100, 16, 1));
        assertEquals(112, ring.allocate(100, 16, 2));
        ring.retire(1);
        assertEquals(0, ring.allocate(100, 16, 3), "does not fit at 212, wraps; frame 2 at 112..212 untouched");
        assertEquals(-1, ring.allocate(50, 16, 3), "would overlap frame 2");
        assertEquals(2, ring.liveCount());
    }

    @Test
    void oversizedRequestIsRejected() {
        assertEquals(-1, new FrameRing(64).allocate(65, 4, 1));
    }

    @Test
    void pendingRegionsSurviveRetireUntilStamped() {
        FrameRing ring = new FrameRing(256);
        long offset = ring.allocate(100, 16, FrameRing.PENDING);
        ring.retire(1000);
        assertEquals(1, ring.liveCount());
        ring.stamp(offset, 5);
        ring.retire(4);
        assertEquals(1, ring.liveCount());
        ring.retire(5);
        assertEquals(0, ring.liveCount());
    }
}
