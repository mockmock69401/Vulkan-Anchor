package net.vulkanmod.visuals.dh;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.EmptyLevelChunk;

/**
 * 2026-10-23 (CTE2: the integrated server stopped ticking for 1 to 5 s at a time while Distant Horizons generated LODs, none with its
 * generation off): Distant Horizons' generator (FEATURES) must not make the server thread load or generate real chunks.
 * <ul>
 * <li>Vanilla's Blender asks WorldGenRegion.isOldChunkAround for each chunk group, which IOWorker answers by scanning a region's 1024
 * chunks on its one queue, at the priority of the server's own loads; from six threads over hundreds of regions the server's chunk and
 * POI loads waited behind it. Blending only joins chunks saved before 1.18, which a distant LOD never shows: Distant Horizons' regions
 * skip it (WorldGenRegionBlendingMixin).</li>
 * <li>Mods' features look up the real world, not the generation region (Biomes We've Gone's large pumpkins ask the ServerLevel's
 * StructureManager), and ServerChunkCache.getChunk off the server thread hands each such request to the server thread, which loads or
 * generates that chunk at LOD distance before the tick goes on. From Distant Horizons' generation steps such a request gets an empty
 * chunk (null where the caller allows none) instead (ServerChunkCacheDhIsolationMixin): no structures, air.</li>
 * </ul>
 */
public final class DhWorldGenIsolation {
    /** Distant Horizons' WorldGenRegion subclass (common.wrappers.worldGeneration.mimicObject). */
    public static final String DH_REGION = "com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion";
    /** Its generation steps (StepBiomes, StepFeatures, ...); its other modes ask the server for chunks on purpose and keep doing so. */
    public static final String DH_STEPS = "com.seibel.distanthorizons.common.wrappers.worldGeneration.step.";
    /** Its world generation threads' name. */
    public static final String DH_WORLD_GEN_THREAD = "DH-World Gen";

    private DhWorldGenIsolation() {
    }

    /**
     * Whether a WorldGenRegion of this class is Distant Horizons' (it, or a subclass of it). DH's released jars hold the Fabric and
     * the Forge (or NeoForge) build at once and name the loader's copy of a class with a suffix (DhLitWorldGenRegion_forge).
     */
    public static boolean isDhRegion(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (isDhRegionName(k.getName())) return true;
        }
        return false;
    }

    static boolean isDhRegionName(String name) {
        return DH_REGION.equals(name) || name.startsWith(DH_REGION + "_");
    }

    /** Whether the calling thread is in one of Distant Horizons' generation steps (its thread name first: the walk is the slow part). */
    public static boolean inDhGenerationStep() {
        if (!Thread.currentThread().getName().startsWith(DH_WORLD_GEN_THREAD)) return false;
        return StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().startsWith(DH_STEPS)));
    }

    /** The chunk a generation step gets for a real chunk the server would have to load: empty, plains. */
    public static EmptyLevelChunk emptyChunk(ServerLevel level, int x, int z) {
        return new EmptyLevelChunk(level, new ChunkPos(x, z), level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
    }
}
