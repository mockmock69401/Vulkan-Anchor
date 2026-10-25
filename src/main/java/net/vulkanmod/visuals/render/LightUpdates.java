package net.vulkanmod.visuals.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P7l design 3.1, 3.7: light changes from ClientChunkCache.onLightUpdate (ClientChunkCacheLightMixin), drained by the render thread.
 * 2026-10-15: with Reforged's meshes (holdForMeshes) a change is held until that section's new mesh is uploaded
 * (the TaskDispatcher source hook), so the volume and vanilla's colour show the same light; the volume running a frame ahead made the
 * held light's gain light an already lit colour (a flash when a placed torch went out while holding one).
 */
public final class LightUpdates {
    /** Past this many pending changes they are dropped and every slot is uploaded again. */
    public static final int CAPACITY = 1 << 16;
    /** 2026-10-15: a held change whose section was not rebuilt (out of view) is taken after this long. */
    public static final long HOLD_NANOS = 250_000_000L;
    private static final ConcurrentLinkedQueue<long[]> QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<long[]> UPLOADS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final AtomicBoolean OVERFLOWED = new AtomicBoolean();
    /** Render thread: held sections and when their light changed (the earliest change). */
    private static final Map<Long, Long> HELD = new HashMap<>();
    private static volatile boolean collecting, holdForMeshes, anyHeld;

    private LightUpdates() {
    }

    /** On while a light volume exists; off clears what is pending. */
    public static void collect(boolean on) {
        collecting = on;
        if (!on) clear();
    }

    /** 2026-10-15: on when Reforged builds the meshes (its uploads are reported); off takes every change at once. */
    public static void holdForMeshes(boolean on) {
        holdForMeshes = on;
    }

    public static void changed(int sx, int sy, int sz) {
        changed(sx, sy, sz, System.nanoTime());
    }

    static void changed(int sx, int sy, int sz, long nanos) {
        if (!collecting) return;
        if (SIZE.get() >= CAPACITY) {
            OVERFLOWED.set(true);
            return;
        }
        QUEUE.add(new long[]{LightSections.pack(sx, sy, sz), nanos});
        SIZE.incrementAndGet();
    }

    /** 2026-10-15: Reforged uploaded a section's new mesh (a full update). */
    public static void meshUploaded(int sx, int sy, int sz) {
        meshUploaded(sx, sy, sz, System.nanoTime());
    }

    static void meshUploaded(int sx, int sy, int sz, long nanos) {
        if (!collecting || !holdForMeshes || !anyHeld) return;
        UPLOADS.add(new long[]{LightSections.pack(sx, sy, sz), nanos});
    }

    public static void drainInto(LightSections sections) {
        drainInto(sections, System.nanoTime());
    }

    static void drainInto(LightSections sections, long now) {
        if (OVERFLOWED.getAndSet(false)) {
            sections.forgetAll();
            HELD.clear();
        }
        long[] e;
        while ((e = QUEUE.poll()) != null) {
            SIZE.decrementAndGet();
            if (holdForMeshes) {
                HELD.putIfAbsent(e[0], e[1]);
            } else {
                give(sections, e[0]);
            }
        }
        anyHeld = !HELD.isEmpty();
        //A mesh uploaded after the change shows its light; one from before still shows the old light
        while ((e = UPLOADS.poll()) != null) {
            Long changedAt = HELD.get(e[0]);
            if (changedAt != null && e[1] >= changedAt) {
                HELD.remove(e[0]);
                give(sections, e[0]);
            }
        }
        for (Iterator<Map.Entry<Long, Long>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> h = it.next();
            if (now - h.getValue() > HOLD_NANOS) {
                it.remove();
                give(sections, h.getKey());
            }
        }
        anyHeld = !HELD.isEmpty();
    }

    private static void give(LightSections sections, long packed) {
        int[] s = LightSections.unpack(packed);
        sections.changed(s[0], s[1], s[2]);
    }

    public static void clear() {
        QUEUE.clear();
        UPLOADS.clear();
        SIZE.set(0);
        OVERFLOWED.set(false);
        HELD.clear();
        anyHeld = false;
    }

    public static int pending() {
        return SIZE.get();
    }

    /** Changes waiting for their section's mesh. */
    static int held() {
        return HELD.size();
    }
}
