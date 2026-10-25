package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * W5: the Java mirror of the lighting pass (post/lighting.comp) and of what the composite reads with it (post/lighting.glsl). Every
 * public float and int constant here is a {@code const} of the same name and value in one of the two shaders, and the functions follow
 * the shader code step by step: change both together. lighting.glsl's header says what each output channel means.
 */
public final class LightingMath {
    // ---- lighting.glsl: the blur, the composite's sun shading, the upsampling, the marks ----
    /** The texels lighting_blur.comp's AO blur reaches on each side (boundary 3.9). */
    public static final int BLUR_RADIUS = 2;
    /** The blur's depth tolerance: (BLUR_DEPTH_SHARE x centre distance + BLUR_DEPTH_MIN) blocks per factor e. */
    public static final float BLUR_DEPTH_SHARE = 0.05f, BLUR_DEPTH_MIN = 0.1f;
    /** The sky-lit light's factor in full shade and on sunlit flat ground with a high sun (sun term 1). Tuning round 1: 0.55, 1.1. */
    public static final float SHADE_LEVEL = 0.72f, SUN_LEVEL = 2.5f;
    /** The same with a low sun (sun term 0): round 1's levels, which the sunset (S2) matched with. */
    public static final float LOW_SUN_SHADE_LEVEL = 0.55f, LOW_SUN_SUN_LEVEL = 1.1f;
    /** The sun term (sunHeightTerm) runs from 0 at sin(elevation) SUN_TERM_LOW to 1 at SUN_TERM_HIGH. */
    public static final float SUN_TERM_LOW = 0.2f, SUN_TERM_HIGH = 0.8f;
    /** The relative facing's cap (lightOut.g). */
    public static final float FACING_MAX = 1.5f;
    /** Direct sun needs this much sky light (0..1): none below LOW, all above HIGH. */
    public static final float SKY_GATE_LOW = 0.35f, SKY_GATE_HIGH = 0.9f;
    /** lightOut.b for sky texels. */
    public static final float SKY_DISTANCE = -1.0f;
    public static final float UPSAMPLE_DEPTH_SHARE = 0.03f, UPSAMPLE_DEPTH_MIN = 0.1f, UPSAMPLE_NORMAL_POWER = 8.0f;
    /** FoliageMarker's marks in the 8-bit main colour, round(alpha x 255). */
    public static final int PLANT_ALPHA_CODE = 254, EMISSIVE_ALPHA_CODE = 253;

    // ---- lighting.comp ----
    public static final float FACING_WRAP = 0.0f;
    public static final float GROUND_FACING_MIN = 0.1f;
    /** 2026-10-21: within this of 0, N.L counts as 0 and the face is looked up (lighting.comp grazingNDotL). */
    public static final float GRAZING_NDOTL = 0.002f;
    /** B2, blocks per full-resolution pixel. */
    public static final float CONTRAST_FADE_START = 0.2f, CONTRAST_FADE_END = 0.6f;
    public static final float SHADOW_FADE_START = 0.15f, SHADOW_FADE_END = 0.5f;
    public static final float SHADOW_WIDE_BLOCKS = 3.0f, SHADOW_WIDE_PIXELS = 3.0f, SHADOW_WIDE_SPREAD = 0.7f;
    public static final int WIDE_TAPS = 5;
    public static final float RECEIVER_SLOPE_MAX = 4.0f;
    /** leanNormal: below this length the leaned normal is world up. */
    public static final float LEAN_MIN_LENGTH = 1e-4f;
    public static final float NORMAL_SNAP_MIN = 0.8f;
    public static final float HORIZONTAL_MIN = 0.5f, BOUNDARY_TOLERANCE = 0.04f;
    /**
     * 2026-10-22: a boundary only where one depth step moves the surface's world y by at most this (an eighth of the tolerance: the
     * rebuilt height errs by several steps, the D24 rounding and the float maths of the inverse projection).
     */
    public static final float BOUNDARY_STEP_MAX = 0.005f;
    /**
     * 2026-10-22: the kind's normal is rebuilt from neighbours far enough apart that one depth step is at most KIND_STEP_SHARE of their
     * spacing (lighting.comp's depthNormalStride, the caller's normal here), up to KIND_STRIDE_MAX pixels; past that: water.
     */
    public static final float KIND_STEP_SHARE = 0.1f, KIND_STRIDE_MAX = 16.0f;
    /** The depth steps the height and distance steps are measured over (one step rebuilt in float often moves nothing far away). */
    public static final float KIND_PROBE_STEPS = 16.0f;
    public static final float WATER_MAX_LENGTH = 1024.0f, KIND_MIN_LENGTH = 0.015625f;
    /** B4: the LOD water match, in main depth steps and blocks. */
    public static final float LOD_WATER_DEPTH_STEPS = 64.0f, LOD_WATER_MATCH_MIN = 1.0f;
    public static final float DEPTH_STEP = 5.9604645e-8f;

