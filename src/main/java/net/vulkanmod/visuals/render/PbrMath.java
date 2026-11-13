package net.vulkanmod.visuals.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 2026-11-10 (shader roadmap session 9, PBR): the Java mirror of the terrain's material packing (vanilla shader include
 * pbr_terrain.glsl) and of the composite's and the reflection's PBR maths (visuals/post/pbr.glsl), with the atlas helpers PbrAtlas uses.
 * Every constant named as in the shaders equals its shader value (PbrMathTest reads them): change both together.
 * <p>
 * Formats: LabPBR 1.3 (shaderLABS wiki, "LabPBR Material Standard"). The decoding of its channels and the metals' tint follow Glimmer's
 * material.glsl (Copyright (c) 2024 Josh Britain, MIT License; THIRD_PARTY_NOTICES.md); wet materials after Lagarde 2013 and Jensen,
 * Legakis and Dorsey 1999 (pbr.glsl).
 */
public final class PbrMath {
    // ---- pbr_terrain.glsl (and pbr.glsl's copies) ----
    public static final float PBR_NORMAL_STEPS = 2047.0f;
    public static final float PBR_AO_STEPS = 7.0f;
    public static final int PBR_NORMAL_WRITTEN = 33554432;
    public static final int PBR_TAG_SHIFT = 26;
    public static final int PBR_TAG_MASK = 63;
    public static final float PBR_POROSITY_STEPS = 15.0f;
    public static final float PBR_EMISSION_STEPS = 31.0f;
    public static final float PBR_SNAP_MIN = 0.98f;
    public static final int PBR_PARALLAX_STEPS = 24;
    public static final int PBR_PARALLAX_REFINE = 5;
    public static final float PBR_PARALLAX_FADE_START = 12.0f;
    public static final float PBR_PARALLAX_FADE_END = 20.0f;
    public static final float PBR_PARALLAX_MIN_COS = 0.08f;
    // ---- pbr.glsl ----
    public static final float PBR_F0_MAX = 0.898039f;
    public static final int PBR_METAL_FIRST = 230;
    public static final int PBR_METAL_ALBEDO = 255;
    public static final float PBR_ALPHA_MIN = 0.03f;
    public static final float PBR_FACING_RATIO_MAX = 2.5f;
    public static final float PBR_FACING_MIN = 0.05f;
    public static final float PBR_SSS_SHARE = 0.45f;
    public static final float PBR_AO_STRENGTH = 1.0f;
    public static final float PBR_WET_SKY_LOW = 13.5f;
    public static final float PBR_WET_SKY_HIGH = 14.75f;
    public static final float PBR_WET_FILM_SMOOTHNESS = 0.88f;
    public static final float PBR_WET_FILM_F0 = 0.02f;
    public static final float PBR_WET_DARKEN = 0.55f;
    public static final float PBR_WET_POROUS_SMOOTHNESS = 0.4f;
    public static final float PBR_WET_SIDE_FILM = 0.35f;
    public static final float PBR_WET_SATURATION = 0.25f;
    public static final float PBR_METAL_DIFFUSE = 0.35f;
    public static final float PBR_REFLECT_SMOOTH_MIN = 0.45f;
    public static final float PBR_REFLECT_SMOOTH_FULL = 0.75f;
    public static final float PBR_RAY_SPREAD = 1.0f;
    public static final float PBR_REFLECT_MIN_MARCH = 0.08f;
    public static final float PBR_EMISSION_GAIN = 1.0f;
    public static final float PBR_MARK_BASE = 8.0f;
    public static final float PBR_MARK_LIMIT = -7.5f;
    public static final float PBR_SMOOTH_CODES = 15.0f;
    public static final float PBR_MARK_FILM = 1024.0f;
    public static final float PBR_FILM_FLAT = 0.5f;
    public static final int PBR_F0_CODES = 22;
    public static final int PBR_CODE_METAL_FIRST = 23;
    public static final int PBR_CODE_METAL_ALBEDO = 31;
    /** Session 16: an albedo metal's mark carries its F0's brightest channel in this many sqrt steps (codes 31..38). */
    public static final int PBR_ALBEDO_F0_STEPS = 7;
    /** LabPBR's predefined metals 230..237 (iron, gold, aluminium, chrome, copper, lead, platinum, silver): F0 from LabPBR's n and k. */
    public static final float[][] PBR_METAL_F0 = {{0.5312f, 0.5124f, 0.4958f}, {0.9442f, 0.7761f, 0.3734f}, {0.9123f, 0.9139f, 0.9197f},
            {0.5556f, 0.5545f, 0.5548f}, {0.9260f, 0.7209f, 0.5042f}, {0.6325f, 0.6259f, 0.6415f}, {0.6788f, 0.6424f, 0.5884f},
            {0.9620f, 0.9495f, 0.9221f}};
    /** LabPBR's table (n, k per channel) the F0 above come from, for the test. */
    public static final float[][][] LABPBR_METAL_NK = {
            {{2.9114f, 2.9497f, 2.5845f}, {3.0893f, 2.9318f, 2.7670f}}, {{0.18299f, 0.42108f, 1.3734f}, {3.4242f, 2.3459f, 1.7704f}},
            {{1.3456f, 0.96521f, 0.61722f}, {7.4746f, 6.3995f, 5.3031f}}, {{3.1071f, 3.1812f, 2.3230f}, {3.3314f, 3.3291f, 3.1350f}},
            {{0.27105f, 0.67693f, 1.3164f}, {3.6092f, 2.6248f, 2.2921f}}, {{1.9100f, 1.8300f, 1.4400f}, {3.5100f, 3.4000f, 3.1800f}},
            {{2.3757f, 2.0847f, 1.8453f}, {4.2655f, 3.7153f, 3.1365f}}, {{0.15943f, 0.14512f, 0.13547f}, {3.9291f, 3.1900f, 2.3808f}}};
    // ---- the settings ----
    /** The parallax depth the terrain gets with the parallax on (1 = LabPBR's quarter of a texture for a height of 0). */
    public static final float PARALLAX_DEPTH = 1.0f;
    public static final int NORMAL_STRENGTH_MAX = 200;
    /** The wetness eases in over this many seconds of rain and dries over the other. */
    public static final float WET_RISE_SECONDS = 8f, WET_DRY_SECONDS = 40f;
    // ---- blockreflect.glsl's highlight (BlockReflectionMath) ----
    static final float HIGHLIGHT_SCALE = 0.3f, HIGHLIGHT_TERMINATOR = 0.1f, NDOTV_MIN = 0.35f, HIGHLIGHT_MAX = 3.0f;

