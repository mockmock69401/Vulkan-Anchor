package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * W9: the temporal anti-aliasing in Java, the mirror of post_taa.comp (every constant here has the shader constant of the same
 * name and value; TaaTest compares them) plus the per-frame uniforms VkTaa writes.
 *
 * <p>Method (literature, no code taken): Karis 2014, "High Quality Temporal Supersampling" (SIGGRAPH, Advances in Real-Time
 * Rendering): jittered projection, reprojection by depth with the closest depth of the 3x3 neighbourhood, clipping the history
 * toward the neighbourhood's box in YCoCg; Salvi 2016, "An Excursion in Temporal Supersampling" (GDC): the variance box (mean
 * plus/minus gamma standard deviations); Jimenez 2016, "Filmic SMAA" (SIGGRAPH): the Catmull-Rom history fetch from five
 * bilinear taps; Malvar and Sullivan 2003 (JVT-I014): the YCoCg transform.
 *
 * <p>Reprojection: the history holds the image at unjittered pixel centres, so both frames' projections are used without their
 * jitter. A pixel's depth gives its view position through this frame's unjittered inverse projection (the vanilla one, or the LOD
 * one where viewpos.glsl says the LOD depth placed it); prevFromView takes a view position (w = 1) or a sky direction (w = 0) to
 * the previous frame's clip space: P_prev * MV_prev * T(camera_now - camera_prev) * MV_now^-1.
 */
public final class TaaMath {
    /** The jitter sequence length: TaaJitter repeats every JITTER_PHASES frames. */
    public static final int JITTER_PHASES = 8;
    /** The width of the jitter pattern in pixels (1: the offsets span one whole pixel). */
    public static final float JITTER_SCALE = 1f;
    /** The current frame's share of the result while the image stands still (the rest is history). */
    public static final float CURRENT_WEIGHT = 0.1f;
    /** The current frame's share once the image moves MOTION_RAMP_PIXELS pixels a frame or more (resampled history is softer). */
    public static final float MOVING_CURRENT_WEIGHT = 0.2f;
    public static final float MOTION_RAMP_PIXELS = 4f;
    /** At least this current share on translucent surfaces (water): their reflections move unlike the surface's depth. */
    public static final float TRANSLUCENT_CURRENT_WEIGHT = 0.3f;
    /** The neighbourhood box's half size in standard deviations, for near opaque pixels and the sky. */
    public static final float CLIP_GAMMA = 1f;
    /** For LOD pixels and anything past vanilla's render distance: wider, so sub-pixel far detail is not rejected into crawling. */
    public static final float FAR_CLIP_GAMMA = 1.5f;
    /** For translucent surfaces: tighter, against reflection trails. */
    public static final float TRANSLUCENT_CLIP_GAMMA = 0.75f;
    /** The history is dropped where every history depth around the fetch is nearer than (1 - this) times the expected depth. */
    public static final float DISOCCLUSION_DEPTH = 0.1f;
    /** The depth stored in the history's alpha for the sky (rgba16f holds up to 65504). */
    public static final float SKY_DEPTH = 60000f;
    /** The smallest half size of the clip box (an 8-bit flat area still has room for the dither). */
    public static final float BOX_EPS = 0.001f;

    /** Uniform locations of post_taa.comp. */
    public static final int INV_VANILLA_PROJ = 0, INV_LOD_PROJ = 1, PREV_FROM_VIEW = 2, DEPTH_PARAMS = 3, TAA_PARAMS = 4;

    private TaaMath() {
    }

    /** A frame's camera as the next frame needs it: the unjittered projection (0..1 depth), the model view, the position. */
    public record Camera(Matrix4f projection, Matrix4f modelView, double x, double y, double z) {
    }

    /**
     * The frame's uniforms: unjittered inverse projections, prevFromView, depthParams (boundary 3: x the LOD clamp threshold, y 1 when
     * the LOD depth is valid) and taaParams (x 1 when the history is valid, y vanilla's render distance in blocks).
     */
    public record Values(Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f prevFromView, boolean lodValid,
                         boolean historyValid, float vanillaDistance) {
        public void write(LooseUniformWriter w) {
            w.putMat4(INV_VANILLA_PROJ, this.invVanillaProjection).putMat4(INV_LOD_PROJ, this.invLodProjection).putMat4(PREV_FROM_VIEW, this.prevFromView);
            w.putVec4(DEPTH_PARAMS, LodClamp.CLAMP_THRESHOLD, this.lodValid ? 1 : 0, 0, 0);
            w.putVec4(TAA_PARAMS, this.historyValid ? 1 : 0, this.vanillaDistance, 0, 0);
        }
    }