    /** Clean viewpos.glsl's LOD match (mirrored for classify; LightingMathTest checks the shader still has these values). */
    static final float VIEWPOS_LOD_MATCH_DEPTH_STEPS = 16, VIEWPOS_LOD_MATCH_MIN = 0.25f;
    private static final float FAR = 1;

    /** World normals of the LOD faces, boundary 3.1 order: down, up, north (-z), south (+z), west (-x), east (+x). */
    private static final float[][] FACES = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    /** The normal used when lodLight carries no face (index byte 0): world up. */
    public static final float[] NO_FACE_NORMAL = {0, 1, 0};

    private LightingMath() {
    }

    /**
     * Boundary 2: forwarded to the composite in slot 10 .w. Tuning round 1: the sun term t, 0 for a low sun (below SUN_TERM_LOW) .. 1 for
     * a high one (above SUN_TERM_HIGH), by which lighting.glsl's sunShadingAt moves from the low-sun to the high-sun levels.
     */
    public static float sunHeightTerm(float sunHeight) {
        return smoothstep(SUN_TERM_LOW, SUN_TERM_HIGH, sunHeight);
    }

    public static float shadeLevel(float sunTerm) {
        return mix(LOW_SUN_SHADE_LEVEL, SHADE_LEVEL, clamp01(sunTerm));
    }

    public static float sunLevel(float sunTerm) {
        return mix(LOW_SUN_SUN_LEVEL, SUN_LEVEL, clamp01(sunTerm));
    }

    static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ---- facing (lightOut.g) ----

    /** Lambert's cosine with the wrap: 0 turned away .. 1 facing the sun. */
    public static float facing(float nDotL) {
        return clamp01((nDotL + FACING_WRAP) / (1 + FACING_WRAP));
    }

    /** N.L with a grazed face's (within GRAZING_NDOTL of 0: vanilla's sun turns in the x-y plane) as exactly 0. */
    public static float grazingNDotL(float nDotL) {
        return Math.abs(nDotL) <= GRAZING_NDOTL ? 0 : nDotL;
    }

    /** Whether lighting.comp looks a face's sun visibility up: it faces the sun, or the rays run along it. */
    public static boolean looksUp(float facing, float nDotL) {
        return facing > 0 || grazingNDotL(nDotL) == 0;
    }

    /** The facing relative to flat ground's (groundNDotL = up . sun), capped; 1 with sun shading off. */
    public static float relativeFacing(float nDotL, float groundNDotL, boolean sunOn) {
        if (!sunOn) return 1;
        return Math.min(facing(nDotL) / Math.max(facing(groundNDotL), GROUND_FACING_MIN), FACING_MAX);
    }

    /** B2: 0 near (full face contrast) .. 1 far (evenly lit), by blocks per full-resolution pixel. */
    public static float contrastFade(float blocksPerPixel) {
        return smoothstep(CONTRAST_FADE_START, CONTRAST_FADE_END, blocksPerPixel);
    }

