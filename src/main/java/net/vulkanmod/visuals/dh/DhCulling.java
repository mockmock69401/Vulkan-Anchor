package net.vulkanmod.visuals.dh;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Which of DH's draws can put a pixel on screen (Task 5b). DH culls its render list per section, with a box of the section's
 * width and the level's whole height, and only while its frustum culling is on; the fork's frustum keeps every section for the
 * shadow casters (DhCasterFrustum). So the fork culls each buffer itself, by its own box: its corner plus its vertices' bounds
 * (kept at upload, DhVertexBuffer.Bounds), x and z widened by DH's micro offset (terrain.vert moves corners along x and z by it)
 * and every side by MARGIN (float rounding). A buffer is culled when the box misses the view, or, in the main view, when the
 * whole box is nearer than the discard distance (terrain.frag discards every LOD fragment nearer than that): no fragment of it
 * could have been written either way. A draw whose bounds are unknown is drawn.
 */
final class DhCulling {
    /** Added to every side of a box: blocks, past what the vertices and the micro offset reach (float rounding of the corner). */
    static final float MARGIN = 1 / 16f;
    /** mainView's answers: drawn, the box outside the view, the box wholly inside the near discard, or DH's section frustum left it out. */
    static final int DRAWN = 0, OUT_OF_VIEW = 1, NEAR = 2, DH_FRUSTUM = 3;

    private DhCulling() {
    }

    /** The matrix the main view's draws are recorded with: the frame's DH projection (0..1 depth) times its model view. */
    static Matrix4f viewProjection(DhFrameCapture.Frame f) {
        return new Matrix4f(f.dhProjectionVk()).mul(f.dhModelView());
    }

    /**
     * The box of d's buffer relative to (camX, camY, camZ), into out as {minX, minY, minZ, maxX, maxY, maxZ}: false (out untouched)
     * when its bounds are unknown (no buffer, or one with no vertices).
     */
    static boolean box(DhFrameCapture.Draw d, double camX, double camY, double camZ, float[] out) {
        DhVertexBuffer.Bounds b = d.buffer() == null ? null : d.buffer().bounds();
        if (b == null) return false;
        float pad = DhVertexFormat.MICRO_OFFSET + MARGIN;
        double x = d.originX() - camX, y = d.originY() - camY, z = d.originZ() - camZ;
        out[0] = (float) (x + b.minX()) - pad;
        out[1] = (float) (y + b.minY()) - MARGIN;
        out[2] = (float) (z + b.minZ()) - pad;
        out[3] = (float) (x + b.maxX()) + pad;
        out[4] = (float) (y + b.maxY()) + MARGIN;
        out[5] = (float) (z + b.maxZ()) + pad;
        return true;
    }

    /**
     * Whether box reaches the clip volume of m (Vulkan's: -w <= x, y <= w and 0 <= z <= w): false only when all eight corners are
     * past the same plane. The planes are tested in homogeneous coordinates, so a perspective's corners behind the camera (w < 0)
     * count right; an orthographic m (w = 1) is the plain box test.
     */
    static boolean inClipVolume(Matrix4fc m, float[] box) {
        int all = 0x3F;
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? box[0] : box[3], y = (i & 2) == 0 ? box[1] : box[4], z = (i & 4) == 0 ? box[2] : box[5];
            float cx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            float cy = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            float cz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
            float cw = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
            int out = (cx < -cw ? 1 : 0) | (cx > cw ? 2 : 0) | (cy < -cw ? 4 : 0) | (cy > cw ? 8 : 0) | (cz < 0 ? 16 : 0) | (cz > cw ? 32 : 0);
            all &= out;
            if (all == 0) return true;
        }
        return false;
    }

    /** Whether every point of box is nearer the camera (the origin) than distance; false for no distance (0 or NaN). */
    static boolean withinDistance(float[] box, float distance) {
        if (!(distance > 0)) return false;
        float dx = Math.max(Math.abs(box[0]), Math.abs(box[3]));
        float dy = Math.max(Math.abs(box[1]), Math.abs(box[4]));
        float dz = Math.max(Math.abs(box[2]), Math.abs(box[5]));
        return dx * dx + dy * dy + dz * dz < distance * distance;
    }

    /**
     * The main view's answer for d (DRAWN, DH_FRUSTUM, OUT_OF_VIEW or NEAR): DH's own section frustum left it out, its box misses the view
     * (viewProjection, positions relative to the camera at camX, camY, camZ), or the whole box is nearer than discardDistance
     * (0 for no discard). scratch holds the box (6 floats).
     */
    static int mainView(DhFrameCapture.Draw d, Matrix4fc viewProjection, double camX, double camY, double camZ, float discardDistance, float[] scratch) {
        if (!d.inView()) return DH_FRUSTUM;
        if (!box(d, camX, camY, camZ, scratch)) return DRAWN;
        if (!inClipVolume(viewProjection, scratch)) return OUT_OF_VIEW;
        return withinDistance(scratch, discardDistance) ? NEAR : DRAWN;
    }

    /** Whether two draws are buffers of one container (the capture keeps a container's buffers in a row, with its corner). */
    static boolean sameContainer(DhFrameCapture.Draw a, DhFrameCapture.Draw b) {
        return a != null && b != null && a.originX() == b.originX() && a.originY() == b.originY() && a.originZ() == b.originZ();
    }
}
