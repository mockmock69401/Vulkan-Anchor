package dev.vulkanfix.compat;

/**
 * Packs a normal into the vanilla NORMAL vertex element (3 signed bytes), clamping each component to [-1, 1] first
 * exactly like 1.20.1's BufferBuilder.normalIntValue. GUI item poses produce normals around 1e25 (vanilla
 * PoseStack.scale + Mth.fastInvCubeRoot on a negative scale), which only come out right because of that clamp.
 */
public final class NormalPacking {
    private NormalPacking() {
    }

    public static int pack(float x, float y, float z) {
        return component(x) | component(y) << 8 | component(z) << 16;
    }

    private static int component(float f) {
        return (int) (Math.max(-1.0F, Math.min(1.0F, f)) * 127.0F) & 0xFF;
    }
}
