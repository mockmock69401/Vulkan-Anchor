package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Review focus 4: a resource replaced in frame N may still be read by frames before N; it is freed once N has completed. */
class DeferredFreeTest {
    @Test
    void freesOnlyAfterTheRetiringFrameCompleted() {
        DeferredFree deferred = new DeferredFree();
        List<String> freed = new ArrayList<>();
        deferred.retire(() -> freed.add("a"), 5);
        deferred.retire(() -> freed.add("b"), 7);
        deferred.tick(4);
        assertEquals(List.of(), freed);
        deferred.tick(5);
        assertEquals(List.of("a"), freed);
        deferred.tick(6);
        assertEquals(List.of("a"), freed);
        assertEquals(1, deferred.pendingCount());
        deferred.freeAll();
        assertEquals(List.of("a", "b"), freed);
        assertEquals(0, deferred.pendingCount());
    }
}
