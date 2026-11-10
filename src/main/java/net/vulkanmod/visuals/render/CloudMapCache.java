package net.vulkanmod.visuals.render;

import java.util.Arrays;

/**
 * 2026-11-10 (session 7): the last cloud noise map built (CloudMath.withNoise), by the mask, cloud size and count it was built for. The
 * renderer is rebuilt whenever the world's renderer is (allChanged: the render distance, graphics settings, F3+A), and each time built the
 * map again on the render thread (about a tenth of a second); for the same clouds it now takes this copy. Any thread (the size and count
 * rebuild runs off the render thread).
 */
final class CloudMapCache {
    private static byte[] mask, map;
    private static float size, count;
    /** Maps built (for the test). */
    static int builds;

    private CloudMapCache() {
    }

    /** CloudMath.withNoise(mask, size, count), from the cache when the last map was built for the same mask, size and count; a copy. */
    static byte[] withNoise(byte[] mask, float size, float count) {
        synchronized (CloudMapCache.class) {
            if (CloudMapCache.map != null && CloudMapCache.size == size && CloudMapCache.count == count && Arrays.equals(CloudMapCache.mask, mask)) {
                return CloudMapCache.map.clone();
            }
        }
        byte[] built = CloudMath.withNoise(mask, size, count);
        synchronized (CloudMapCache.class) {
            builds++;
            CloudMapCache.mask = mask.clone();
            CloudMapCache.map = built.clone();
            CloudMapCache.size = size;
            CloudMapCache.count = count;
        }
        return built;
    }

    /** Forgets the map (for the test). */
    static synchronized void clear() {
        mask = null;
        map = null;
        builds = 0;
    }
}
