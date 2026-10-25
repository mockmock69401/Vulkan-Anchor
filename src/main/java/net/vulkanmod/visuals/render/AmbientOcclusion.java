package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * W10: the Java mirror of the ambient occlusion (post/ao.glsl, ao_depth.comp, ao.comp, ao_blur.comp) and the uniforms its passes
 * take. Every shader constant has a field of the same name here (AmbientOcclusionTest compares them both ways); the functions follow
 * the shaders step by step. Zero-to-one depth (the programs' USE_ZERO_ONE_DEPTH).
 */
public final class AmbientOcclusion {
    public static final float AO_RADIUS = 4.0f;
    public static final float AO_RADIUS_GROWTH = 0.01f;
    public static final int AO_SLICES = 2;
    public static final int AO_STEPS = 6;
    public static final float AO_MAX_RADIUS_TEXELS = 64.0f;
    public static final float AO_MIN_RADIUS_TEXELS = 1.0f;
    public static final float AO_FULL_RADIUS_TEXELS = 2.0f;
    public static final float AO_FALLOFF_START = 0.6f;
    public static final float AO_DEPTH_BIAS = 0.002f;
    public static final float AO_POWER = 1.5f;
    public static final int AO_PATTERN = 3;
    public static final int AO_JITTER_STRIDE = 4;
    public static final int AO_BLUR_RADIUS = 1;
    public static final float AO_BLUR_DEPTH_SHARE = 0.03f;
    public static final float AO_BLUR_DEPTH_MIN = 0.05f;
    public static final float AO_BLUR_NORMAL_POWER = 8.0f;
    public static final float AO_UPSAMPLE_DEPTH_SHARE = 0.03f;
    public static final float AO_UPSAMPLE_DEPTH_MIN = 0.1f;
    public static final float AO_TAKE_NEIGHBOURS = -1.0f;
    public static final float AO_NONE = -2.0f;
    public static final float AO_RAY_NEAR = 0.5f, AO_RAY_FAR = 0.9f;
    public static final float AO_MIN_DISTANCE = 0.001f;
    public static final float AO_PI = 3.14159265f;
    public static final float AO_LIGHT_MIN = 0.00001f;

    // ---- uniform locations (Java only) ----
    /** ao_depth.comp: viewpos.glsl's inverse projections and depthParams, then the size vec4. */
    public static final int DEPTH_INV_VANILLA_PROJ = 0, DEPTH_INV_LOD_PROJ = 1, DEPTH_PARAMS = 2, DEPTH_SIZE = 3;
    /** ao.comp: the view rays' inverse projection, then the size vec4. */
    public static final int OCCLUSION_INV_VANILLA_PROJ = 0, OCCLUSION_SIZE = 1;

    private AmbientOcclusion() {
    }

    // ---- when it runs ----

    /** The passes run only after the lighting pass and with a strength above 0 (slot 10 .x); otherwise the image is untouched. */
    public static boolean runs(LightingUniforms.Values lighting) {
        return lighting != null && lighting.on() && lighting.aoStrength() > 0;
    }

    // ---- sizes ----

    /** The half-resolution size for a full one (boundary 4: ceil(full / 2), the lighting pass's grid). */
    public static int halfSize(int full) {
        return (full + 1) / 2;
    }

    /** Workgroups of 8 covering a size. */
    public static int groups(int texels) {
        return (texels + 7) / 8;
    }

    /** The half-resolution texels a block spans at view distance 1: the projection's y scale times the full height / 4. */
    public static float texelsPerBlock(Matrix4fc projection, int fullHeight) {
        return Math.abs(projection.m11()) * fullHeight * 0.25f;
    }

    // ---- uniforms ----

    public static void writeDepth(LooseUniformWriter w, LightingUniforms.Values lighting, int fullWidth, int fullHeight) {
        w.putMat4(DEPTH_INV_VANILLA_PROJ, lighting.invVanillaProjection()).putMat4(DEPTH_INV_LOD_PROJ, lighting.invLodProjection());
        w.putVec4(DEPTH_PARAMS, LodClamp.CLAMP_THRESHOLD, lighting.lodValid() ? 1 : 0, 0, 0);
        w.putVec4(DEPTH_SIZE, fullWidth, fullHeight, texelsPerBlock(lighting.projection(), fullHeight), 0);
    }

    public static void writeOcclusion(LooseUniformWriter w, LightingUniforms.Values lighting, int fullWidth, int fullHeight) {
        w.putMat4(OCCLUSION_INV_VANILLA_PROJ, lighting.invVanillaProjection());
        w.putVec4(OCCLUSION_SIZE, fullWidth, fullHeight, texelsPerBlock(lighting.projection(), fullHeight), 0);
    }

    // ---- ao.glsl ----

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** aoRay: the unit view ray through half-resolution texel position (tx, ty). */
    public static Vector3f ray(Matrix4fc invProj, float tx, float ty, float fullWidth, float fullHeight) {
        float u = (2 * tx + 0.5f) / fullWidth, v = 1 - (2 * ty + 0.5f) / fullHeight;
        Vector4f n = invProj.transform(new Vector4f(u * 2 - 1, v * 2 - 1, AO_RAY_NEAR, 1));
        Vector4f f = invProj.transform(new Vector4f(u * 2 - 1, v * 2 - 1, AO_RAY_FAR, 1));
        return new Vector3f(f.x / f.w - n.x / n.w, f.y / f.w - n.y / n.w, f.z / f.w - n.z / n.w).normalize();
    }

    public static float worldRadius(float dist) {
        return AO_RADIUS + AO_RADIUS_GROWTH * dist;
    }

    public static float radiusTexels(float radius, float dist, float texelsPerBlock) {
        return radius * texelsPerBlock / Math.max(dist, AO_MIN_DISTANCE);
    }

    public static int patternIndex(int qx, int qy) {
        return (qx % AO_PATTERN) + AO_PATTERN * (qy % AO_PATTERN);
    }

    public static float rotation(int k) {
        return (k + 0.5f) / (AO_PATTERN * AO_PATTERN);
    }

    public static float jitter(int k) {
        return ((k * AO_JITTER_STRIDE) % (AO_PATTERN * AO_PATTERN) + 0.5f) / (AO_PATTERN * AO_PATTERN);
    }

    public static float stepTexels(int i, float jitter, float rTex) {
        float s = (i + jitter) / AO_STEPS;
        return 1 + s * s * Math.max(rTex - 1, 0);
    }

    public static float falloff(float len, float r) {
        return clamp((r - len) / Math.max(r * (1 - AO_FALLOFF_START), AO_MIN_DISTANCE), 0, 1);
    }

    public static float arc(float h, float n) {
        return (float) (0.25 * (-Math.cos(2 * h - n) + Math.cos(n) + 2 * h * Math.sin(n)));
    }

    public static float strengthApplied(float visibility, float strength) {
        return mix(1, clamp(visibility, 0, 1), clamp(strength, 0, 1));
    }

    /** aoFloorShare per channel. */
    public static float[] floorShare(float[] skyOnly, float[] blockOnly, float[] dark) {
        float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            float total = dark[c] + Math.max(skyOnly[c] - dark[c], 0) + Math.max(blockOnly[c] - dark[c], 0);
            out[c] = total > AO_LIGHT_MIN ? dark[c] / Math.max(total, AO_LIGHT_MIN) : 0;
        }
        return out;
    }

    /** aoOccludedLight per channel. */
    public static float[] occludedLight(float[] perLight, float[] occludable, float occlusion) {
        float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            float left = perLight[c] - (1 - clamp(occlusion, 0, 1)) * clamp(occludable[c], 0, perLight[c]);
            out[c] = perLight[c] > AO_LIGHT_MIN ? clamp(left / Math.max(perLight[c], AO_LIGHT_MIN), 0, 1) : 1;
        }
        return out;
    }

    /** One texel of aoOut: r the visibility, g the view distance (0: sky). */
    public interface Texels {
        int width();

        int height();

        float[] at(int qx, int qy);
    }

    /** aoAt: aoOut at full-resolution pixel (px, py) for a pixel at view distance dist. */
    public static float at(Texels ao, int px, int py, float dist) {
        int q0x = px >> 1, q0y = py >> 1, oddX = px & 1, oddY = py & 1;
        float sum = 0, weights = 0, closest = 1e30f, fallback = 1;
        for (int j = 0; j <= oddY; j++) {
            for (int i = 0; i <= oddX; i++) {
                float[] t = ao.at(Math.min(q0x + i, ao.width() - 1), Math.min(q0y + j, ao.height() - 1));
                if (!(t[1] > 0)) continue;
                float miss = Math.abs(t[1] - dist);
                float w = (float) Math.exp(-miss / (AO_UPSAMPLE_DEPTH_SHARE * dist + AO_UPSAMPLE_DEPTH_MIN));
                sum += w * t[0];
                weights += w;
                if (miss < closest) {
                    closest = miss;
                    fallback = t[0];
                }
            }
        }
        return weights > 1e-6f ? sum / weights : fallback;
    }

    // ---- the passes ----

    /** What the passes read at half resolution: aoDepth, and W5's normal (sunOut.gba) and translucent kind (lightOut.a). */
    public interface Scene {
        int width();

        int height();

        /** aoDepth: the view distance, 0 sky, negative for plant and light-source texels. */
        float depth(int qx, int qy);

        /** W5's view-space normal (0 for sky). */
        Vector3f normal(int qx, int qy);

        /** W5's translucent kind. */
        float kind(int qx, int qy);
    }

    /**
     * ao_depth.comp for one texel: the view distance of a surface (null: sky), negated for one that hides nothing (a plant or
     * light-source pixel, or W5's translucent kind non-zero).
     */
    public static float depth(Vector3f view, boolean noOccluder) {
        if (view == null) return 0;
        float d = Math.max(view.length(), AO_MIN_DISTANCE);
        return noOccluder ? -d : d;
    }

    /** ao.comp's main() for texel (qx, qy); fullWidth/fullHeight and texelsPerBlock are its size vec4. */
    public static float occlusion(Scene scene, Matrix4fc invProj, float fullWidth, float fullHeight, float texelsPerBlock, int qx, int qy) {
        float d = scene.depth(qx, qy);
        if (d == 0 || scene.kind(qx, qy) != 0) return AO_NONE;
        if (d < 0) return AO_TAKE_NEIGHBOURS;
        Vector3f n = new Vector3f(scene.normal(qx, qy));
        if (!(n.lengthSquared() > 0.25f)) return AO_NONE;
        n.normalize();
        Vector3f ray = ray(invProj, qx, qy, fullWidth, fullHeight);
        Vector3f centre = new Vector3f(ray).mul(d);
        Vector3f toEye = new Vector3f(ray).negate();
        float rTex = radiusTexels(worldRadius(d), d, texelsPerBlock);
        float fade = smoothstep(AO_MIN_RADIUS_TEXELS, AO_FULL_RADIUS_TEXELS, rTex);
        if (fade <= 0) return 1;
        rTex = Math.min(rTex, AO_MAX_RADIUS_TEXELS);
        float radius = rTex * d / texelsPerBlock;
        int k = patternIndex(qx, qy);
        float rotation = rotation(k), jitter = jitter(k);
        float visible = 0, open = 0;
        for (int s = 0; s < AO_SLICES; s++) {
            float phi = (s + rotation) * AO_PI / AO_SLICES;
            float dx = (float) Math.cos(phi), dy = (float) Math.sin(phi);
            Vector3f next = ray(invProj, qx + dx, qy + dy, fullWidth, fullHeight);
            Vector3f side = new Vector3f(next).sub(new Vector3f(ray).mul(next.dot(ray)));
            if (!(side.lengthSquared() > 1e-12f)) continue;
            side.normalize();
            Vector3f axis = new Vector3f(side).cross(toEye).normalize();
            Vector3f projected = new Vector3f(n).sub(new Vector3f(axis).mul(n.dot(axis)));
            float projectedLength = projected.length();
            if (!(projectedLength > 1e-4f)) continue;
            float cosN = clamp(projected.dot(toEye) / projectedLength, -1, 1);
            float angleN = clamp((projected.dot(side) < 0 ? -1 : 1) * (float) Math.acos(cosN), -0.5f * AO_PI, 0.5f * AO_PI);
            float[] rise = {0, 0};
            for (int h = 0; h < 2; h++) {
                float way = h == 0 ? -1 : 1;
                for (int i = 0; i < AO_STEPS; i++) {
                    float t = stepTexels(i, jitter, rTex);
                    int tx = (int) Math.floor(qx + 0.5f + way * dx * t), ty = (int) Math.floor(qy + 0.5f + way * dy * t);
                    if (tx < 0 || ty < 0 || tx >= scene.width() || ty >= scene.height()) continue;
                    float ds = scene.depth(tx, ty);
                    if (!(ds > 0)) continue;
                    Vector3f delta = ray(invProj, tx, ty, fullWidth, fullHeight).mul(ds).sub(centre);
                    float len = delta.length();
                    if (!(len > AO_MIN_DISTANCE)) continue;
                    rise[h] = Math.max(rise[h], Math.max(delta.dot(n) - AO_DEPTH_BIAS * d, 0) / len * falloff(len, radius));
                }
            }
            float h0 = Math.min(angleN - 0.5f * AO_PI + (float) Math.asin(clamp(rise[0] / projectedLength, 0, 1)), 0);
            float h1 = Math.max(angleN + 0.5f * AO_PI - (float) Math.asin(clamp(rise[1] / projectedLength, 0, 1)), 0);
            visible += projectedLength * (arc(h0, angleN) + arc(h1, angleN));
            open += projectedLength * (float) (Math.cos(angleN) + angleN * Math.sin(angleN));
        }
        float ao = open > 1e-4f ? clamp(visible / open, 0, 1) : 1;
        return mix(1, (float) Math.pow(ao, AO_POWER), fade);
    }

    /** ao_blur.comp's main() for texel (qx, qy) over aoRaw (raw[qy][qx]): {visibility, view distance}. */
    public static float[] blur(Scene scene, float[][] raw, int qx, int qy) {
        float centre = raw[qy][qx];
        float dist = Math.abs(scene.depth(qx, qy));
        if (centre < 0.5f * (AO_TAKE_NEIGHBOURS + AO_NONE)) return new float[]{1, dist};
        boolean own = centre >= 0;
        Vector3f n = own ? new Vector3f(scene.normal(qx, qy)).normalize() : new Vector3f();
        float sum = 0, weights = 0;
        for (int j = -AO_BLUR_RADIUS; j <= AO_BLUR_RADIUS; j++) {
            for (int i = -AO_BLUR_RADIUS; i <= AO_BLUR_RADIUS; i++) {
                int tx = qx + i, ty = qy + j;
                if (tx < 0 || ty < 0 || tx >= scene.width() || ty >= scene.height()) continue;
                float v = raw[ty][tx];
                if (v < 0) continue;
                float ds = Math.abs(scene.depth(tx, ty));
                float w = (float) Math.exp(-Math.abs(ds - dist) / (AO_BLUR_DEPTH_SHARE * dist + AO_BLUR_DEPTH_MIN));
                if (own) w *= (float) Math.pow(Math.max(n.dot(new Vector3f(scene.normal(tx, ty)).normalize()), 0), AO_BLUR_NORMAL_POWER);
                sum += v * w;
                weights += w;
            }
        }
        return new float[]{weights > 1e-6f ? sum / weights : (own ? centre : 1), dist};
    }
}