    /**
     * The inverse of the unjittered projection from the inverse of the jittered one: P_j = J P with J the NDC translation
     * (jx, jy), so P^-1 = P_j^-1 J.
     */
    public static Matrix4f unjitterInverse(Matrix4fc invJittered, float jx, float jy) {
        return new Matrix4f(invJittered).mul(new Matrix4f().translation(jx, jy, 0));
    }

    /** This frame's camera for the next frame, given the NDC jitter the level was drawn with. */
    public static Camera camera(VkTaa.Frame f, float[] jitterNdc) {
        Matrix4f proj = unjitterInverse(f.invVanillaProjection(), jitterNdc[0], jitterNdc[1]).invert();
        return new Camera(proj, new Matrix4f(f.invModelView()).invert(), f.cameraX(), f.cameraY(), f.cameraZ());
    }

    /** View position now (w 1) or direction (w 0) to the previous frame's clip space. */
    public static Matrix4f prevFromView(Camera prev, VkTaa.Frame now) {
        return targetFromView(prev, now.invModelView(), now.cameraX(), now.cameraY(), now.cameraZ());
    }

    /**
     * A view position (w 1) or direction (w 0) of the camera at (nowX, nowY, nowZ) with inverse model view invModelViewNow to the
     * target camera's clip space: P_t * MV_t * T(now - target) * MV_now^-1.
     */
    public static Matrix4f targetFromView(Camera target, Matrix4fc invModelViewNow, double nowX, double nowY, double nowZ) {
        float dx = (float) (nowX - target.x()), dy = (float) (nowY - target.y()), dz = (float) (nowZ - target.z());
        return new Matrix4f(target.projection()).mul(target.modelView()).translate(dx, dy, dz).mul(invModelViewNow);
    }

    /** The unjittered projection from the jittered one: P_j = J P with J the NDC translation (jx, jy), so P = J^-1 P_j. */
    public static Matrix4f unjitter(Matrix4fc jitteredProjection, float jx, float jy) {
        return new Matrix4f().translation(-jx, -jy, 0).mul(jitteredProjection);
    }

    /** This frame's uniforms; prev null (or historyValid false) means no history: the matrix then reprojects onto itself. */
    public static Values compute(VkTaa.Frame now, float[] jitterNdc, Camera prev, boolean historyValid) {
        Camera self = camera(now, jitterNdc);
        boolean valid = historyValid && prev != null;
        return new Values(unjitterInverse(now.invVanillaProjection(), jitterNdc[0], jitterNdc[1]),
                unjitterInverse(now.invLodProjection(), jitterNdc[0], jitterNdc[1]), prevFromView(valid ? prev : self, now), now.lodValid(),
                valid, now.vanillaDistanceBlocks());
    }

    // ---- the shader's per-pixel steps ----

    /**
     * post_taa.comp reproject(): a screen position (x, y in pixels from the top-left corner, pixel k's centre at k + 0.5) with its
     * depth (0..1) and the unjittered inverse projection, or a sky direction (sky true, depth ignored: the far plane's point with
     * w 0), to {x, y, w}: its position in the previous frame in the same pixel units and its previous clip w; null behind the camera.
     */
    public static float[] reproject(Matrix4fc invProj, Matrix4fc prevFromView, float x, float y, float depth, int width, int height, boolean sky) {
        float u = x / width, v = 1f - y / height;
        Vector4f h = invProj.transform(new Vector4f(u * 2f - 1f, v * 2f - 1f, sky ? 1f : depth, 1f));
        Vector4f clip = prevFromView.transform(new Vector4f(h.x / h.w, h.y / h.w, h.z / h.w, sky ? 0f : 1f));
        if (clip.w <= 0f) return null;
        float pu = clip.x / clip.w * 0.5f + 0.5f, pv = clip.y / clip.w * 0.5f + 0.5f;
        return new float[]{pu * width, (1f - pv) * height, clip.w};
    }

