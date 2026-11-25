package net.vulkanmod.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-25: a region chunk that has not reached the asked status reads as "no chunk", as in vanilla (Zero to Engineering crash). */
class WorldGenChunkStatusTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The crash: Twilight Forest asks a neighbour for biomes while that neighbour has only its structure starts. */
    @Test
    void aChunkWithoutBiomesIsMissingForABiomeRead() {
        assertTrue(WorldGenChunkStatus.missing(ChunkStatus.STRUCTURE_STARTS, ChunkStatus.BIOMES));
        assertTrue(WorldGenChunkStatus.missing(ChunkStatus.EMPTY, ChunkStatus.BIOMES));
    }

    @Test
    void aChunkAtOrPastTheAskedStatusIsThere() {
        assertFalse(WorldGenChunkStatus.missing(ChunkStatus.BIOMES, ChunkStatus.BIOMES));
        assertFalse(WorldGenChunkStatus.missing(ChunkStatus.NOISE, ChunkStatus.BIOMES));
        assertFalse(WorldGenChunkStatus.missing(ChunkStatus.FULL, ChunkStatus.EMPTY));
    }

    /** Row by row from the region's first corner, as vanilla's WorldGenRegion.getChunk indexes its cache. */
    @Test
    void cacheIndexMatchesVanilla() {
        int size = 17;
        assertEquals(0, WorldGenChunkStatus.index(-12, 2, -12, 2, size));
        assertEquals(8 + 8 * size, WorldGenChunkStatus.index(-4, 10, -12, 2, size));
        assertEquals(size * size - 1, WorldGenChunkStatus.index(4, 18, -12, 2, size));
    }
}
