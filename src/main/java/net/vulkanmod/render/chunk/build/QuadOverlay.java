package net.vulkanmod.render.chunk.build;

/**
 * 2026-11-25 (Conquest the Kingdom's Throne: magma and warped wart blocks drew as flat orange and grey): Continuity draws an emissive
 * texture (Conquest Reforged's "_e" overlays) as a second quad on the very place of the block's own quad, a mostly transparent layer.
 * Through vanilla's block renderer it reaches us inside the block's solid-layer call, where session 23's opaque flag made every texel
 * opaque: the overlay's transparent texels covered the block. A quad that lies exactly on an earlier quad of the same block is such an
 * overlay; it keeps its transparency (TerrainVertexConsumer). The positions are a quad's 4 vertices as x, y, z each.
 */
final class QuadOverlay {
    /** Quads of one block remembered for the test; a block with more simply stops finding overlays past them. */
    static final int MAX_QUADS = 32;
    private static final float EPSILON = 1.0e-4f;

    private QuadOverlay() {
    }

    /** Whether the quad (12 floats from offset q in quad) has the same 4 corners, in any order, as one of the count quads in seen. */
    static boolean coversEarlier(float[] seen, int count, float[] quad, int q) {
        for (int i = 0; i < count; i++) {
            if (sameCorners(seen, i * 12, quad, q)) return true;
        }
        return false;
    }

    private static boolean sameCorners(float[] a, int p, float[] b, int q) {
        for (int i = 0; i < 4; i++) {
            if (!hasCorner(a, p, b[q + i * 3], b[q + i * 3 + 1], b[q + i * 3 + 2])) return false;
        }
        return true;
    }

    private static boolean hasCorner(float[] a, int p, float x, float y, float z) {
        for (int j = 0; j < 4; j++) {
            int k = p + j * 3;
            if (Math.abs(a[k] - x) < EPSILON && Math.abs(a[k + 1] - y) < EPSILON && Math.abs(a[k + 2] - z) < EPSILON) return true;
        }
        return false;
    }
}