    static float[] toYCoCg(float[] c) {
        return new float[]{0.25f * c[0] + 0.5f * c[1] + 0.25f * c[2], 0.5f * c[0] - 0.5f * c[2], -0.25f * c[0] + 0.5f * c[1] - 0.25f * c[2]};
    }

    static float[] fromYCoCg(float[] c) {
        return new float[]{c[0] + c[1] - c[2], c[0] + c[2], c[0] - c[1] - c[2]};
    }

    /** The current frame's share for a motion in pixels and the pixel's class. */
    public static float currentWeight(float motionPixels, boolean translucent) {
        float t = Math.max(0f, Math.min(1f, motionPixels / MOTION_RAMP_PIXELS));
        float w = CURRENT_WEIGHT + (MOVING_CURRENT_WEIGHT - CURRENT_WEIGHT) * t;
        return translucent ? Math.max(w, TRANSLUCENT_CURRENT_WEIGHT) : w;
    }

    public static float clipGamma(boolean translucent, boolean far) {
        return translucent ? TRANSLUCENT_CLIP_GAMMA : far ? FAR_CLIP_GAMMA : CLIP_GAMMA;
    }

    /**
     * Whether the history at the fetch shows something else: every stored depth around it is clearly nearer than expected. Depths
     * past SKY_DEPTH (the sky, the farthest LODs) are stored as SKY_DEPTH and compared as such.
     */
    public static boolean disoccluded(float storedMaxDepth, float expectedDepth) {
        return storedMaxDepth < Math.min(expectedDepth, SKY_DEPTH) * (1f - DISOCCLUSION_DEPTH);
    }

    /** The history (YCoCg) clipped toward the centre of the box [lo, hi] (Karis 2014). */
    static float[] clip(float[] history, float[] lo, float[] hi) {
        float[] centre = new float[3], v = new float[3];
        float units = 0;
        for (int i = 0; i < 3; i++) {
            centre[i] = 0.5f * (lo[i] + hi[i]);
            float ext = Math.max(0.5f * (hi[i] - lo[i]), BOX_EPS);
            v[i] = history[i] - centre[i];
            units = Math.max(units, Math.abs(v[i]) / ext);
        }
        if (units <= 1f) return history.clone();
        return new float[]{centre[0] + v[0] / units, centre[1] + v[1] / units, centre[2] + v[2] / units};
    }

    /**
     * post_taa.comp's resolve for one pixel: the 3x3 current neighbourhood (display RGB, centre at index 4) and the fetched history
     * (RGB; null or not historyValid: none). Without usable history the result is the current pixel itself, unblended.
     */
    public static float[] resolve(float[][] neighbourhood, float[] history, boolean historyValid, float storedMaxDepth, float expectedDepth,
                                  float motionPixels, boolean translucent, boolean far) {
        float[] current = neighbourhood[4];
        if (!historyValid || history == null || disoccluded(storedMaxDepth, expectedDepth)
                || Float.isNaN(history[0] + history[1] + history[2])) {
            return current.clone();
        }
        float[] m1 = new float[3], m2 = new float[3], mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] c : neighbourhood) {
            float[] y = toYCoCg(c);
            for (int i = 0; i < 3; i++) {
                m1[i] += y[i];
                m2[i] += y[i] * y[i];
                mn[i] = Math.min(mn[i], y[i]);
                mx[i] = Math.max(mx[i], y[i]);
            }
        }
        float gamma = clipGamma(translucent, far);
        float[] lo = new float[3], hi = new float[3];
        for (int i = 0; i < 3; i++) {
            float mean = m1[i] / 9f;
            float sigma = (float) Math.sqrt(Math.max(m2[i] / 9f - mean * mean, 0f));
            lo[i] = Math.max(mean - gamma * sigma, mn[i]);
            hi[i] = Math.min(mean + gamma * sigma, mx[i]);
        }
        float[] h = clip(toYCoCg(history), lo, hi);
        float[] c = toYCoCg(current);
        float w = currentWeight(motionPixels, translucent);
        float[] out = fromYCoCg(new float[]{h[0] + (c[0] - h[0]) * w, h[1] + (c[1] - h[1]) * w, h[2] + (c[2] - h[2]) * w});
        for (int i = 0; i < 3; i++) out[i] = Math.max(0f, Math.min(1f, out[i]));
        return out;
    }
}
