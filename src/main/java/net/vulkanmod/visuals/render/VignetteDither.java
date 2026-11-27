package net.vulkanmod.visuals.render;

/**
 * 2026-11-27 (the user: "여전히 밴딩현상이 보이는데 해결할 순 없어?"; the Output Dither setting left rings on the snow): vanilla's GUI
 * vignette (Gui.renderVignette: textures/misc/vignette.png stretched over the screen, the screen times 1 - texel x colour) is blended
 * onto the 8-bit screen after every visuals pass, so its smooth darkening toward the edges lands as rings a step apart around the screen
 * centre (a flat debug colour showed them; so did the game with the visuals off). DitheredVignette draws this in its place: the vanilla
 * texture filtered up to one texel per screen pixel and randomly rounded (the noise of post/dither.glsl, triangular, AMPLITUDE steps
 * either way), so the blend's result follows the smooth darkening on average. The darkening stays vanilla's: the same texture, the
 * same colour (its brightness or the world border's tint), drawn at the same moment, over the hand as well.
 */
public final class VignetteDither {
    /**
     * The noise's reach in 8-bit steps of the texture, either way. The blend scales a texel step by the colour under it times the
     * vignette's colour (at most one output step), so the texture needs more than one step of noise to round the output randomly: two
     * gave the output's local means no preference for the 8-bit grid in a simulation (as a float dither), one did not.
     */
    public static final float AMPLITUDE = 2f;

    private VignetteDither() {
    }

    /**
     * The dithered vignette: src (ABGR pixels as NativeImage holds them, srcWidth x srcHeight) filtered bilinearly over width x height
     * pixels, as the texture is stretched over the screen, each colour channel rounded with the triangular noise; the alpha is filtered
     * and rounded plainly. The noise never reaches past 0 or 255, so a texel at either end (no darkening, full darkening) stays there.
     */
    public static int[] bake(int[] src, int srcWidth, int srcHeight, int width, int height) {
        int[] out = new int[width * height];
        float sx = (float) srcWidth / width, sy = (float) srcHeight / height;
        for (int y = 0; y < height; y++) {
            float fy = (y + 0.5f) * sy - 0.5f;
            int y0 = (int) Math.floor(fy);
            float ty = fy - y0;
            int r0 = Math.max(0, Math.min(srcHeight - 1, y0)) * srcWidth, r1 = Math.max(0, Math.min(srcHeight - 1, y0 + 1)) * srcWidth;
            for (int x = 0; x < width; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                int x0 = (int) Math.floor(fx);
                float tx = fx - x0;
                int c0 = Math.max(0, Math.min(srcWidth - 1, x0)), c1 = Math.max(0, Math.min(srcWidth - 1, x0 + 1));
                int a = src[r0 + c0], b = src[r0 + c1], c = src[r1 + c0], d = src[r1 + c1];
                float noise = noise(x, y) + noise(y, x) - 1f;
                int p = 0;
                for (int shift = 0; shift < 24; shift += 8) {
                    float v = bilinear(a, b, c, d, shift, tx, ty);
                    float reach = Math.min(AMPLITUDE, Math.min(v, 255f - v));
                    p |= clampByte(Math.round(v + noise * reach)) << shift;
                }
                p |= clampByte(Math.round(bilinear(a, b, c, d, 24, tx, ty))) << 24;
                out[y * width + x] = p;
            }
        }
        return out;
    }

    private static float bilinear(int a, int b, int c, int d, int shift, float tx, float ty) {
        float top = channel(a, shift) + (channel(b, shift) - channel(a, shift)) * tx;
        float bottom = channel(c, shift) + (channel(d, shift) - channel(c, shift)) * tx;
        return top + (bottom - top) * ty;
    }

    private static int channel(int p, int shift) {
        return (p >>> shift) & 255;
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Interleaved gradient noise (Jimenez 2014) at pixel (x, y), as postmath.glsl's and post/dither.glsl's. */
    static float noise(float x, float y) {
        double a = x * PostMath.IGN_X + y * PostMath.IGN_Y;
        double b = PostMath.IGN_SCALE * (a - Math.floor(a));
        return (float) (b - Math.floor(b));
    }
}
