package net.vulkanmod.visuals.render;

/**
 * W8: the maths of volumetric.comp (the march through the sun's cascades) and volumetric_blur.comp, mirrored line for line so the
 * tests can check it. Every {@code const} of those two shaders is a field here of the same name and value (VolumetricLightTest).
 *
 * <p>The mask a texel holds is the single-scattering integral of a homogeneous medium along the view ray,
 * {@code phase(cos) * sum_i (exp(-DENSITY a_i) - exp(-DENSITY b_i)) * V(t_i)}: each segment [a_i, b_i] carries exactly the light the
 * medium scatters toward the camera from it (Beer-Lambert extinction; the single-scattering model of e.g. Nishita et al. 1987,
 * "A shading model for atmospheric scattering considering luminous intensity distribution of light sources"), and V is the sun's
 * visibility from the shadow maps at one point inside the segment. A fully lit ray to distance D gives
 * {@code phase * (1 - exp(-DENSITY D))} whatever the jitter, so jitter shows only where the shadow changes inside a segment.
 */
public final class VolumetricMath {
    private VolumetricMath() {
    }

    // ---- volumetric.comp ----

    /** Samples along each view ray; segment i spans D (i/STEPS)^2 .. D ((i+1)/STEPS)^2: short steps near the camera (tree shadows), long far away. */
    public static final int STEPS = 16;
    /** Extinction (= scattering) coefficient of the air, per block. */
    public static final float DENSITY = 0.0015f;
    /** Henyey-Greenstein asymmetry (Henyey and Greenstein 1941): forward scattering, beams show mostly toward the sun. */
    public static final float PHASE_G = 0.5f;
    /** The ordered-dither matrix's side (Bayer 1973): one ray offset per texel of each 4x4 block. */
    public static final int DITHER_SIZE = 4;

    // ---- volumetric_blur.comp ----

    /**
     * The blur is separable, 2 BLUR_RADIUS + 1 taps an axis: BLUR_EDGE at +-BLUR_RADIUS, BLUR_INNER between. With radius 2 the two
     * end taps fall on the same residue mod DITHER_SIZE, so every residue gets the same weight and a 4x4 dither of the ray offsets
     * averages out exactly on smooth areas (the image stays fixed in screen space, no temporal noise to crawl).
     */
    public static final int BLUR_RADIUS = 2;
    public static final float BLUR_EDGE = 0.125f, BLUR_INNER = 0.25f;

    /** The ray offset (0..1, exclusive) of a texel: a 4x4 Bayer matrix, 4 M2(low bits) + M2(high bits), M2(x, y) = 2 (x ^ y) + y. */
    public static float dither(int x, int y) {
        int ax = x & 1, ay = y & 1, bx = (x >> 1) & 1, by = (y >> 1) & 1;
        int m0 = 2 * (ax ^ ay) + ay;
        int m1 = 2 * (bx ^ by) + by;
        return (4 * m0 + m1 + 0.5f) / (DITHER_SIZE * DITHER_SIZE);
    }

    /** Henyey-Greenstein phase function scaled by 4 pi: 1 is isotropic scattering. cosTheta is between the view ray and the sun direction. */
    public static float phase(float cosTheta) {
        float g2 = PHASE_G * PHASE_G;
        float d = Math.max(1 + g2 - 2 * PHASE_G * cosTheta, 1e-4f);
        return (1 - g2) / (float) Math.pow(d, 1.5);
    }

    /** The sun's visibility (0..1) at distance t along the ray. */
    @FunctionalInterface
    public interface Visibility {
        float at(float t);
    }

    /** volumetric.comp's march: the mask for a ray of length dist with ray offset jitter and the cosine to the sun. */
    public static float march(Visibility v, float dist, float jitter, float cosTheta) {
        float sum = 0;
        float before = 1;
        for (int i = 0; i < STEPS; i++) {
            float u = (i + 1f) / STEPS;
            float after = (float) Math.exp(-DENSITY * dist * u * u);
            float s = (i + jitter) / STEPS;
            float t = dist * s * s;
            sum += (before - after) * v.at(t);
            before = after;
        }
        return sum * phase(cosTheta);
    }

    /** A fully lit ray's mask. */
    public static float litRay(float dist, float cosTheta) {
        return (1 - (float) Math.exp(-DENSITY * dist)) * phase(cosTheta);
    }

    /** A shadow map: the depth it recorded at a point of its clip space (ndc x, y in -1..1). */
    @FunctionalInterface
    public interface ShadowMap {
        float depth(float ndcX, float ndcY);
    }

    /** volumetric.comp's insideCascade: within the map and its depth range. */
    public static boolean insideCascade(float[] clip) {
        return Math.max(Math.abs(clip[0]), Math.abs(clip[1])) < 1 && clip[2] >= 0 && clip[2] <= 1;
    }

    /**
     * volumetric.comp's visibility: the near cascade where it covers the point (with the far one drawn, picked against the blend
     * weight inside the band), else the far one where it covers it, else lit. blend is ShadowMath.BLEND (shadow params z).
     */
    public static float visibility(float[] nearClip, float[] farClip, float pick, boolean nearOn, boolean farOn, float blend, ShadowMap near,
                                   float nearBias, ShadowMap far, float farBias) {
        if (nearOn && insideCascade(nearClip)) {
            float edge = Math.max(Math.abs(nearClip[0]), Math.abs(nearClip[1]));
            float t = Math.max(0, Math.min(1, (edge - (1 - blend)) / Math.max(blend, 1e-4f)));
            float weight = farOn ? t * t * (3 - 2 * t) : 0;
            if (pick >= weight) return nearClip[2] <= near.depth(nearClip[0], nearClip[1]) + nearBias ? 1 : 0;
        }
        if (farOn && insideCascade(farClip)) return farClip[2] <= far.depth(farClip[0], farClip[1]) + farBias ? 1 : 0;
        return 1;
    }

    public static float blurWeight(int i) {
        return Math.abs(i) == BLUR_RADIUS ? BLUR_EDGE : BLUR_INNER;
    }

    /** volumetric_blur.comp on a w x h single-channel image (row-major), edges clamped. */
    public static float[] blur(float[] src, int w, int h) {
        float[] dst = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int j = -BLUR_RADIUS; j <= BLUR_RADIUS; j++) {
                    int sy = Math.max(0, Math.min(h - 1, y + j));
                    for (int i = -BLUR_RADIUS; i <= BLUR_RADIUS; i++) {
                        int sx = Math.max(0, Math.min(w - 1, x + i));
                        sum += blurWeight(i) * blurWeight(j) * src[sy * w + sx];
                    }
                }
                dst[y * w + x] = Math.max(sum, 0);
            }
        }
        return dst;
    }
}
