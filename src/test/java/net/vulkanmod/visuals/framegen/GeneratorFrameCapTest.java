package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-02: half the refresh rate caps the frame limit while a generator runs; a lower limit stays. */
class GeneratorFrameCapTest {
    @Test void unlimitedIsCappedAtHalfTheRefresh() {
        assertEquals(120, GeneratorFrameCap.cap(260, 240));
        assertEquals(72, GeneratorFrameCap.cap(260, 144));
        assertEquals(30, GeneratorFrameCap.cap(260, 60));
    }

    @Test void oddRefreshRoundsDown() {
        assertEquals(82, GeneratorFrameCap.cap(260, 165));
        assertEquals(37, GeneratorFrameCap.cap(260, 75));
    }

    @Test void lowerLimitStays() {
        assertEquals(60, GeneratorFrameCap.cap(60, 240));
        assertEquals(10, GeneratorFrameCap.cap(10, 144));
        assertEquals(120, GeneratorFrameCap.cap(120, 240));
    }

    @Test void unknownRefreshLeavesTheLimit() {
        assertEquals(260, GeneratorFrameCap.cap(260, 0));
        assertEquals(90, GeneratorFrameCap.cap(90, -1));
    }

    @Test void neverBelowOne() {
        assertEquals(1, GeneratorFrameCap.cap(260, 1));
    }
}
