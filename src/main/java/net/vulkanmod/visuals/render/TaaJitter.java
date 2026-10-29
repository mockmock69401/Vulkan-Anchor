package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;

/**
 * W9: the sub-pixel offset of the level's projection for the temporal anti-aliasing. Each jittered frame moves the whole
 * projection by a fraction of a pixel in screen space (clip x += jx * w, y += jy * w, i.e. a translation pre-multiplied onto
 * the projection), so successive frames sample each pixel at different points and VkTaa's history integrates them.
 *
 * <p>The offsets are the first {@link TaaMath#JITTER_PHASES} points of the Halton sequence in bases 2 and 3 (Halton 1960, "On
 * the efficiency of certain quasi-random sequences of points in evaluating multi-dimensional integrals"; used for TAA jitter
 * by Karis 2014, "High Quality Temporal Supersampling", SIGGRAPH), shifted so that their mean over one period is exactly 0.
 *
 * <p>Clean code calls {@link #setActive} once per frame; the mixin calls {@link #apply} on the level's projection, which reads
 * the flag set by the frame before. Render thread only.
 */
public final class TaaJitter {
    private static final float[][] OFFSETS = sequence(TaaMath.JITTER_PHASES);
    private static volatile boolean active;
    private static int phase;
    //The offset (NDC) the last apply() added to the level's projection, 0 when it left it unjittered
    private static float appliedX, appliedY;

    private TaaJitter() {
    }

    /** Jitter the level projection from the next frame on (false: leave it as vanilla builds it). */
    public static void setActive(boolean on) {
        active = on;
    }

    public static boolean active() {
        return active;
    }

    /** The offset in pixels of phase i (any int; the sequence repeats every JITTER_PHASES), each component in (-0.5, 0.5). */
    public static float[] offset(int i) {
        float[] o = OFFSETS[Math.floorMod(i, OFFSETS.length)];
        return new float[]{o[0], o[1]};
    }

    /** The radical inverse of i in the given base (the Halton sequence's i-th coordinate), in [0, 1). */
    static double radicalInverse(int i, int base) {
        double inv = 1.0 / base, f = inv, r = 0;
        while (i > 0) {
            r += f * (i % base);
            i /= base;
            f *= inv;
        }
        return r;
    }

    /** Halton (2, 3) points 1..n, minus their mean: a zero-mean pattern inside the pixel. */
    static float[][] sequence(int n) {
        double[][] d = new double[n][2];
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            d[i][0] = radicalInverse(i + 1, 2);
            d[i][1] = radicalInverse(i + 1, 3);
            mx += d[i][0];
            my += d[i][1];
        }
        mx /= n;
        my /= n;
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = (float) ((d[i][0] - mx) * TaaMath.JITTER_SCALE);
            out[i][1] = (float) ((d[i][1] - my) * TaaMath.JITTER_SCALE);
        }
        return out;
    }

    /**
     * The mixin's hook on the level's projection (modified in place and returned): while active, the next phase's offset for a
     * framebuffer of width x height pixels; otherwise unchanged. Remembers what it added for {@link #appliedNdc}.
     */
    public static Matrix4f apply(Matrix4f projection, int width, int height) {
        if (!active || width <= 0 || height <= 0) {
            appliedX = 0;
            appliedY = 0;
            return projection;
        }
        phase = Math.floorMod(phase + 1, OFFSETS.length);
        float[] o = OFFSETS[phase];
        appliedX = 2f * o[0] / width;
        appliedY = 2f * o[1] / height;
        projection.translateLocal(appliedX, appliedY, 0);
        return projection;
    }

    /**
     * Final fix R4: the size the jitter is a pixel of. The level is drawn into the bound main framebuffer, which Reforged's render
     * scale makes smaller than the window (the post-processing and VkTaa work at its size); the window's size counts only when no
     * framebuffer size is known.
     */
    public static int[] levelSize(int framebufferWidth, int framebufferHeight, int windowWidth, int windowHeight) {
        return framebufferWidth > 0 && framebufferHeight > 0 ? new int[]{framebufferWidth, framebufferHeight} : new int[]{windowWidth, windowHeight};
    }

    /** 2026-10-30 (audit): the projection without the NDC offset jitter that {@link #apply} added to it (a new matrix). */
    public static Matrix4f unjittered(Matrix4f projection, float[] jitter) {
        return new Matrix4f(projection).translateLocal(-jitter[0], -jitter[1], 0);
    }

    /** The NDC offset the last {@link #apply} added to the level projection ((0, 0): none). */
    public static float[] appliedNdc() {
        return new float[]{appliedX, appliedY};
    }

    static void resetForTest() {
        active = false;
        phase = 0;
        appliedX = 0;
        appliedY = 0;
    }
}
