package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.render.TaaMath;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * The Java mirror of the frame generation warp shaders (FG design 3.2): the last real frame's pixels and view depths are splatted
 * into a generated frame's camera, holes are filled from the nearest valid pixels. Every constant here has the shader constant of
 * the same name and value. Pixel convention as TaaMath.reproject: u = x / width, v = 1 - y / height, top-left origin, pixel k's
 * centre at k + 0.5. Pure: no Vulkan, no clock.
 */
public final class WarpMath {
    /** The splat writes a SPLAT_FOOTPRINT x SPLAT_FOOTPRINT (2x2) block of target pixels whose top-left is floor(target - 0.5): offsets (0,0), (1,0), (0,1), (1,1). */
    public static final int SPLAT_FOOTPRINT = 2;
    /** The hole fill searches this many directions, */
    public static final int FILL_DIRECTIONS = 8;
    /** out to this many pixels. */
    public static final int FILL_RADIUS = 16;
    /** A source pixel is accepted when its depth is within this fraction of the expected one. */
    public static final float DISOCCLUSION_DEPTH = TaaMath.DISOCCLUSION_DEPTH;
    public static final float SKY_DEPTH = TaaMath.SKY_DEPTH;
    /** Validity marks of the warped frame. */
    public static final float HOLE_MARK = 0f, EDGE_MARK = 0.5f, VALID_MARK = 1f;
    /** An unwritten splat depth cell (all bits set: loses no atomicMin, reads as empty). */
    public static final int EMPTY = 0xFFFFFFFF;

    private WarpMath() {
    }

    /** View positions (w 1) and directions (w 0) of the real camera to the generated camera's clip space. */
    static Matrix4f genFromReal(TaaMath.Camera gen, TaaMath.Camera real) {
        return TaaMath.targetFromView(gen, new Matrix4f(real.modelView()).invert(), real.x(), real.y(), real.z());
    }

    /**
     * The point on pixel (u, v)'s ray at positive view depth viewDepth. The ray is the line through the unprojected near (NDC z 0) and
     * far (z 1) points A and B, so a translation inside the projection (view bobbing) stays exact: P = A + (B - A) s with -P.z = viewDepth.
     */
    static float[] viewPos(Matrix4fc invProj, float u, float v, float viewDepth) {
        Vector4f a = invProj.transform(new Vector4f(u * 2f - 1f, v * 2f - 1f, 0f, 1f));
        Vector4f b = invProj.transform(new Vector4f(u * 2f - 1f, v * 2f - 1f, 1f, 1f));
        float ax = a.x / a.w, ay = a.y / a.w, az = a.z / a.w, bx = b.x / b.w, by = b.y / b.w, bz = b.z / b.w;
        float s = (-viewDepth - az) / (bz - az);
        return new float[]{ax + (bx - ax) * s, ay + (by - ay) * s, -viewDepth};
    }

    /**
     * Where the real frame's pixel (x, y) with view depth viewDepth lands in the generated frame: {x, y, genViewDepth} in pixels, or
     * null behind the camera. The generated view depth is the clip w (for a standard perspective projection w equals -z of the view).
     */
    static float[] splatTarget(Matrix4fc invRealProj, Matrix4fc genFromReal, float x, float y, float viewDepth, int w, int h) {
        float[] p = viewPos(invRealProj, x / w, 1f - y / h, viewDepth);
        Vector4f clip = genFromReal.transform(new Vector4f(p[0], p[1], p[2], 1f));
        if (clip.w <= 0f) return null;
        float u = clip.x / clip.w * 0.5f + 0.5f, v = clip.y / clip.w * 0.5f + 0.5f;
        return new float[]{u * w, (1f - v) * h, clip.w};
    }

    /** Whether a source pixel's view depth fits the depth the target pixel expects. */
    static boolean accept(float sourceViewDepth, float expectedViewDepth) {
        return Math.abs(sourceViewDepth - expectedViewDepth) <= DISOCCLUSION_DEPTH * expectedViewDepth;
    }

    /** The pixel inside the image nearest to (x, y): the fetch clamps to the edge. */
    static int[] edgeClamp(float x, float y, int w, int h) {
        return new int[]{Math.max(0, Math.min(w - 1, (int) Math.floor(x))), Math.max(0, Math.min(h - 1, (int) Math.floor(y)))};
    }

    /** Positive floats order as unsigned integers, so an integer atomicMin keeps the nearest depth. */
    static int depthBits(float viewDepth) {
        return Float.floatToRawIntBits(viewDepth);
    }
}
