package net.vulkanmod.visuals.vk;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reforged recreates its main render passes when the render-scaled framebuffer is swapped; destroying the pipeline
 * built for the old pass right away could free it while in-flight frames still use it. Keep one per render pass.
 */
class PerRenderPassCacheTest {
    @Test
    void buildsOncePerRenderPassAndKeepsEarlierOnes() {
        List<Long> built = new ArrayList<>();
        PerRenderPassCache cache = new PerRenderPassCache(pass -> {
            built.add(pass);
            return pass * 10;
        });
        assertEquals(10L, cache.get(1L));
        assertEquals(10L, cache.get(1L));
        assertEquals(20L, cache.get(2L));
        assertEquals(10L, cache.get(1L), "switching back reuses the first pipeline, it was not destroyed");
        assertEquals(List.of(1L, 2L), built);
    }

    /** Review focus 1: Reforged replaces its main render pass on every resize; compatible passes share one pipeline. */
    @Test
    void compatibleRenderPassesShareOnePipeline() {
        List<Long> built = new ArrayList<>();
        PerRenderPassCache cache = new PerRenderPassCache(pass -> {
            built.add(pass);
            return pass * 10;
        });
        long rgbaD32 = PerRenderPassCache.compatibilityKey(37, 126);
        assertEquals(1000L, cache.get(rgbaD32, 100L));
        assertEquals(1000L, cache.get(rgbaD32, 200L), "a recreated pass with the same formats reuses the pipeline");
        assertEquals(3000L, cache.get(PerRenderPassCache.compatibilityKey(44, 126), 300L));
        assertEquals(List.of(100L, 300L), built);
    }

    @Test
    void keysTellColourAndDepthFormatsApart() {
        assertNotEquals(PerRenderPassCache.compatibilityKey(37, 126), PerRenderPassCache.compatibilityKey(126, 37));
        assertNotEquals(PerRenderPassCache.compatibilityKey(37, 126), PerRenderPassCache.compatibilityKey(37, 129));
    }
}
