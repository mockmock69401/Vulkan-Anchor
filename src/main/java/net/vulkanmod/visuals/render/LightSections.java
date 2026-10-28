package net.vulkanmod.visuals.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * P7l design 3.1: which sections the light volume still needs, nearest the camera first. held mirrors the ready table: the
 * key of the section each slot was last given (0 = none).
 */
public final class LightSections {
    public interface Loaded {
        boolean test(int sx, int sz);
    }

    private LightVolumeMath.Layout layout;
    private int[] held = new int[0];
    private final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
    private int cameraSx, cameraSy, cameraSz, readyCount;
    private boolean centred;

    public void reset(LightVolumeMath.Layout layout) {
        this.layout = layout;
        this.held = new int[layout.slots()];
        this.dirty.clear();
        this.readyCount = 0;
        this.centred = false;
    }

    /** A section's light changed (ClientChunkCache.onLightUpdate): upload it again while it is in range. */
    public void changed(int sx, int sy, int sz) {
        if (this.layout == null || !this.centred) return;
        if (!this.layout.inRange(sx, sy, sz, this.cameraSx, this.cameraSz)) {
            this.unready(sx, sy, sz);
            return;
        }
        this.dirty.add(pack(sx, sy, sz));
    }

    /** A change that is ignored or dropped must not leave its section ready: the slot may still hold it, stale, when it is back in range. */
    private void unready(int sx, int sy, int sz) {
        if (sy < this.layout.minSectionY() || sy >= this.layout.minSectionY() + this.layout.slotsY()) return;
        int slot = this.layout.slot(sx, sy, sz);
        if (this.held[slot] != this.layout.key(sx, sy, sz)) return;
        this.held[slot] = 0;
        this.readyCount--;
    }

    /** The camera's section. A new one, or rescan, queues each in-range section its slot does not hold whose chunk is loaded. */
    public void centre(int sx, int sy, int sz, boolean rescan, Loaded loaded) {
        boolean moved = !this.centred || sx != this.cameraSx || sz != this.cameraSz;
        this.cameraSx = sx;
        this.cameraSy = sy;
        this.cameraSz = sz;
        this.centred = true;
        if (!moved && !rescan) return;
        for (int slot = 0; slot < this.held.length; slot++) {
            int[] s = this.layout.sectionAt(slot, sx, sz);
            if (this.held[slot] != this.layout.key(s[0], s[1], s[2]) && loaded.test(s[0], s[2])) this.dirty.add(pack(s[0], s[1], s[2]));
        }
    }

    /**
     * Up to budget queued sections, nearest first (ties in queue order), taken off the queue; those that left the range are dropped.
     * 2026-10-28 (NightfallCraft, a taller world, so tens of thousands of queued sections while new chunks come in): one pass keeps
     * the budget nearest in a bounded max-heap instead of sorting the whole queue each frame, which took a fifth of the render thread.
     */
    public List<int[]> next(int budget) {
        int cap = Math.max(0, Math.min(budget, this.dirty.size()));
        long[] keys = new long[cap], order = new long[cap];//order: distance in the high bits, queue position in the low 24
        int size = 0, position = 0;
        for (Iterator<Long> it = this.dirty.iterator(); it.hasNext(); position++) {
            long key = it.next();
            int sx = (int) (key >> 42), sy = (int) (key << 44 >> 44), sz = (int) (key << 22 >> 42);
            if (!this.layout.inRange(sx, sy, sz, this.cameraSx, this.cameraSz)) {
                it.remove();
                this.unready(sx, sy, sz);
                continue;
            }
            if (cap == 0) continue;
            long rank = this.distance(sx, sy, sz) << 24 | (position & 0xFFFFFF);
            if (size < cap) {
                keys[size] = key;
                order[size] = rank;
                siftUp(keys, order, size++);
            } else if (rank < order[0]) {
                keys[0] = key;
                order[0] = rank;
                siftDown(keys, order, size);
            }
        }
        Integer[] picked = new Integer[size];
        for (int i = 0; i < size; i++) picked[i] = i;
        Arrays.sort(picked, Comparator.comparingLong(i -> order[i]));
        List<int[]> out = new ArrayList<>(size);
        for (int i : picked) {
            long key = keys[i];
            this.dirty.remove(key);
            out.add(unpack(key));
        }
        return out;
    }

    /** Max-heap on order (the farthest kept section at the root). */
    private static void siftUp(long[] keys, long[] order, int i) {
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (order[parent] >= order[i]) return;
            swap(keys, order, parent, i);
            i = parent;
        }
    }

    private static void siftDown(long[] keys, long[] order, int size) {
        int i = 0;
        while (true) {
            int l = 2 * i + 1, r = l + 1, largest = i;
            if (l < size && order[l] > order[largest]) largest = l;
            if (r < size && order[r] > order[largest]) largest = r;
            if (largest == i) return;
            swap(keys, order, largest, i);
            i = largest;
        }
    }

    private static void swap(long[] keys, long[] order, int a, int b) {
        long k = keys[a], o = order[a];
        keys[a] = keys[b];
        order[a] = order[b];
        keys[b] = k;
        order[b] = o;
    }

    public void uploaded(int sx, int sy, int sz) {
        int slot = this.layout.slot(sx, sy, sz);
        if (this.held[slot] == 0) this.readyCount++;
        this.held[slot] = this.layout.key(sx, sy, sz);
    }

    /** Light changes were lost (LightUpdates overflow): every slot is uploaded again by the next rescan. */
    public void forgetAll() {
        Arrays.fill(this.held, 0);
        this.readyCount = 0;
    }

    public int ready() {
        return this.readyCount;
    }

    public int queued() {
        return this.dirty.size();
    }

    public int slots() {
        return this.held.length;
    }

    private long distance(int sx, int sy, int sz) {
        long dx = sx - this.cameraSx, dy = sy - this.cameraSy, dz = sz - this.cameraSz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** x and z in 22 bits, y in 20 (as SectionPos.asLong). */
    public static long pack(int sx, int sy, int sz) {
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sz & 0x3FFFFF) << 20 | (sy & 0xFFFFFL);
    }

    public static int[] unpack(long p) {
        return new int[]{(int) (p >> 42), (int) (p << 44 >> 44), (int) (p << 22 >> 42)};
    }
}