    /** B2: 0 near (sharp shadows) .. 1 far (shadows averaged over the wide filter). */
    public static float shadowFade(float blocksPerPixel) {
        return smoothstep(SHADOW_FADE_START, SHADOW_FADE_END, blocksPerPixel);
    }

    /** The wide shadow filter's radius in blocks. */
    public static float wideRadius(float blocksPerPixel) {
        return Math.max(SHADOW_WIDE_BLOCKS, SHADOW_WIDE_PIXELS * blocksPerPixel);
    }

    /** lightOut.g of an opaque, unmarked surface: the relative facing faded toward flat ground's (1) with the distance. */
    public static float shadedFacing(float nDotL, float groundNDotL, float blocksPerPixel, boolean sunOn) {
        if (!sunOn) return 1;
        return mix(relativeFacing(nDotL, groundNDotL, true), 1, contrastFade(blocksPerPixel));
    }

    /**
     * lighting.comp's leanNormal: n leaned toward up by t (the contrast fade), normalised; where the two cancel (a face turned down at
     * t = 0.5) world up itself instead of a NaN.
     */
    public static Vector3f leanNormal(Vector3fc n, Vector3fc up, float t) {
        Vector3f m = new Vector3f(n).lerp(up, t);
        float len = m.length();
        return len > LEAN_MIN_LENGTH ? m.div(len) : new Vector3f(up);
    }

    /** Blocks one full-resolution pixel covers at this distance: proj11 is the projection's [1][1], fullHeight the image height. */
    public static float blocksPerPixel(float distance, float proj11, int fullHeight) {
        return distance * 2 / (Math.max(Math.abs(proj11), 1e-6f) * fullHeight);
    }

    // ---- LOD faces (B1) ----

    /** The world normal of a lodLight texel: its high byte is the face index + 1 (boundary 3.1), 0 or out of range gives NO_FACE_NORMAL. */
    public static float[] lodFaceNormal(int packed) {
        int face = (packed >>> 8) & 0xFF;
        return (face >= 1 && face <= 6 ? FACES[face - 1] : NO_FACE_NORMAL).clone();
    }

    /** The same in view space from world east and up in view space (south is east x up, as the shader rebuilds it). */
    public static Vector3f lodFaceNormalView(int packed, Vector3fc eastView, Vector3fc upView) {
        float[] w = lodFaceNormal(packed);
        Vector3f south = new Vector3f(eastView).cross(upView);
        return new Vector3f(eastView).mul(w[0]).add(new Vector3f(upView).mul(w[1])).add(south.mul(w[2]));
    }

    // ---- sizes ----

    /** The half-resolution size the dispatcher allocates (boundary 4). */
    public static int halfSize(int full) {
        return (full + 1) / 2;
    }

    /** The full-resolution pixel a half-resolution texel stands for, on one axis. */
    public static int fullPixel(int q, int full) {
        return Math.min(q * 2, full - 1);
    }

    /** The full-resolution pixels texel (qx, qy) writes in plantSunOut, as {x, y} pairs (those inside the image). */
    public static int[][] plantPixels(int qx, int qy, int fullWidth, int fullHeight) {
        int[][] out = new int[4][];
        int n = 0;
        for (int k = 0; k < 4; k++) {
            int x = qx * 2 + (k & 1), y = qy * 2 + (k >> 1);
            if (x < fullWidth && y < fullHeight) out[n++] = new int[]{x, y};
        }
        return java.util.Arrays.copyOf(out, n);
    }

    // ---- shadows ----

    /** ShadowMath.blendWeight with the band the shader reads (shadowParams.z). */
    public static float bandWeight(float edge, float band) {
        float b = Math.max(band, 1e-4f);
        float t = clamp01((edge - (1 - b)) / b);
        return t * t * (3 - 2 * t);
    }

