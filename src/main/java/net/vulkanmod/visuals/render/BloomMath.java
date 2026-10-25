package net.vulkanmod.visuals.render;

/**
 * W3: the bloom pyramid in Java, a line-for-line mirror of bloom.comp (every float constant here has the shader constant of
 * the same name and value; BloomTest compares them). It exists for the tests and as the readable statement of the maths.
 *
 * <p>Literature: Jimenez 2014, "Next Generation Post Processing in Call of Duty: Advanced Warfare" (SIGGRAPH), the 13-tap
 * downsample, the tent upsample and the Karis average (with W2's luminance, PostMath.LUMA); the idea of a quadratic knee below
 * the threshold for the soft threshold; Hermite smoothstep for the distance ramp.
 */
public final class BloomMath {
    // Prefilter
    public static final float KNEE = 0.5f, MAX_VALUE = 4096f;
    public static final float TAP_OUTER = 0.125f, TAP_INNER = 0.375f;
    public static final float KARIS_SCALE = 4f;
    public static final float SOFT_EPS = 0.00001f;
    public static final float KNEE_RANGE = 2f, SOFT_DIV = 4f, DEPTH_TAP = 0.25f, HALF_TEXEL = 0.5f;
    public static final float FAR_DEPTH = 1f;
    public static final float DEPTH_DEN_MIN = 0.000000001f;
    // DOWN
    public static final float DOWN_CENTRE = 0.125f, DOWN_INNER = 0.125f, DOWN_EDGE = 0.0625f, DOWN_CORNER = 0.03125f;
    public static final float DOWN_INNER_OFFSET = 1f, DOWN_OUTER_OFFSET = 2f;
    // UP
    public static final float UP_SCALE = 0.5f, UP_CENTRE = 0.25f, UP_EDGE = 0.125f, UP_CORNER = 0.0625f, UP_RADIUS = 1f;

    /** An RGB float image, row 0 first. */
    public static final class Img {
        public final int w, h;
        public final float[] d;

        public Img(int w, int h) {
            this.w = w;
            this.h = h;
            this.d = new float[w * h * 3];
        }

        public static Img uniform(int w, int h, float r, float g, float b) {
            Img i = new Img(w, h);
            for (int k = 0; k < w * h; k++) {
                i.d[k * 3] = r;
                i.d[k * 3 + 1] = g;
                i.d[k * 3 + 2] = b;
            }
            return i;
        }

        public float get(int x, int y, int c) {
            return this.d[(y * this.w + x) * 3 + c];
        }

        public void set(int x, int y, int c, float v) {
            this.d[(y * this.w + x) * 3 + c] = v;
        }

        /** Bilinear sample at a position in texels (texel k has its centre at k + 0.5), clamp to edge. */
        float sample(float x, float y, int c) {
            float fx = Math.max(0f, Math.min(this.w - 1f, x - HALF_TEXEL)), fy = Math.max(0f, Math.min(this.h - 1f, y - HALF_TEXEL));
            int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
            int x1 = Math.min(this.w - 1, x0 + 1), y1 = Math.min(this.h - 1, y0 + 1);
            float tx = fx - x0, ty = fy - y0;
            float top = get(x0, y0, c) * (1 - tx) + get(x1, y0, c) * tx;
            float bottom = get(x0, y1, c) * (1 - tx) + get(x1, y1, c) * tx;
            return top * (1 - ty) + bottom * ty;
        }
    }

    private BloomMath() {
    }

    /** bloom.comp distanceGlow(): how much of its own colour a pixel of this main depth adds to the glow. */
    public static float distanceGlow(float depth, BloomFog.Values v) {
        if (depth >= FAR_DEPTH) return v.amount();
        float den = v.depthC() * depth + v.depthE();
        if (!(den > DEPTH_DEN_MIN)) return v.amount();
        return v.glowAt((v.depthA() * depth + v.depthB()) / den);
    }

    /** NaN becomes 0, the rest is kept in [0, MAX_VALUE]. */
    static float[] clean(float r, float g, float b) {
        if (Float.isNaN(r) || Float.isNaN(g) || Float.isNaN(b)) return new float[]{0, 0, 0};
        return new float[]{clamp(r), clamp(g), clamp(b)};
    }

    private static float clamp(float x) {
        return Math.max(0f, Math.min(MAX_VALUE, x));
    }

    /** bloom.comp softThreshold(). */
    static float[] softThreshold(float[] c, float threshold) {
        float br = Math.max(c[0], Math.max(c[1], c[2]));
        float rq = Math.max(0f, Math.min(KNEE_RANGE * KNEE, br - threshold + KNEE));
        rq = rq * rq / (SOFT_DIV * KNEE + SOFT_EPS);
        float k = Math.max(rq, br - threshold) / Math.max(br, SOFT_EPS);
        return new float[]{c[0] * k, c[1] * k, c[2] * k};
    }

