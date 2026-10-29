package net.vulkanmod.render.model.quad;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares identical vertex arrays between baked quads (BakedQuadM) to save memory. 2026-10-26 (Deceased: chunk building took seconds
 * on new terrain, interactions lagged): only while models are baked (ModelBakerImplM marks it). Models that make quads while chunks
 * are built (Create's connected textures clone a quad, then change its UVs in the copy's array) handed every copy to this cache: the
 * copy came back as the shared original, which Create then overwrote, so the cache's keys changed under it, its bins filled with
 * colliding entries and its lookups ate 90% of the chunk builder threads.
 */
public class BakedQuadDeduplicator {

    private static final ConcurrentHashMap<IntArrayKey, int[]> VERTICES_CACHE = new ConcurrentHashMap<>();
    /** How deep this thread is in ModelBaker.bake; arrays are shared only inside. */
    private static final ThreadLocal<int[]> BAKE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    public static void enterBake() {
        BAKE_DEPTH.get()[0]++;
    }

    public static void exitBake() {
        int[] depth = BAKE_DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    /** 2026-10-30 (audit): ModelBakery.bakeModels returned; a bake it caught failing left this thread marked (ModelBakeryM). */
    public static void resetBake() {
        BAKE_DEPTH.get()[0] = 0;
    }

    public static int[] deduplicateVertices(int[] vertices) {
        if (vertices == null || BAKE_DEPTH.get()[0] == 0) return vertices;
        return VERTICES_CACHE.computeIfAbsent(new IntArrayKey(vertices), key -> key.array);
    }

    private static class IntArrayKey {
        private final int[] array;
        private final int hash;

        public IntArrayKey(int[] array) {
            this.array = array;
            this.hash = java.util.Arrays.hashCode(array);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof IntArrayKey)) return false;
            return java.util.Arrays.equals(this.array, ((IntArrayKey) obj).array);
        }
    }
}

