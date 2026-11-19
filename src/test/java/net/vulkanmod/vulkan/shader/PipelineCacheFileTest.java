package net.vulkanmod.vulkan.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-19: the pipeline cache is written once new pipelines have settled, not only when the game closes normally. */
class PipelineCacheFileTest {
    private static final long S = PipelineCacheFile.SETTLE_NANOS;

    @Test
    void writesOnceCreationHasSettled() {
        assertTrue(PipelineCacheFile.due(3, 100 + S, 100, false));
    }

    @Test
    void waitsWhilePipelinesAreStillBeingBuilt() {
        assertFalse(PipelineCacheFile.due(3, 100 + S - 1, 100, false));
    }

    @Test
    void nothingNewNothingWritten() {
        assertFalse(PipelineCacheFile.due(0, 100 + 10 * S, 100, false));
    }

    @Test
    void oneWriteAtATime() {
        assertFalse(PipelineCacheFile.due(3, 100 + 10 * S, 100, true));
    }
}
