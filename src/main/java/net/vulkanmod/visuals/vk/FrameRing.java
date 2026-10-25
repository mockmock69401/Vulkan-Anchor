package net.vulkanmod.visuals.vk;

import java.util.ArrayDeque;

/**
 * Ring allocator for per-frame staging memory. Allocations are released by frame serial once the GPU has finished
 * that frame; a request that would overlap a live allocation fails (-1) instead of overwriting it.
 */
public final class FrameRing {
    /** Serial for a region whose frame is not known yet; it is never retired until stamped. */
    public static final long PENDING = Long.MAX_VALUE;

    private final long capacity;
    private final ArrayDeque<long[]> live = new ArrayDeque<>(); // {start, end, serial}, in allocation order
    private long head;

    public FrameRing(long capacity) {
        this.capacity = capacity;
    }

    public long allocate(long size, long alignment, long serial) {
        if (size <= 0 || size > this.capacity) {
            return -1;
        }
        long start = (this.head + alignment - 1) / alignment * alignment;
        if (start + size > this.capacity) {
            start = 0;
        }
        long end = start + size;
        for (long[] a : this.live) {
            if (start < a[1] && a[0] < end) {
                return -1;
            }
        }
        this.live.addLast(new long[]{start, end, serial});
        this.head = end;
        return start;
    }

    /** Re-assigns the live region starting at start to the frame that will use it (or back to PENDING). */
    public void stamp(long start, long serial) {
        for (long[] a : this.live) {
            if (a[0] == start) {
                a[2] = serial;
                return;
            }
        }
        throw new IllegalArgumentException("no live region at " + start);
    }

    public void retire(long completedSerial) {
        while (!this.live.isEmpty() && this.live.peekFirst()[2] <= completedSerial) {
            this.live.removeFirst();
        }
    }

    public int liveCount() {
        return this.live.size();
    }
}
