package net.vulkanmod.render.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** chunkRenderReady's memo answers as the map does, through any sequence of status changes and asks (2026-11-21, the 1% lows). */
class ChunkStatusMapMemoTest {
    /** The map's rules without a memo: data and light ready in the 3x3 around, as setChunkStatus / resetChunkStatus keep them. */
    private static final class Reference {
        final Map<Long, Byte> flags = new HashMap<>();

        void set(int x, int z, byte f) {
            this.flags.merge(key(x, z), f, (a, b) -> (byte) (a | b));
        }

        void reset(int x, int z, byte f) {
            this.flags.merge(key(x, z), (byte) 0, (a, b) -> (byte) (a & ~f));
        }

        boolean ready(int x, int z) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if ((this.flags.getOrDefault(key(x + dx, z + dz), (byte) 0) & ChunkStatusMap.CHUNK_READY) != ChunkStatusMap.CHUNK_READY) return false;
            return true;
        }

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }

    @Test
    void answersAsTheMapDoesThroughRandomChanges() {
        Random random = new Random(42);
        ChunkStatusMap map = new ChunkStatusMap(8);
        Reference ref = new Reference();
        //A span wider than the memo (128) so slots collide, around a centre that moves
        for (int step = 0; step < 20_000; step++) {
            int cx = (step / 2000) * 37, x = cx + random.nextInt(140) - 70, z = random.nextInt(140) - 70;
            byte flag = random.nextBoolean() ? ChunkStatusMap.DATA_READY : ChunkStatusMap.LIGHT_READY;
            switch (random.nextInt(4)) {
                case 0, 1 -> {
                    map.setChunkStatus(x, z, flag);
                    ref.set(x, z, flag);
                }
                case 2 -> {
                    map.resetChunkStatus(x, z, flag);
                    ref.reset(x, z, flag);
                }
                default -> {
                }
            }
            //Ask a patch twice (the second from the memo) and a far chunk that shares slots
            for (int i = 0; i < 6; i++) {
                int ax = cx + random.nextInt(140) - 70, az = random.nextInt(140) - 70;
                assertEquals(ref.ready(ax, az), map.chunkRenderReady(ax, az), "step " + step + " at " + ax + ", " + az);
                assertEquals(ref.ready(ax, az), map.chunkRenderReady(ax, az), "again, step " + step);
                assertEquals(ref.ready(ax + 128, az), map.chunkRenderReady(ax + 128, az), "slot twin, step " + step);
            }
        }
    }

    @Test
    void aChangeReachesAnAnswerAlreadyMemoised() {
        ChunkStatusMap map = new ChunkStatusMap(8);
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) map.setChunkStatus(x, z, ChunkStatusMap.DATA_READY);
        assertFalse(map.chunkRenderReady(0, 0));
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) map.setChunkStatus(x, z, ChunkStatusMap.LIGHT_READY);
        assertTrue(map.chunkRenderReady(0, 0));
        map.resetChunkStatus(1, 1, ChunkStatusMap.LIGHT_READY);
        assertFalse(map.chunkRenderReady(0, 0));
    }

    @Test
    void theVersionWrapEmptiesTheMemo() {
        ChunkStatusMap map = new ChunkStatusMap(8);
        map.versionForTest(1);
        assertFalse(map.chunkRenderReady(0, 0));//memoised at version 1
        map.versionForTest(-9);
        //Nine changes before the last one wrap through 0 back to 1: without emptying, version 1's slot would answer
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                map.setChunkStatus(x, z, ChunkStatusMap.DATA_READY);
                map.setChunkStatus(x, z, ChunkStatusMap.LIGHT_READY);
            }
        assertTrue(map.chunkRenderReady(0, 0));
    }
}
