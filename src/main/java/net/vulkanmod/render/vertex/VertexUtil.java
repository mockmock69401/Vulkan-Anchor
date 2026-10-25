package net.vulkanmod.render.vertex;

public class VertexUtil {

    private static final float NORM_INV = 1.0f / 127.0f;

    /**
     * The fork (vulkanfix, NormalPacking): each component clamped to [-1, 1] first, like vanilla's BufferBuilder.normalIntValue. GUI
     * item poses produce normals around 1e25, which otherwise wrap and turn flat items in the hotbar dark.
     */
    public static int packNormal(float x, float y, float z) {
        return dev.vulkanfix.compat.NormalPacking.pack(x, y, z);
    }

    public static int packNormal(int x, int y, int z) {
        return (x & 0xFF) | (y & 0xFF) << 8|  (z & 0xFF) << 16;
    }

    public static float unpackN1(int i) {
        return (byte)(i & 0xFF) * NORM_INV;
    }

    public static float unpackN2(int i) {
        return (byte)((i >> 8) & 0xFF) * NORM_INV;
    }

    public static float unpackN3(int i) {
        return (byte)((i >> 16) & 0xFF) * NORM_INV;
    }

}
