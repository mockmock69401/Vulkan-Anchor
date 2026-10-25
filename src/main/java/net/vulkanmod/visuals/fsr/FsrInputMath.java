package net.vulkanmod.visuals.fsr;

import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * FSR design 4.5 step 2: the depth and the camera motion vector FSR reads for one pixel, the Java mirror of fsr_inputs.comp (each
 * method here has a shader block marked with its name: change both together). Pure: no Vulkan.
 *
 * <p>Coordinates are FSR's texture UV: top-left origin, v down, pixel (x, y)'s centre at ((x + 0.5) / width, (y + 0.5) / height).
 * The projections take GL screen coordinates (v up), so v is flipped on the way in and out.
 *
 * <p>Depth: {@code near / viewDistance}, an inverted depth with an infinite far plane in the main projection's near plane (FSR's
 * context flags DEPTH_INVERTED and DEPTH_INFINITE, cameraNear = near). viewDistance is the positive view depth (-z in view space) of
 * whatever placed the pixel, vanilla terrain or an LOD (viewpos.glsl), so LODs kilometres away keep their precision in R32_SFLOAT.
 *
 * <p>Motion: previous UV minus current UV of the pixel's surface point, as the SDK's sample writes them (FSR's motionVectorScale is
 * then the render size, and current + vector is where the point was). Camera motion only: the point is taken as fixed in the world
 * and moved through prevFromView ({@code TaaMath.targetFromView}: this frame's view to the previous frame's clip space).
 *
 * <p>Sky (no geometry: the main depth was not written): depth 0 and the motion of a point at infinity on the pixel's ray, i.e. the
 * ray's direction reprojected with w 0, so the camera's rotation moves the sky and its translation does not. A point behind the
 * previous camera (clip w not positive) has no previous position on screen: its vector is {0, 0}.
 */
public final class FsrInputMath {
    /** The view distance of a pixel with no geometry (the sky). */
    public static final float SKY = Float.POSITIVE_INFINITY;
    /** viewpos.glsl's viewRay: the two NDC depths whose unprojected points give a pixel's ray direction (view bobbing cancels out). */
    public static final float VIEW_RAY_NEAR = 0.5f, VIEW_RAY_FAR = 0.9f;

    private FsrInputMath() {
    }

    /** The depth FSR reads: near / viewDistance, at most 1 (nearer than the near plane); 0 for the sky. */
    public static float depth(float viewDistance, float near) {
        if (viewDistance == SKY) return 0f;
        return near / Math.max(viewDistance, near);
    }

    /**
     * The motion vector {du, dv} (previous UV minus current UV) of the pixel at FSR UV (u, v) whose surface is at positive view depth
     * viewDistance (SKY for none), with invProj the unjittered inverse projection that placed it (the main one, or the LOD one).
     */
    public static float[] motion(Matrix4fc prevFromView, float u, float v, float viewDistance, Matrix4fc invProj) {
        return motionOf(prevFromView, viewPoint(invProj, u, v, viewDistance), u, v);
    }

    /** The UV of pixel (x, y)'s centre in an image of width x height. */
    public static float[] pixelUv(int x, int y, int width, int height) {
        return new float[]{(x + 0.5f) / width, (y + 0.5f) / height};
    }

    /**
     * The view-space point (w 1) on pixel (u, v)'s ray at positive view depth viewDistance, or for the sky the ray's unit direction
     * (w 0, as viewpos.glsl's viewRay). The ray is the line through the points unprojected at NDC z 0 and 1, so a translation inside the
     * projection (view bobbing) stays exact. The shader gets the point from the depth buffers instead (viewpos.glsl scenePosition): the
     * same point.
     */
    static Vector4f viewPoint(Matrix4fc invProj, float u, float v, float viewDistance) {
        float x = u * 2f - 1f, y = (1f - v) * 2f - 1f;
        if (viewDistance == SKY) {
            Vector4f a = unproject(invProj, x, y, VIEW_RAY_NEAR), b = unproject(invProj, x, y, VIEW_RAY_FAR);
            Vector4f d = b.sub(a);
            d.w = 0f;
            return d.normalize3();
        }
        Vector4f a = unproject(invProj, x, y, 0f), b = unproject(invProj, x, y, 1f);
        float s = (-viewDistance - a.z) / (b.z - a.z);
        return new Vector4f(a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, -viewDistance, 1f);
    }

    private static Vector4f unproject(Matrix4fc invProj, float x, float y, float z) {
        Vector4f h = invProj.transform(new Vector4f(x, y, z, 1f));
        return new Vector4f(h.x / h.w, h.y / h.w, h.z / h.w, 1f);
    }

    /** The motion vector of a view point (w 1) or direction (w 0) seen at FSR UV (u, v); {0, 0} behind the previous camera. */
    static float[] motionOf(Matrix4fc prevFromView, Vector4fc view, float u, float v) {
        Vector4f clip = prevFromView.transform(new Vector4f(view));
        if (clip.w <= 0f) return new float[]{0f, 0f};
        float prevU = clip.x / clip.w * 0.5f + 0.5f, prevV = 0.5f - clip.y / clip.w * 0.5f;
        return new float[]{prevU - u, prevV - v};
    }
}
