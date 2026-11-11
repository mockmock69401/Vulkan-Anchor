package net.vulkanmod.visuals.vk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * An image's layout as the GPU will see it: each recorded transition is kept with its frame until that frame
 * completes, and undone when the frame turns out to be dropped (never submitted).
 */
public final class LayoutTracker {
    private record Transition(long serial, int before) {}

    private final List<Transition> pending = new ArrayList<>();
    private int current;

    public LayoutTracker(int initial) {
        this.current = initial;
    }

    public int current() {
        return this.current;
    }

    public void recorded(long serial, int newLayout) {
        this.pending.add(new Transition(serial, this.current));
        this.current = newLayout;
    }

    public void tick(long completedSerial, LongPredicate isDropped) {
        //2026-11-12 (session 14): every image ticks every frame; nothing pending (most of them) allocates nothing, and the completed
        // transitions go without removeIf's capturing lambda
        if (this.pending.isEmpty()) return;
        for (int i = 0; i < this.pending.size(); i++) {
            if (isDropped.test(this.pending.get(i).serial())) {
                this.current = this.pending.get(i).before();
                this.pending.subList(i, this.pending.size()).clear();
                break;
            }
        }
        int kept = 0;
        for (int i = 0; i < this.pending.size(); i++) {
            Transition t = this.pending.get(i);
            if (t.serial() > completedSerial) this.pending.set(kept++, t);
        }
        this.pending.subList(kept, this.pending.size()).clear();
    }
}
