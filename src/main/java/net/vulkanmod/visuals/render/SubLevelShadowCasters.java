package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.FloatBuffer;

/**
 * 2026-11-07: the sections of Sable's sub-levels (physics structures) this frame drew, kept on the render thread for the sun shadows.
 * The shadow maps read VulkanMod's own terrain buffers, and a sub-level's sections are vanilla vertex buffers Sable draws by itself,
 * so they are recorded as Sable draws them (as Iris' shadow pass gets them by having Sable draw there): the buffer, the model-view
 * and the section offset of each draw, in the opaque and cutout layers only. Sable's draw also knows no culling, so what is kept is
 * every section of every sub-level. The level render empties the list; the entries are reused, a frame allocates nothing.
 */
public final class SubLevelShadowCasters {
    /** The most section draws one frame keeps; a structure beyond that casts in part. */
    public static final int MAX_CASTERS = 8192;

    /** What a caster draws with: its vertices and indices, bound and drawn into the shadow pass open on cmd. False when it has none now. */
    public interface Drawable {
        boolean recordShadowDraw(VkCommandBuffer cmd);
    }

    /** One draw: the buffer, the model-view it was drawn with, the section's offset (Sable's ChunkOffset), the layer's alpha cutout. */
    public static final class Caster {
        public Drawable drawable;
        public final Matrix4f modelView = new Matrix4f();
        public float ox, oy, oz;
        public float cutout;
    }

    private static final Caster[] CASTERS = new Caster[MAX_CASTERS];
    private static int count;
    private static boolean layerOpen;
    private static float layerCutout;

    private SubLevelShadowCasters() {
    }

    /** The level render starts: last frame's draws are forgotten, whether or not a post-process used them. */
    public static void beginLevel() {
        for (int i = 0; i < count; i++) {
            CASTERS[i].drawable = null;
        }
        count = 0;
        layerOpen = false;
    }

    /** Sable draws an opaque or cutout layer: its draws are kept with the layer's alpha cutout. */
    public static void beginLayer(float alphaCutout) {
        layerOpen = true;
        layerCutout = alphaCutout;
    }

    public static void endLayer() {
        layerOpen = false;
    }

    public static boolean recording() {
        return layerOpen;
    }

    /** VBO.draw inside a layer: modelView is the 16 floats Sable gave the shader for this draw, read at once. */
    public static void record(Drawable drawable, FloatBuffer modelView, float ox, float oy, float oz) {
        if (!layerOpen || count >= MAX_CASTERS) return;
        Caster c = CASTERS[count];
        if (c == null) c = CASTERS[count] = new Caster();
        c.drawable = drawable;
        c.modelView.set(modelView);
        c.ox = ox;
        c.oy = oy;
        c.oz = oz;
        c.cutout = layerCutout;
        count++;
    }

    public static int count() {
        return count;
    }

    public static Caster get(int index) {
        return CASTERS[index];
    }
}
