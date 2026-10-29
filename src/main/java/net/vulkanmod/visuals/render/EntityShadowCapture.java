package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * 2026-10-29 (the user: Complementary Reimagined's High profile has entity shadows; the sun shadow maps hold terrain only): the
 * triangles of the entities this frame draws, kept on the render thread for the entity shadow map (VkEntityShadows). The level
 * render opens the capture for its entity section (LevelRendererEntityShadowMixin); while it is open VulkanMod's immediate draws
 * (Drawer.draw) in the entity vertex format (position first: three floats) give their positions here, turned into camera-relative
 * world space by the inverse of the camera's rotation (frame()) times the model-view they were drawn with, quads split into two
 * triangles. Only entities the camera's view drew cast (vanilla culls the rest), within RADIUS of the camera.
 */
public final class EntityShadowCapture {
    /** The entity map's half width in blocks (VkEntityShadows) and how far from the camera a triangle's first corner may be. */
    public static final float RADIUS = 32;
    static final float REACH = RADIUS * 1.75f;
    /** The most triangle corners one frame keeps (12 bytes each: 7.2 MB); a crowd beyond that casts in part. */
    public static final int MAX_VERTICES = 600_000;

    private static final Matrix4f INV_VIEW = new Matrix4f();
    private static final Matrix4f TO_WORLD = new Matrix4f();
    private static FloatBuffer data;
    private static int vertices;
    private static boolean wanted, open;
    private static long serial = Long.MIN_VALUE;

    private EntityShadowCapture() {
    }

    /** frame(), before the entities: whether this frame captures, and the camera's rotation (the chunks' model-view). Clears the last frame's. */
    public static void beginFrame(long frameSerial, boolean capture, Matrix4fc modelView) {
        serial = frameSerial;
        wanted = capture;
        vertices = 0;
        open = false;
        if (capture) INV_VIEW.set(modelView).invert();
    }

    /** The level render's entity section starts (opens the capture when this frame wants it). */
    public static void open() {
        open = wanted;
    }

    public static void close() {
        open = false;
    }

    public static boolean capturing() {
        return open;
    }

    /** The triangle corners this frame (serial) kept, 0 for another frame's. */
    public static int vertexCount(long frameSerial) {
        return frameSerial == serial ? vertices : 0;
    }

    /** The kept corners (xyz floats), positioned at 0 and limited to vertexCount floats x 3; null before any capture. */
    public static FloatBuffer data() {
        if (data == null) return null;
        return data.duplicate().position(0).limit(vertices * 3);
    }

    /**
     * Drawer.draw while capturing: vertexCount vertices of stride bytes from address, as quads (else triangles), drawn with the
     * model-view modelView.
     */
    public static void capture(long address, int stride, int vertexCount, boolean quads, Matrix4fc modelView) {
        if (!open || vertexCount <= 0) return;
        if (data == null) data = MemoryUtil.memAllocFloat(MAX_VERTICES * 3);
        TO_WORLD.set(INV_VIEW).mul(modelView);
        vertices = append(data, vertices, address, stride, vertexCount, quads, TO_WORLD);
    }

    /**
     * Appends the triangles of vertexCount vertices (stride bytes apart from address, position first) to out (corner count so far
     * given, returned updated) transformed by toWorld; quads (4 corners each) become two triangles (0 1 2, 2 3 0). A primitive whose
     * first corner lies farther than REACH from the camera is left out, as are the primitives past MAX_VERTICES.
     */
    static int append(FloatBuffer out, int count, long address, int stride, int vertexCount, boolean quads, Matrix4fc toWorld) {
        int per = quads ? 4 : 3;
        int emit = quads ? 6 : 3;
        float[] p = new float[12];
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (int first = 0; first + per <= vertexCount; first += per) {
            if (count + emit > MAX_VERTICES) break;
            for (int k = 0; k < per; k++) {
                long a = address + (long) (first + k) * stride;
                v.set(MemoryUtil.memGetFloat(a), MemoryUtil.memGetFloat(a + 4), MemoryUtil.memGetFloat(a + 8));
                toWorld.transformPosition(v);
                p[k * 3] = v.x;
                p[k * 3 + 1] = v.y;
                p[k * 3 + 2] = v.z;
            }
            if (p[0] * p[0] + p[1] * p[1] + p[2] * p[2] > REACH * REACH) continue;
            int base = count * 3;
            put(out, base, p, 0);
            put(out, base + 3, p, 1);
            put(out, base + 6, p, 2);
            if (quads) {
                put(out, base + 9, p, 2);
                put(out, base + 12, p, 3);
                put(out, base + 15, p, 0);
            }
            count += emit;
        }
        return count;
    }

    private static void put(FloatBuffer out, int at, float[] p, int corner) {
        out.put(at, p[corner * 3]).put(at + 1, p[corner * 3 + 1]).put(at + 2, p[corner * 3 + 2]);
    }
}
