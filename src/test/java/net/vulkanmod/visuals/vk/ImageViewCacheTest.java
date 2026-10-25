package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Review focus 4: Reforged replaces its main depth image object on resize; the visuals' depth-only view of the old one may still be in use. */
class ImageViewCacheTest {
    @Test
    void keepsOneViewPerImageAndRetiresReplacedOnes() {
        List<Long> destroyed = new ArrayList<>();
        long[] next = {100};
        ImageViewCache cache = new ImageViewCache(image -> next[0]++, destroyed::add);
        DeferredFree deferred = new DeferredFree();
        Object first = new Object();
        Object second = new Object();
        assertEquals(100, cache.get(first, deferred, 1));
        assertEquals(100, cache.get(first, deferred, 2));
        assertEquals(101, cache.get(second, deferred, 3));
        assertEquals(List.of(), destroyed, "frames 1-2 may still sample the first view");
        deferred.tick(3);
        assertEquals(List.of(100L), destroyed);
        cache.free();
        assertEquals(List.of(100L, 101L), destroyed);
    }
}
