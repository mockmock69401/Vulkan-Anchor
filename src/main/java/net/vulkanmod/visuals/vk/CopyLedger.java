package net.vulkanmod.visuals.vk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * Bookkeeping for the staging streams: a ring region belongs to the frame that records its copy (not the frame it was
 * reserved in), and a copy recorded into a frame that was never submitted is handed back as dropped.
 */
public final class CopyLedger<T> {
    private record Entry<T>(T item, long ringOffset, long serial) {}

    private final FrameRing ring;
    private final List<Entry<T>> pending = new ArrayList<>();
    private final List<Entry<T>> inFlight = new ArrayList<>();

    public CopyLedger(FrameRing ring) {
        this.ring = ring;
    }

    /** Ring space for a copy recorded later; -1 if the ring is full. */
    public long reserve(long size, long alignment) {
        return this.ring.allocate(size, alignment, FrameRing.PENDING);
    }

    public void queue(T item, long ringOffset) {
        this.pending.add(new Entry<>(item, ringOffset, FrameRing.PENDING));
    }

    public boolean hasPending() {
        return !this.pending.isEmpty();
    }

    /** The pending copies are recorded into frame serial; returns them in queue order. */
    public List<T> commit(long serial) {
        List<T> items = new ArrayList<>(this.pending.size());
        for (Entry<T> e : this.pending) {
            this.ring.stamp(e.ringOffset(), serial);
            this.inFlight.add(new Entry<>(e.item(), e.ringOffset(), serial));
            items.add(e.item());
        }
        this.pending.clear();
        return items;
    }

    /**
     * Hands finished copies to done, and copies of dropped frames to onDropped, which returns true to record them again
     * (keeping their staging region); then releases ring space of completed frames. 2026-10-30 (audit): copies recorded again go
     * ahead of the pending ones, in their own order (as FrameWork.tick does): a copy queued after the dropped frame's commit is
     * newer and lands last when both write the same bytes.
     */
    public void tick(long completedSerial, LongPredicate isDropped, Consumer<T> done, Predicate<T> onDropped) {
        List<Entry<T>> again = new ArrayList<>();
        for (Iterator<Entry<T>> it = this.inFlight.iterator(); it.hasNext(); ) {
            Entry<T> e = it.next();
            if (isDropped.test(e.serial())) {
                it.remove();
                if (onDropped.test(e.item())) {
                    this.ring.stamp(e.ringOffset(), FrameRing.PENDING);
                    again.add(new Entry<>(e.item(), e.ringOffset(), FrameRing.PENDING));
                }
            } else if (e.serial() <= completedSerial) {
                it.remove();
                done.accept(e.item());
            }
        }
        this.pending.addAll(0, again);
        this.ring.retire(completedSerial);
    }
}
