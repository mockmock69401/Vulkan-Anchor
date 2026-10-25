package net.vulkanmod.visuals.vk;

import java.util.ArrayList;
import java.util.List;

/**
 * Transfer writes recorded back to back are unordered in Vulkan. This tracks the byte ranges written since the last
 * barrier and says when a new write overlaps one of them, so the caller records a TRANSFER→TRANSFER barrier first.
 */
public final class OverlapTracker {
    private record Range(long buffer, long start, long end) {}

    private final List<Range> written = new ArrayList<>();

    /** True when this write must be ordered after earlier ones; the tracked scope then restarts at this write. */
    public boolean needsBarrier(long buffer, long offset, long size) {
        long end = offset + size;
        boolean overlaps = false;
        for (Range r : this.written) {
            if (r.buffer() == buffer && offset < r.end() && r.start() < end) {
                overlaps = true;
                break;
            }
        }
        if (overlaps) this.written.clear();
        this.written.add(new Range(buffer, offset, end));
        return overlaps;
    }
}
