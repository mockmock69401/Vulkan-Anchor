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

    /** Up to budget queued sections, nearest first, taken off the queue; those that left the range are dropped. */
    public List<int[]> next(int budget) {
        List<int[]> in = new ArrayList<>();
        for (Iterator<Long> it = this.dirty.iterator(); it.hasNext(); ) {
            int[] s = unpack(it.next());
            if (this.layout.inRange(s[0], s[1], s[2], this.cameraSx, this.cameraSz)) in.add(s);
            else {
                it.remove();
                this.unready(s[0], s[1], s[2]);
            }
        }
        in.sort(Comparator.comparingLong(this::distance));
        List<int[]> out = new ArrayList<>(in.subList(0, Math.min(budget, in.size())));
        for (int[] s : out) this.dirty.remove(pack(s[0], s[1], s[2]));
        return out;
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

    private long distance(int[] s) {
        long dx = s[0] - this.cameraSx, dy = s[1] - this.cameraSy, dz = s[2] - this.cameraSz;
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