    /**
     * sunVisibility's combination: each cascade's visibility (negative: not drawn or not covering the point) and edge, finest first
     * (close, near, far); each fades into the next across its band, what none covers is lit.
     */
    public static float combineCascades(float band, float[] visibility, float[] edge) {
        float acc = 0, rest = 1;
        for (int i = 0; i < visibility.length && rest > 0; i++) {
            if (visibility[i] < 0) continue;
            float w = 1 - bandWeight(edge[i], band);
            acc += rest * w * visibility[i];
            rest *= 1 - w;
        }
        return acc + rest;
    }

    /** The shader's bilinear percentage-closer weights: lit texels (i0j0, i1j0, i0j1, i1j1) mixed by the fraction (fx, fy). */
    public static float pcf(float[] lit, float fx, float fy) {
        return mix(mix(lit[0], lit[1], fx), mix(lit[2], lit[3], fx), fy);
    }

    // ---- the blur and the composite ----

    /** lighting.glsl's blurWeight. */
    public static float blurWeight(int i, int j, float centreDist, float sampleDist) {
        float tent = (BLUR_RADIUS + 1 - Math.abs(i)) * (BLUR_RADIUS + 1 - Math.abs(j));
        return tent * (float) Math.exp(-Math.abs(sampleDist - centreDist) / (BLUR_DEPTH_SHARE * Math.abs(centreDist) + BLUR_DEPTH_MIN));
    }

    public static int alphaCode(float alpha) {
        return (int) Math.floor(alpha * 255 + 0.5f);
    }

    public static float skyGate(float skyLight) {
        return smoothstep(SKY_GATE_LOW, SKY_GATE_HIGH, skyLight);
    }

    /** lighting.glsl's sunShadingAt: the factor on a pixel's sky-lit light, direct = lightOut.g x sunOut.r (or plantSun), sunTerm slot 10 .w. */
    public static float sunShadingAt(float direct, float skyLight, float sunWeight, float sunTerm) {
        float shade = shadeLevel(sunTerm);
        float lit = shade + (sunLevel(sunTerm) - shade) * direct * skyGate(skyLight);
        return mix(1, lit, clamp01(sunWeight));
    }

    // ---- translucent kinds (B4, B5) ----

    /** One full-resolution pixel's depths: main, vanilla's before/after its translucents, the LOD depth and the LOD before its translucents. */
    public record Depths(float main, float before, float after, float lod, float lodOpaque) {
    }

    /** What the pass makes of a pixel: sky, whether it shows an LOD, its view distance and lightOut.a. */
    public record Surface(boolean sky, boolean fromLod, float distance, float kind) {
    }

    /** viewpos.glsl's viewPosition (zero-to-one depth): GL screen uv and depth through an inverse projection. */
    public static Vector3f viewPosition(Matrix4fc invProj, float u, float v, float depth) {
        Vector4f p = invProj.transform(new Vector4f(u * 2 - 1, v * 2 - 1, depth, 1));
        return new Vector3f(p.x / p.w, p.y / p.w, p.z / p.w);
    }

