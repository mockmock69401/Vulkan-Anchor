package net.vulkanmod.visuals.vk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * GPU work recorded per frame. Work of a frame that was never submitted can be recorded again: it goes back to the
 * front of the queue in its original order, ahead of anything queued since.
 */
public final class FrameWork<T> {
    private record Entry<T>(T item, long serial) {}

    private final ArrayDeque<T> pending = new ArrayDeque<>();
    private final List<Entry<T>> inFlight = new ArrayList<>();

    public void queue(T item) {
        this.pending.addLast(item);
    }

    public boolean hasPending() {
        return !this.pending.isEmpty();
    }

    /** The pending work is recorded into frame serial; returns it in order. */
    public List<T> record(long serial) {
        List<T> items = new ArrayList<>(this.pending);
        for (T item : items) {
            this.inFlight.add(new Entry<>(item, serial));
        }
        this.pending.clear();
        return items;
    }

    public void tick(long completedSerial, LongPredicate isDropped, Consumer<T> done, Predicate<T> requeue) {
        List<T> again = new ArrayList<>();
        for (Iterator<Entry<T>> it = this.inFlight.iterator(); it.hasNext(); ) {
            Entry<T> e = it.next();
            if (isDropped.test(e.serial())) {
                it.remove();
                if (requeue.test(e.item())) again.add(e.item());
            } else if (e.serial() <= completedSerial) {
                it.remove();
                done.accept(e.item());
            }
        }
        for (int i = again.size() - 1; i >= 0; i--) {
            this.pending.addFirst(again.get(i));
        }
    }
}
