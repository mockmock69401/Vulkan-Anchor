package net.vulkanmod.visuals.vk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * GPU resources replaced while frames may still use them (a resize): each is freed once the frame that replaced it has
 * completed, so no earlier frame can still reference it. A dropped frame is passed by a later completed one.
 */
public final class DeferredFree {
    private record Entry(Runnable free, long serial) {}

    private final List<Entry> pending = new ArrayList<>();

    public void retire(Runnable free, long serial) {
        this.pending.add(new Entry(free, serial));
    }

    /** Call when a frame starts recording (VkFrames listener). */
    public void tick(long completedSerial) {
        for (Iterator<Entry> it = this.pending.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.serial() <= completedSerial) {
                it.remove();
                e.free().run();
            }
        }
    }

    /** Only once no submitted frame can still use anything (after the device is idle). */
    public void freeAll() {
        List<Entry> all = new ArrayList<>(this.pending);
        this.pending.clear();
        for (Entry e : all) e.free().run();
    }

    public int pendingCount() {
        return this.pending.size();
    }
}