    /** PREFILTER: scene (w x h) and its main depth (w x h, row 0 first) to level 0, ceil(w/2) x ceil(h/2). */
    public static Img prefilter(Img scene, float[] depth, BloomFog.Values v) {
        Img out = new Img((scene.w + 1) / 2, (scene.h + 1) / 2);
        for (int qy = 0; qy < out.h; qy++) {
            for (int qx = 0; qx < out.w; qx++) {
                float[] sumBright = new float[3], sumColour = new float[3];
                float sumTent = 0;
                float sumWeight = 0;
                for (int j = 0; j < 4; j++) {
                    float wy = (j == 1 || j == 2) ? TAP_INNER : TAP_OUTER;
                    for (int i = 0; i < 4; i++) {
                        float wx = (i == 1 || i == 2) ? TAP_INNER : TAP_OUTER;
                        int px = Math.max(0, Math.min(scene.w - 1, 2 * qx + i - 1)), py = Math.max(0, Math.min(scene.h - 1, 2 * qy + j - 1));
                        float[] c = clean(scene.get(px, py, 0), scene.get(px, py, 1), scene.get(px, py, 2));
                        float tent = wx * wy;
                        float w = tent / (1f + PostMath.luminance(c[0], c[1], c[2]) / KARIS_SCALE);
                        float[] t = softThreshold(c, v.threshold());
                        for (int k = 0; k < 3; k++) {
                            sumBright[k] += w * t[k];
                            sumColour[k] += tent * c[k];
                        }
                        sumTent += tent;
                        sumWeight += w;
                    }
                }
                float glow = 0;
                for (int j = 0; j < 2; j++) {
                    for (int i = 0; i < 2; i++) {
                        int px = Math.max(0, Math.min(scene.w - 1, 2 * qx + i)), py = Math.max(0, Math.min(scene.h - 1, 2 * qy + j));
                        glow += DEPTH_TAP * distanceGlow(depth[py * scene.w + px], v);
                    }
                }
                for (int k = 0; k < 3; k++) out.set(qx, qy, k, Math.min(sumColour[k] / sumTent * (1f + glow) + sumBright[k] / sumWeight, MAX_VALUE));
            }
        }
        return out;
    }

    /** DOWN: 13 taps around the edge between source texels 2q and 2q+1 (weights sum to 1). */
    public static Img down(Img src) {
        Img out = new Img((src.w + 1) / 2, (src.h + 1) / 2);
        for (int qy = 0; qy < out.h; qy++) {
            for (int qx = 0; qx < out.w; qx++) {
                float cx = 2 * qx + 1, cy = 2 * qy + 1;
                for (int c = 0; c < 3; c++) {
                    float sum = DOWN_CENTRE * src.sample(cx, cy, c);
                    for (int j = -1; j <= 1; j += 2) {
                        for (int i = -1; i <= 1; i += 2) {
                            sum += DOWN_INNER * src.sample(cx + i * DOWN_INNER_OFFSET, cy + j * DOWN_INNER_OFFSET, c);
                            sum += DOWN_CORNER * src.sample(cx + i * DOWN_OUTER_OFFSET, cy + j * DOWN_OUTER_OFFSET, c);
                        }
                    }
                    sum += DOWN_EDGE * (src.sample(cx + DOWN_OUTER_OFFSET, cy, c) + src.sample(cx - DOWN_OUTER_OFFSET, cy, c)
                            + src.sample(cx, cy + DOWN_OUTER_OFFSET, c) + src.sample(cx, cy - DOWN_OUTER_OFFSET, c));
                    out.set(qx, qy, c, sum);
                }
            }
        }
        return out;
    }

    /** UP: dst plus src upsampled by a 3x3 tent (source texel j covers destination texels 2j and 2j+1). */
    public static Img up(Img dst, Img src) {
        Img out = new Img(dst.w, dst.h);
        for (int py = 0; py < dst.h; py++) {
            for (int px = 0; px < dst.w; px++) {
                float cx = (px + HALF_TEXEL) * UP_SCALE, cy = (py + HALF_TEXEL) * UP_SCALE;
                for (int c = 0; c < 3; c++) {
                    float sum = UP_CENTRE * src.sample(cx, cy, c);
                    for (int j = -1; j <= 1; j += 2) {
                        for (int i = -1; i <= 1; i += 2) sum += UP_CORNER * src.sample(cx + i * UP_RADIUS, cy + j * UP_RADIUS, c);
                    }
                    sum += UP_EDGE * (src.sample(cx + UP_RADIUS, cy, c) + src.sample(cx - UP_RADIUS, cy, c)
                            + src.sample(cx, cy + UP_RADIUS, c) + src.sample(cx, cy - UP_RADIUS, c));
                    out.set(px, py, c, dst.get(px, py, c) + sum);
                }
            }
        }
        return out;
    }

    /** The whole chain as the dispatcher runs it (PostMath.bloomSizes levels); element 0 is the glow image W2 reads. */
    public static Img[] chain(Img scene, float[] depth, BloomFog.Values v) {
        int levels = PostMath.bloomSizes(scene.w, scene.h).length;
        Img[] pyramid = new Img[levels];
        pyramid[0] = prefilter(scene, depth, v);
        for (int i = 1; i < levels; i++) pyramid[i] = down(pyramid[i - 1]);
        for (int i = levels - 2; i >= 0; i--) pyramid[i] = up(pyramid[i], pyramid[i + 1]);
        return pyramid;
    }
}
