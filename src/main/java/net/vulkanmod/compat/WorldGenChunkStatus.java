package net.vulkanmod.compat;

import net.minecraft.world.level.chunk.ChunkStatus;

/** WorldGenRegionChunkStatusMixin's two rules, kept apart so the tests can check them without a world. */
public final class WorldGenChunkStatus {
    private WorldGenChunkStatus() {
    }

    /** Where chunk (x, z) sits in a region's cache, as vanilla's WorldGenRegion.getChunk works it out. */
    public static int index(int x, int z, int firstX, int firstZ, int size) {
        return (x - firstX) + (z - firstZ) * size;
    }

    /** A chunk that has not reached the asked status yet: vanilla answers "no chunk" for it when the caller allows that. */
    public static boolean missing(ChunkStatus reached, ChunkStatus asked) {
        return !reached.isOrAfter(asked);
    }
}