    /** A decoded material (pbr.glsl's struct PbrMaterial). */
    public record Material(float[] normal, float ao, float smoothness, float f0, int metal, float porosity, float sss, float emission,
                           boolean specular) {
        Material with(float smoothness, float f0) {
            return new Material(this.normal, this.ao, smoothness, f0, this.metal, this.porosity, this.sss, this.emission, this.specular);
        }
    }

    private PbrMath() {
    }

    public static float normalStrength(int percent) {
        return Math.max(0, Math.min(NORMAL_STRENGTH_MAX, percent)) / 100f;
    }

    // ---- packing ----

    /** pbrOctEncode: a unit vector to the octahedron's square (-1..1). */
    public static float[] octEncode(float x, float y, float z) {
        float s = Math.abs(x) + Math.abs(y) + Math.abs(z);
        x /= s;
        y /= s;
        z /= s;
        if (z < 0) {
            float ox = (1 - Math.abs(y)) * (x >= 0 ? 1 : -1);
            float oy = (1 - Math.abs(x)) * (y >= 0 ? 1 : -1);
            return new float[]{ox, oy};
        }
        return new float[]{x, y};
    }

    /** pbrOctDecode. */
    public static float[] octDecode(float ex, float ey) {
        float x = ex, y = ey, z = 1 - Math.abs(ex) - Math.abs(ey);
        if (z < 0) {
            x = (1 - Math.abs(ey)) * (ex >= 0 ? 1 : -1);
            y = (1 - Math.abs(ex)) * (ey >= 0 ? 1 : -1);
        }
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / len, y / len, z / len};
    }

    private static int tag(int light) {
        return ((light >>> 12) & PBR_TAG_MASK) << PBR_TAG_SHIFT;
    }

    /** packTerrainNormal: n a unit world normal, ao 0..1, light the fragment's TerrainLightMath.pack value. */
    public static int packNormal(float nx, float ny, float nz, float ao, int light) {
        float[] o = octEncode(nx, ny, nz);
        int qx = Math.round(clamp01(o[0] * 0.5f + 0.5f) * PBR_NORMAL_STEPS);
        int qy = Math.round(clamp01(o[1] * 0.5f + 0.5f) * PBR_NORMAL_STEPS);
        int a = Math.round(clamp01(ao) * PBR_AO_STEPS);
        return tag(light) | PBR_NORMAL_WRITTEN | (a << 22) | (qy << 11) | qx;
    }

    /** packTerrainSpecular: the specular texel's bytes (r smoothness, g F0, b porosity / scattering, a emission). */
    public static int packSpecular(int r, int g, int b, int a, int light) {
        int scatter = b <= 64 ? Math.round(b / 64f * PBR_POROSITY_STEPS) : 16 + Math.round((b - 65) / 190f * PBR_POROSITY_STEPS);
        int emission = a >= 255 ? 0 : Math.round(a / 254f * PBR_EMISSION_STEPS);
        return tag(light) | (emission << 21) | (scatter << 16) | (g << 8) | r;
    }

    /** pbrMaterial: the material, or null when either value is not the light's fragment's (or the normal is not written). */
    public static Material material(int light, int n, int s) {
        if ((n & PBR_NORMAL_WRITTEN) == 0 || (n & (PBR_TAG_MASK << PBR_TAG_SHIFT)) != tag(light) || (s & (PBR_TAG_MASK << PBR_TAG_SHIFT)) != tag(light)) {
            return null;
        }
        float[] normal = octDecode((n & 2047) / PBR_NORMAL_STEPS * 2 - 1, ((n >>> 11) & 2047) / PBR_NORMAL_STEPS * 2 - 1);
        float ao = ((n >>> 22) & 7) / PBR_AO_STEPS;
        int r = s & 255, g = (s >>> 8) & 255, b = (s >>> 16) & 31, a = (s >>> 21) & 31;
        int metal = -1;
        float f0 = 0;
        if (g >= PBR_METAL_FIRST) metal = g - PBR_METAL_FIRST < 8 ? g - PBR_METAL_FIRST : 8;
        else f0 = g / 255f;
        float porosity = b < 16 ? b / PBR_POROSITY_STEPS : 0;
        float sss = b < 16 ? 0 : (b - 16) / PBR_POROSITY_STEPS;
        return new Material(normal, ao, r / 255f, f0, metal, porosity, sss, a / PBR_EMISSION_STEPS, (s & 67108863) != 0);
    }

    // ---- lighting ----

    public static float wetAt(float wetness, float skyLevel) {
        return clamp01(wetness) * smoothstep(PBR_WET_SKY_LOW, PBR_WET_SKY_HIGH, skyLevel);
    }

    /** pbrWet: the material with the rain on it, the diffuse factor (index 1) and the film's share (index 2); face the face's normal. */
    public static Object[] wet(Material m, float wet, float[] face) {
        if (!(wet > 0) || !m.specular()) return new Object[]{m, 1f, 0f};
        float film = wet * (1 - m.porosity()) * mix(PBR_WET_SIDE_FILM, 1, clamp01(face[1]));
        float[] n = m.normal();
        float[] flat = normalize(new float[]{mix(n[0], face[0], film), mix(n[1], face[1], film), mix(n[2], face[2], film)});
        float soak = wet * m.porosity();
        float s = mix(m.smoothness(), Math.max(m.smoothness(), PBR_WET_FILM_SMOOTHNESS), film);
        s = mix(s, Math.max(s, PBR_WET_POROUS_SMOOTHNESS), soak);
        float f0 = m.metal() < 0 ? mix(m.f0(), Math.max(m.f0(), PBR_WET_FILM_F0), Math.max(film, soak)) : m.f0();
        Material w = new Material(flat, m.ao(), s, f0, m.metal(), m.porosity(), m.sss(), m.emission(), m.specular());
        return new Object[]{w, 1 - PBR_WET_DARKEN * soak, film};
    }

    /** pbrWetColour. */
    public static float[] wetColour(float[] c, float darken) {
        float soak = (1 - darken) / PBR_WET_DARKEN;
        float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = Math.max(mix(l, c[i], 1 + PBR_WET_SATURATION * soak), 0) * darken;
        return out;
    }

    public static float alpha(float smoothness) {
        float r = 1 - clamp01(smoothness);
        return Math.max(r * r, PBR_ALPHA_MIN);
    }

    /** pbrF0: albedo is the texture's colour (display values); an albedo metal's F0 is it in linear units (session 16). */
    public static float[] f0(Material m, float[] albedo) {
        if (m.metal() < 0) return new float[]{m.f0(), m.f0(), m.f0()};
        return m.metal() < 8 ? PBR_METAL_F0[m.metal()].clone()
                : new float[]{PostMath.displayToLinear(clamp01(albedo[0])), PostMath.displayToLinear(clamp01(albedo[1])), PostMath.displayToLinear(clamp01(albedo[2]))};
    }

    public static float fresnel(float f0, float cosTheta) {
        double k = Math.pow(1 - clamp01(cosTheta), 5);
        return (float) (f0 + (1 - f0) * k);
    }

    public static float facingRatio(float[] nm, float[] nb, float[] l) {
        float face = dot(nb, l);
        if (!(face > 0)) return 1;
        return Math.min(clamp01(dot(nm, l)) / Math.max(face, PBR_FACING_MIN), PBR_FACING_RATIO_MAX);
    }

    public static float scatter(float direct, float sss, float visibility) {
        return Math.max(direct, PBR_SSS_SHARE * clamp01(sss) * clamp01(visibility));
    }

    /** pbrHighlight for one channel (f0 and light of that channel). */
    public static float highlight(Material m, float f0, float[] v, float[] l, float light) {
        float[] n = m.normal();
        float nl = dot(n, l), nv = dot(n, v);
        if (!(nl > 0) || !(nv > 0)) return 0;
        float[] h = normalize(new float[]{l[0] + v[0], l[1] + v[1], l[2] + v[2]});
        float d = BlockReflectionMath.ggxDistribution(Math.max(dot(n, h), 0), alpha(m.smoothness()));
        float f = fresnel(f0, dot(v, h));
        float s = Math.max(light, 0) * f * (d * HIGHLIGHT_SCALE * smoothstep(0, HIGHLIGHT_TERMINATOR, nl) / Math.max(nv, NDOTV_MIN));
        return Math.min(s, HIGHLIGHT_MAX);
    }

    public static boolean reflects(Material m) {
        return m.smoothness() >= PBR_REFLECT_SMOOTH_MIN;
    }

    public static int f0Code(Material m, float[] albedo) {
        if (m.metal() >= 0) {
            if (m.metal() < 8) return PBR_CODE_METAL_FIRST + m.metal();
            float b = PostMath.displayToLinear(clamp01(Math.max(albedo[0], Math.max(albedo[1], albedo[2]))));
            return PBR_CODE_METAL_ALBEDO + Math.round((float) Math.sqrt(b) * PBR_ALBEDO_F0_STEPS);
        }
        return Math.round((float) Math.sqrt(clamp01(m.f0() / PBR_F0_MAX)) * PBR_F0_CODES);
    }

    public static float mark(Material m, float film, float[] albedo) {
        return -(PBR_MARK_BASE + Math.round(clamp01(m.smoothness()) * PBR_SMOOTH_CODES) + 16f * f0Code(m, albedo) + (film >= PBR_FILM_FLAT ? PBR_MARK_FILM : 0));
    }

    public static boolean isMark(float mark) {
        return mark < PBR_MARK_LIMIT;
    }

    /** pbrUnmark: {smoothness, code, 1 under a film}. */
    public static float[] unmark(float mark) {
        float m = Math.max(-mark - PBR_MARK_BASE, 0);
        boolean film = m >= PBR_MARK_FILM - 0.5f;
        if (film) m -= PBR_MARK_FILM;
        int code = (int) Math.floor(m / 16f + 0.001f);
        return new float[]{clamp01((m - 16f * code) / PBR_SMOOTH_CODES), code, film ? 1 : 0};
    }

    public static float[] codeF0(int code, float[] tint) {
        if (code >= PBR_CODE_METAL_ALBEDO) {
            float q = Math.min(code - PBR_CODE_METAL_ALBEDO, PBR_ALBEDO_F0_STEPS) / (float) PBR_ALBEDO_F0_STEPS;
            return new float[]{tint[0] * q * q, tint[1] * q * q, tint[2] * q * q};
        }
        if (code >= PBR_CODE_METAL_FIRST) return PBR_METAL_F0[code - PBR_CODE_METAL_FIRST].clone();
        float q = code / (float) PBR_F0_CODES;
        float f = q * q * PBR_F0_MAX;
        return new float[]{f, f, f};
    }

    /** pbrFresnelRough (session 16): Schlick's term rising toward grazing only up to the smoothness (Lagarde 2011). */
    public static float fresnelRough(float f0, float cosTheta, float smoothness) {
        double k = Math.pow(1 - clamp01(cosTheta), 5);
        return (float) (f0 + (Math.max(clamp01(smoothness), f0) - f0) * k);
    }

    public static float reflectivity(float smoothness, float f0, float cosTheta) {
        return fresnelRough(f0, cosTheta, smoothness) * smoothstep(PBR_REFLECT_SMOOTH_MIN, PBR_REFLECT_SMOOTH_FULL, smoothness);
    }

    // ---- the frame's wetness ----

    /** The wetness after seconds toward rain (0..1): up at 1/WET_RISE_SECONDS a second, down at 1/WET_DRY_SECONDS. */
    public static float easeWetness(float wetness, float rain, float seconds) {
        float target = clamp01(rain);
        if (target > wetness) return Math.min(target, wetness + seconds / WET_RISE_SECONDS);
        return Math.max(target, wetness - seconds / WET_DRY_SECONDS);
    }

    // ---- the atlas ----

    /** One mip level down: each 2x2 block's channel means (RGBA bytes), into a new buffer the caller frees. */
    public static ByteBuffer downsample(ByteBuffer src, int w, int h) {
        int nw = Math.max(1, w >> 1), nh = Math.max(1, h >> 1);
        ByteBuffer dst = MemoryUtil.memAlloc(nw * nh * 4);
        for (int y = 0; y < nh; y++) {
            int y0 = Math.min(2 * y, h - 1), y1 = Math.min(2 * y + 1, h - 1);
            for (int x = 0; x < nw; x++) {
                int x0 = Math.min(2 * x, w - 1), x1 = Math.min(2 * x + 1, w - 1);
                for (int c = 0; c < 4; c++) {
                    int sum = (src.get((y0 * w + x0) * 4 + c) & 255) + (src.get((y0 * w + x1) * 4 + c) & 255)
                            + (src.get((y1 * w + x0) * 4 + c) & 255) + (src.get((y1 * w + x1) * 4 + c) & 255);
                    dst.put((y * nw + x) * 4 + c, (byte) ((sum + 2) >> 2));
                }
            }
        }
        return dst;
    }

    /**
     * The parallax lookup: the cell (the largest power of two, at least cellMin, every sprite's place and size are multiples of), and per
     * cell its place in its sprite and the sprite's size in cells as little-endian RGBA bytes: {cells across, cells down, cell values...}.
     * Null when there is no such cell, the lookup would exceed cellsMax on a side, or a sprite spans more than 255 cells.
     */
    public static int[] spriteCells(List<PbrAtlas.Sprite> sprites, int atlasW, int atlasH, int cellMin, int cellsMax) {
        int g = 0;
        for (PbrAtlas.Sprite s : sprites) {
            g = gcd(g, s.x());
            g = gcd(g, s.y());
            g = gcd(g, s.width());
            g = gcd(g, s.height());
        }
        g = gcd(g, atlasW);
        g = gcd(g, atlasH);
        int cell = Integer.highestOneBit(Math.max(g, 1));
        if (cell < cellMin) return null;
        int cw = atlasW / cell, ch = atlasH / cell;
        if (cw > cellsMax || ch > cellsMax) return null;
        int[] out = new int[2 + cw * ch];
        out[0] = cw;
        out[1] = ch;
        for (PbrAtlas.Sprite s : sprites) {
            int sw = s.width() / cell, sh = s.height() / cell;
            if (sw > 255 || sh > 255) return null;
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int cx = s.x() / cell + x, cy = s.y() / cell + y;
                    if (cx < cw && cy < ch) out[2 + cy * cw + cx] = x | (y << 8) | (sw << 16) | (sh << 24);
                }
            }
        }
        return out;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    // ---- helpers ----

    static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float[] normalize(float[] a) {
        float l = (float) Math.sqrt(dot(a, a));
        return new float[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
