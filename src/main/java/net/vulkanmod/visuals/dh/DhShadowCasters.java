package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.render.ShadowMath;
import org.joml.Matrix4fc;

/**
 * Where DH's opaque LODs cast into a sun shadow cascade. The vanilla terrain casts in a square around the camera
 * (VkVanillaShadows: sections whose centre is within the cascade's caster radius on both horizontal axes, so every block within
 * that radius), with its cutouts' own shapes; DH's colour-only LODs would cast leaves as solid blocks there. So a LOD casts only
 * outside that square (the cutoff, 0 when the vanilla shadows are off and the LODs are the only casters) and
 * only where its box reaches the cascade's orthographic box. Task 5b: the box is the buffer's own (its vertices' bounds), not its
 * section's column of the level's height.
 */
final class DhShadowCasters {
    private DhShadowCasters() {
    }

    /** The cascade VkShadowMaps names "shadows.close", "shadows.near" or "shadows.far". */
    static ShadowMath.Which which(String cascade) {
        if ("shadows.far".equals(cascade)) return ShadowMath.Which.FAR;
        if ("shadows.close".equals(cascade)) return ShadowMath.Which.CLOSE;
        return ShadowMath.Which.NEAR;
    }

    /** The half side, in blocks, of the square around the camera the vanilla terrain casts in for this cascade; 0 without vanilla casters. */
    static float cutoff(String cascade, boolean vanillaShadows, int renderDistanceChunks) {
        return vanillaShadows ? ShadowMath.vanillaRadius(which(cascade), renderDistanceChunks) : 0;
    }

    /**
     * The same at a shadow distance in chunks (VisualsConfig.shadowDistance), while the LODs cast: then every cascade's vanilla
     * terrain ends at the shadow distance (ShadowMath.vanillaRadius's lodBeyond) and the LODs cast past it.
     */
    static float cutoff(String cascade, boolean vanillaShadows, int renderDistanceChunks, int shadowDistanceChunks) {
        return vanillaShadows ? ShadowMath.vanillaRadius(which(cascade), renderDistanceChunks, ShadowMath.nearReach(shadowDistanceChunks), true) : 0;
    }

    /**
     * Whether the box (blocks, relative to the cascade's camera) reaches the orthographic box of viewProjection: false only when
     * all eight corners are past the same side (x or y outside -1..1, depth outside 0..1).
     */
    static boolean visible(Matrix4fc vp, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return DhCulling.inClipVolume(vp, new float[]{minX, minY, minZ, maxX, maxY, maxZ});
    }

    /**
     * Whether a draw casts into the cascade: its buffer's own box (DhCulling.box: its vertices' bounds, the micro offset and a
     * margin) leaves the cutoff square and reaches the cascade. A draw whose bounds are unknown is drawn; shadow.frag still
     * discards inside the cutoff.
     */
    static boolean casts(DhFrameCapture.Draw d, ShadowMath.Cascade cascade, float cutoff) {
        return casts(d, cascade, cascade.viewProjection(), cutoff, new float[6]);
    }

    /**
     * Performance (2026-10-21): whether d belongs to slice of a refresh spread over slices frames (ShadowCascadeContext): by its
     * corner, so a container keeps its slice while DH re-sorts its list and every container is drawn once per refresh.
     */
    static boolean inSlice(DhFrameCapture.Draw d, int slice, int slices) {
        return slices <= 1 || sliceOf(d.originX(), d.originY(), d.originZ(), slices) == slice;
    }

    static int sliceOf(long x, long y, long z, int slices) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) Math.floorMod(h, (long) slices);
    }

    /** The same with the cascade's view projection made once for all of a cascade's draws; box is scratch (6 floats). */
    static boolean casts(DhFrameCapture.Draw d, ShadowMath.Cascade cascade, Matrix4fc viewProjection, float cutoff, float[] box) {
        if (!DhCulling.box(d, cascade.cameraX(), cascade.cameraY(), cascade.cameraZ(), box)) return true;
        float reach = Math.max(Math.max(Math.abs(box[0]), Math.abs(box[3])), Math.max(Math.abs(box[2]), Math.abs(box[5])));
        if (reach < cutoff) return false;
        return DhCulling.inClipVolume(viewProjection, box);
    }
}
