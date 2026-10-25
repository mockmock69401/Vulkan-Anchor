package net.vulkanmod.visuals.dh;

import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.visuals.VisualsLog;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Measurements for the DH stage gates, off unless -Dvulkanmod.dh.stats=true: then a daemon thread logs one line a second
 * (live buffers and bytes, uploads in that second, staging, VMA heap use) and the terrain renderer counts how many
 * containers DH builds per section (a section rebuilt is a re-upload of the same area).
 */
final class DhStats {
    static final boolean ENABLED = Boolean.getBoolean("vulkanmod.dh.stats");
    private static final long PERIOD_MS = 1000;

    private static final Set<Object> SEEN = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Long, Integer> CONTAINERS_PER_SECTION = new HashMap<>();
    private static long containers, unkeyed;
    private static Thread thread;

    private DhStats() {
    }

    /** Render thread, opaque pass: a container seen for the first time. */
    static synchronized void container(Object c) {
        if (c == null || !SEEN.add(c)) return;
        containers++;
        Long key = DhInternals.sectionKey(c);
        if (key == null) {
            unkeyed++;
            return;
        }
        CONTAINERS_PER_SECTION.merge(key, 1, Integer::sum);
    }

    /** Containers built for a section that already had one, over all containers seen. */
    static synchronized String sections() {
        long distinct = CONTAINERS_PER_SECTION.size();
        long rebuilt = containers - unkeyed - distinct;
        return "containers " + containers + ", sections " + distinct + ", rebuilt " + rebuilt
                + (containers == 0 ? "" : String.format(" (%.1f%%)", 100.0 * rebuilt / containers));
    }

    static synchronized void start() {
        if (!ENABLED || thread != null) return;
        thread = new Thread(DhStats::run, "VulkanMod DH stats");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        long t0 = System.currentTimeMillis();
        long lastUploads = DhVertexBuffer.uploads(), lastBytes = DhVertexBuffer.uploadedBytes();
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            long uploads = DhVertexBuffer.uploads(), bytes = DhVertexBuffer.uploadedBytes();
            String heap;
            try {
                heap = MemoryManager.getInstance().getHeapStats();
            } catch (RuntimeException | LinkageError e) {
                heap = "heap n/a";
            }
            VisualsLog.info(String.format("DH stats t=%ds live %d buffers %d KiB | +%d uploads +%d KiB/s | total %d uploads %d MiB, reuploads %d, closed %d | %s | %s | %s",
                    (System.currentTimeMillis() - t0) / 1000, DhVertexBuffer.liveCount(), DhVertexBuffer.liveBytes() >> 10, uploads - lastUploads,
                    (bytes - lastBytes) >> 10, uploads, bytes >> 20, DhVertexBuffer.reuploads(), DhVertexBuffer.closedCount(),
                    DhStaging.INSTANCE.describe(), sections(), heap));
            lastUploads = uploads;
            lastBytes = bytes;
        }
    }
}
