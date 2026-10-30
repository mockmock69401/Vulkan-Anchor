package net.vulkanmod.render.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-31: the time from a block the player changed (a door) to the upload of a mesh built after it. */
class PlayerChangeLatencyTest {
    @Test
    void theFirstChangeStartsTheClock() {
        assertEquals(100, PlayerChangeLatency.dirtied(0, 100));
        assertEquals(100, PlayerChangeLatency.dirtied(100, 250), "a second change before the mesh keeps the first time");
    }

    @Test
    void onlyAMeshBuiltAfterTheChangeStopsIt() {
        PlayerChangeLatency.reset();
        assertEquals(1_000_000, PlayerChangeLatency.uploaded(1_000_000, 999_999, 5_000_000), "built before the change: still waiting");
        assertEquals(0, PlayerChangeLatency.stats()[0]);
        assertEquals(0, PlayerChangeLatency.uploaded(1_000_000, 1_000_000, 5_000_000));
        assertArrayEquals(new double[]{1, 4, 4}, PlayerChangeLatency.stats(), 1e-9);
        assertEquals(0, PlayerChangeLatency.uploaded(0, 2_000_000, 9_000_000), "no change pending: nothing recorded");
        assertEquals(1, PlayerChangeLatency.stats()[0]);
    }

    @Test
    void statsAverageAndKeepTheMax() {
        PlayerChangeLatency.reset();
        PlayerChangeLatency.uploaded(0 + 1, 1, 1 + 2_000_000);
        PlayerChangeLatency.uploaded(1, 1, 1 + 6_000_000);
        assertArrayEquals(new double[]{2, 4, 6}, PlayerChangeLatency.stats(), 1e-9);
    }
}
