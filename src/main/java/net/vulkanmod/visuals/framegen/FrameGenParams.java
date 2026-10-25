package net.vulkanmod.visuals.framegen;

import org.joml.Matrix4fc;

import java.nio.ByteBuffer;

/**
 * The std140 uniform block FgParams of the frame generation shaders (shaders/fg/params.glsl, FG design 5): five matrices, four
 * vec4 and the crosshair quads, 640 bytes. The pacer thread writes it into its own host-visible buffers.
 */
public final class FrameGenParams {
    public static final int SIZE = 640;
    public static final int MAX_QUADS = 8;
    /** Offsets in bytes. */
    static final int SIZE_AT = 320, FLAGS_AT = 336, VIGNETTE_AT = 352, CROSSHAIR_AT = 368, QUADS_AT = 384;

    private FrameGenParams() {
    }

    /**
     * Writes the block at dst's position and advances it by {@link #SIZE}. quads holds quadCount x 8 floats: the rect (x, y, w, h in
     * swapchain pixels) then the uv rect (u, v, w, h); the unused entries are zeroed.
     * warped (crosshair.y) is true when the world image is a warped frame whose alpha holds the marks: only then debug view 3 tints.
     */
    public static void write(ByteBuffer dst, Matrix4fc invRealProj, Matrix4fc invLodProj, Matrix4fc genFromReal, Matrix4fc realFromGen,
                             Matrix4fc invGenProj, float worldW, float worldH, float uiW, float uiH, boolean lodValid, boolean depthValid,
                             int fgView, boolean uiValid, boolean warped, float[] vignetteRgb, boolean vignetteOn, float[] quads, int quadCount) {
        if (quadCount < 0 || quadCount > MAX_QUADS || quads.length < quadCount * 8) {
            throw new IllegalArgumentException("crosshair quads: " + quadCount + " of " + quads.length / 8);
        }
        int base = dst.position();
        invRealProj.get(base, dst);
        invLodProj.get(base + 64, dst);
        genFromReal.get(base + 128, dst);
        realFromGen.get(base + 192, dst);
        invGenProj.get(base + 256, dst);
        putVec4(dst, base + SIZE_AT, worldW, worldH, uiW, uiH);
        putVec4(dst, base + FLAGS_AT, lodValid ? 1f : 0f, depthValid ? 1f : 0f, fgView, uiValid ? 1f : 0f);
        putVec4(dst, base + VIGNETTE_AT, vignetteRgb[0], vignetteRgb[1], vignetteRgb[2], vignetteOn ? 1f : 0f);
        putVec4(dst, base + CROSSHAIR_AT, quadCount, warped ? 1f : 0f, 0f, 0f);
        for (int i = 0; i < 2 * MAX_QUADS; i++) {
            int n = i * 4;
            boolean used = i < 2 * quadCount;
            putVec4(dst, base + QUADS_AT + n * 4, used ? quads[n] : 0f, used ? quads[n + 1] : 0f, used ? quads[n + 2] : 0f, used ? quads[n + 3] : 0f);
        }
        dst.position(base + SIZE);
    }

    /**
     * The pacer's block (FG design 3.1): the render thread's captured block of the slot (captured, from position 0) with the pacer's own
     * fields written over it: the real inverse projection, the three warp matrices, the sizes and warped. The LOD inverse projection,
     * the flags (LOD and depth validity, debug view, UI validity), the vignette and the crosshair quads stay as the render thread wrote
     * them. Writes at dst's position and advances it by {@link #SIZE}.
     */
    public static void writePacer(ByteBuffer dst, ByteBuffer captured, Matrix4fc invRealProj, Matrix4fc genFromReal, Matrix4fc realFromGen,
                                  Matrix4fc invGenProj, float worldW, float worldH, float uiW, float uiH, boolean warped) {
        int base = dst.position();
        for (int i = 0; i < SIZE; i++) dst.put(base + i, captured.get(i));
        invRealProj.get(base, dst);
        genFromReal.get(base + 128, dst);
        realFromGen.get(base + 192, dst);
        invGenProj.get(base + 256, dst);
        putVec4(dst, base + SIZE_AT, worldW, worldH, uiW, uiH);
        dst.putFloat(base + CROSSHAIR_AT + 4, warped ? 1f : 0f);
        dst.position(base + SIZE);
    }

    private static void putVec4(ByteBuffer dst, int at, float x, float y, float z, float w) {
        dst.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
    }
}