    /**
     * The pass's classification of one pixel at GL uv (u, v): viewpos.glsl's scenePositionFrom, then the translucent tests of main()
     * (vanilla copies, the B4 LOD water match, LOD translucents as water). normalView is the normal the pass would use (rebuilt and
     * snapped for vanilla pixels). LOD inputs are read only with lodValid, the copies only with copiesValid.
     */
    public static Surface classify(float u, float v, Depths d, Matrix4fc invVanilla, Matrix4fc invLod, boolean lodValid, boolean copiesValid,
                                   float cameraYFrac, Vector3fc upView, Vector3fc normalView, float pixelBlocks) {
        if (d.main() == FAR) return new Surface(true, false, SKY_DISTANCE, 0);
        Vector3f view = viewPosition(invVanilla, u, v, d.main());
        boolean fromLod = false;
        if (d.main() >= LodClamp.CLAMP_THRESHOLD && lodValid) {
            if (d.lod() != FAR) {
                view = viewPosition(invLod, u, v, d.lod());
                fromLod = true;
            }
        } else if (lodValid && d.lod() != FAR) {
            Vector3f l = viewPosition(invLod, u, v, d.lod());
            float dv = view.length();
            float step = Math.abs(viewPosition(invVanilla, u, v, Math.min(d.main() + VIEWPOS_LOD_MATCH_DEPTH_STEPS * DEPTH_STEP, 1)).length() - dv);
            if (Math.abs(l.length() - dv) <= Math.max(VIEWPOS_LOD_MATCH_MIN, step)) {
                view = l;
                fromLod = true;
            }
        }
        boolean vanillaTranslucent = false;
        float vanillaLength = 0;
        float vanillaHeightStep = 0;
        float vanillaStride = 1;
        if (!fromLod) {
            if (copiesValid && d.after() < d.before() && d.main() >= d.after()) {
                vanillaTranslucent = true;
                float behind;
                if (lodValid && d.before() >= LodClamp.CLAMP_THRESHOLD) {
                    behind = d.lodOpaque() == FAR ? -1 : viewPosition(invLod, u, v, d.lodOpaque()).length();
                } else {
                    behind = d.before() == FAR ? -1 : viewPosition(invVanilla, u, v, d.before()).length();
                }
                Vector3f surface = viewPosition(invVanilla, u, v, d.after());
                vanillaLength = kindLength(behind, surface.length());
                float probe = Math.min(d.after() + KIND_PROBE_STEPS * DEPTH_STEP, 1);
                float steps = Math.max((probe - d.after()) / DEPTH_STEP, 1);
                Vector3f next = viewPosition(invVanilla, u, v, probe);
                vanillaHeightStep = Math.abs(new Vector3f(next).sub(surface).dot(upView)) / steps;
                vanillaStride = Math.abs(next.length() - surface.length()) / steps / (KIND_STEP_SHARE * Math.max(surface.length() * pixelBlocks, 1e-6f));
            }
            if (!vanillaTranslucent && lodValid && d.lod() != FAR && d.lod() < d.lodOpaque()) {
                Vector3f l = viewPosition(invLod, u, v, d.lod());
                float dv = view.length();
                float steps = Math.abs(viewPosition(invVanilla, u, v, Math.min(d.main() + LOD_WATER_DEPTH_STEPS * DEPTH_STEP, 1)).length() - dv);
                if (Math.abs(l.length() - dv) <= Math.max(LOD_WATER_MATCH_MIN, steps)) {
                    view = l;
                    fromLod = true;
                }
            }
        }
        float kind = 0;
        if (fromLod) {
            if (d.lod() < d.lodOpaque()) {
                kind = kindLength(d.lodOpaque() == FAR ? -1 : viewPosition(invLod, u, v, d.lodOpaque()).length(), view.length());
            }
        } else if (vanillaTranslucent) {
            kind = vanillaKind(vanillaLength, view, normalView, upView, cameraYFrac, vanillaHeightStep, vanillaStride <= KIND_STRIDE_MAX);
        }
        return new Surface(false, fromLod, view.length(), kind);
    }

    static float kindLength(float behind, float surface) {
        return behind < 0 ? WATER_MAX_LENGTH : Math.max(KIND_MIN_LENGTH, Math.min(WATER_MAX_LENGTH, behind - surface));
    }

    /**
     * B5: horizontal on a whole world y, or vertical: ice/glass (negative); other horizontal: water. heightStep is how far one depth step
     * moves the surface's world y: coarser than BOUNDARY_STEP_MAX the depth cannot tell a boundary, so a horizontal surface is water.
     * resolved false: the depth is too coarse to rebuild a normal: water.
     */
    static float vanillaKind(float length, Vector3fc view, Vector3fc normal, Vector3fc upView, float cameraYFrac, float heightStep, boolean resolved) {
        if (!resolved) return length;
        if (Math.abs(normal.dot(upView)) < HORIZONTAL_MIN) return -length;
        float y = cameraYFrac + view.dot(upView);
        return heightStep <= BOUNDARY_STEP_MAX && Math.abs(y - (float) Math.floor(y + 0.5f)) <= BOUNDARY_TOLERANCE ? -length : length;
    }
}
