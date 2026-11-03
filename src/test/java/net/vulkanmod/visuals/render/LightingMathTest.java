package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W5: the lighting pass's Java mirror (LightingMath) against the brief's required tests, and the mirror against the shaders. */
class LightingMathTest {
    private static final String COMP = ShaderFiles.read("post/lighting.comp");
    private static final String GLSL = ShaderFiles.read("post/lighting.glsl");

    // ---- 1. the sun-facing term ----

    @Test
    void theFacingNeverDecreasesAsASurfaceTurnsTowardTheSun() {
        for (float ground : new float[]{-0.2f, 0.05f, 0.2f, 0.5f, 1f}) {
            float last = -1;
            for (int i = 0; i <= 360; i++) {
                float nDotL = (float) Math.cos(Math.PI * (360 - i) / 360.0);//from turned away (-1) to facing (1)
                float f = LightingMath.relativeFacing(nDotL, ground, true);
                assertTrue(f >= last - 1e-7f, "ground " + ground + " step " + i);
                assertTrue(f >= 0 && f <= LightingMath.FACING_MAX, "range");
                last = f;
                for (float bpp : new float[]{0.01f, 0.3f, 2f}) {
                    float g = LightingMath.shadedFacing(nDotL, ground, bpp, true);
                    assertTrue(g >= 0 && g <= LightingMath.FACING_MAX);
                }
            }
            assertEquals(0f, LightingMath.relativeFacing(-1, ground, true), 0f, "turned away from the sun");
        }
        //Flat ground in the sun is the reference: 1 at any sun height
        for (float h : new float[]{0.15f, 0.4f, 1f}) assertEquals(1f, LightingMath.relativeFacing(h, h, true), 1e-6f);
        //Monotonic in the distance fade too, for every orientation (it mixes toward 1)
        for (float nDotL : new float[]{-1, 0, 0.3f, 1}) {
            float last = LightingMath.shadedFacing(nDotL, 1, 0, true);
            for (float bpp = 0; bpp < 2; bpp += 0.01f) {
                float g = LightingMath.shadedFacing(nDotL, 1, bpp, true);
                assertTrue(nDotL >= 1 ? g <= last + 1e-6f : g >= last - 1e-6f);
                last = g;
            }
        }
    }

    @Test
    void theFacingIsNeutralWithSunShadingOff() {
        for (float nDotL : new float[]{-1, -0.3f, 0, 0.4f, 1}) {
            assertEquals(1f, LightingMath.relativeFacing(nDotL, 0.7f, false));
            assertEquals(1f, LightingMath.shadedFacing(nDotL, 0.7f, 0.05f, false));
        }
        //The composite's factor is exactly 1 at sun weight 0, whatever it is given
        for (float direct : new float[]{0, 0.5f, 1, LightingMath.FACING_MAX}) {
            for (float sky : new float[]{0, 0.5f, 1}) assertEquals(1f, LightingMath.sunShadingAt(direct, sky, 0, 1), 0f);
        }
        //and the shader writes 1 for the facing and the visibility unless the sun is on
        assertTrue(COMP.contains("bool sunOn = sunView.w > 0.5;"));
        assertTrue(COMP.contains("float g = 1.0;"));
        assertTrue(COMP.contains("float v = 1.0;"));
        assertTrue(COMP.contains("if (sunOn) {"));
    }

    @Test
    void theSunShadingFollowsDirectSunAndSkyLight() {
        assertEquals(LightingMath.SHADE_LEVEL, LightingMath.sunShadingAt(0, 1, 1, 1), 1e-6f);
        assertEquals(LightingMath.SUN_LEVEL, LightingMath.sunShadingAt(1, 1, 1, 1), 1e-6f);
        assertEquals(LightingMath.SHADE_LEVEL, LightingMath.sunShadingAt(1, 0.2f, 1, 1), 1e-6f, "no direct sun without sky light (S8)");
        float last = 0;
        for (float d = 0; d <= LightingMath.FACING_MAX; d += 0.05f) {
            float f = LightingMath.sunShadingAt(d, 1, 1, 1);
            assertTrue(f >= last);
            last = f;
        }
        //Tuning round 1: the sun term, 0 for a low sun (S2's 10.7 degrees) .. 1 at noon, never decreasing as the sun climbs
        assertEquals(1f, LightingMath.sunHeightTerm(1), 1e-6f);
        assertEquals(0f, LightingMath.sunHeightTerm(-0.3f), 0f);
        assertEquals(0f, LightingMath.sunHeightTerm((float) Math.sin(Math.toRadians(10.7))), 0f, "S2's sunset keeps round 1's levels");
        assertEquals(0.5f, LightingMath.sunHeightTerm(0.5f), 1e-6f);
        float lastTerm = 0;
        for (float h = -1; h <= 1; h += 0.01f) {
            float t = LightingMath.sunHeightTerm(h);
            assertTrue(t >= lastTerm && t <= 1);
            lastTerm = t;
        }
    }

    /** Tuning round 1: noon sunlit terrain about x2.2 and shade about x1.3 of round 1's, a low sun and block light/night unchanged. */
    @Test
    void theLevelsFollowTheSunTerm() {
        //High sun: x2.6 sunlit (2026-10-28: sun 3.2, was x2.2; 2026-11-03: 2.88), x1.2 shade against round 1's 1.1 / 0.55
        assertEquals(2.6f, LightingMath.sunShadingAt(1, 1, 1, 1) / 1.1f, 0.1f);
        assertEquals(1.2f, LightingMath.sunShadingAt(0, 1, 1, 1) / 0.55f, 0.05f);
        assertFalse(GLSL.contains("float sunShading("), "final fix minor: the 3-argument form (noon's levels at every sun height) is gone");
        //Low sun: round 1's levels exactly
        assertEquals(1.1f, LightingMath.sunShadingAt(1, 1, 1, 0), 1e-6f);
        assertEquals(0.55f, LightingMath.sunShadingAt(0, 1, 1, 0), 1e-6f);
        //Night and block light: no sun weight, factor 1 whatever the term (the composite applies it to the sky-lit share only)
        for (float t : new float[]{0, 0.5f, 1}) assertEquals(1f, LightingMath.sunShadingAt(1, 1, 0, t), 0f);
        //More sun never darkens, at every term; the levels never fall as the sun climbs
        for (float t = 0; t <= 1; t += 0.1f) {
            float last = 0;
            for (float d = 0; d <= LightingMath.FACING_MAX; d += 0.05f) {
                float f = LightingMath.sunShadingAt(d, 1, 1, t);
                assertTrue(f >= last);
                last = f;
            }
            assertTrue(LightingMath.shadeLevel(t + 0.1f) >= LightingMath.shadeLevel(t));
            assertTrue(LightingMath.sunLevel(t + 0.1f) >= LightingMath.sunLevel(t));
        }
        assertTrue(GLSL.contains("float sunShadingAt(float direct, float skyLight, float sunWeight, float sunTerm) {"));
    }

    /** Review round 2: a face turned down, leaned half way toward up, must not normalise a zero vector (NaN). */
    @Test
    void theLeanedLookupNormalIsNeverNaN() {
        Vector3f up = new Vector3f(0.2f, 0.9f, -0.3f).normalize();
        Vector3f down = new Vector3f(up).negate();
        for (int i = 0; i <= 20; i++) {
            float t = i / 20f;
            for (Vector3f n : new Vector3f[]{down, up, EAST, new Vector3f(down).add(0, 1e-6f, 0).normalize()}) {
                Vector3f l = LightingMath.leanNormal(n, up, t);
                assertTrue(l.isFinite(), "t " + t + " n " + n);
                assertEquals(1f, l.length(), 1e-4f);
            }
        }
        assertEquals(0f, LightingMath.leanNormal(down, up, 0.5f).distance(up), 0f, "the cancelling case falls back to up");
        assertEquals(0f, LightingMath.leanNormal(EAST, up, 0).distance(EAST), 1e-6f);
        assertTrue(COMP.contains("return len > LEAN_MIN_LENGTH ? m/len : up;"));
        assertTrue(COMP.contains("sunVisibility(view, leanNormal(n, upView.xyz, cf), sf, wide)"));
        assertFalse(COMP.contains("normalize(mix(n, upView.xyz, cf))"));
    }

    // ---- 2. the distance fades (B2) ----

    @Test
    void theContrastAndShadowFadesGrowWithBlocksPerPixel() {
        float lastC = -1, lastS = -1, lastR = 0;
        for (int i = 0; i <= 4000; i++) {
            float bpp = i * 0.001f;
            float c = LightingMath.contrastFade(bpp), s = LightingMath.shadowFade(bpp), r = LightingMath.wideRadius(bpp);
            assertTrue(c >= lastC && s >= lastS && r >= lastR, "bpp " + bpp);
            assertTrue(c >= 0 && c <= 1 && s >= 0 && s <= 1);
            lastC = c;
            lastS = s;
            lastR = r;
        }
        //Near terrain (2560x1440, 70 degrees, up to 100 blocks: a block covers more than 10 pixels) keeps full contrast and sharp shadows
        float proj11 = (float) (1 / Math.tan(Math.toRadians(35)));
        for (float d : new float[]{1, 20, 64, 100}) {
            float bpp = LightingMath.blocksPerPixel(d, proj11, 1440);
            assertEquals(0f, LightingMath.contrastFade(bpp), 0f, d + " blocks");
            assertEquals(0f, LightingMath.shadowFade(bpp), 0f, d + " blocks");
        }
        //Far LODs (a block under 2 pixels) are evenly lit and their shadows fully averaged
        for (float d : new float[]{700, 2000, 8000}) {
            float bpp = LightingMath.blocksPerPixel(d, proj11, 1440);
            assertEquals(1f, LightingMath.contrastFade(bpp), 0f, d + " blocks");
            assertEquals(1f, LightingMath.shadowFade(bpp), 0f, d + " blocks");
            for (float nDotL : new float[]{-1, 0, 0.5f, 1}) assertEquals(1f, LightingMath.shadedFacing(nDotL, 0.8f, bpp, true), 1e-6f);
        }
        assertEquals(1f / 1440 * 2 / proj11 * 100, LightingMath.blocksPerPixel(100, proj11, 1440), 1e-7f);
    }

    // ---- 3. LOD faces (B1) ----

    @Test
    void theSixLodFacesMapToTheSixWorldNormalsInBoundaryOrder() {
        float[][] expected = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};//down, up, north, south, west, east
        for (int face = 0; face < 6; face++) {
            for (int light : new int[]{0, 0x5F, 0xFF}) {
                assertArrayEquals(expected[face], LightingMath.lodFaceNormal(((face + 1) << 8) | light), 0f, "face " + face);
            }
        }
        for (int none : new int[]{0, 0xFF, 7 << 8, 0xFF00}) {
            assertArrayEquals(LightingMath.NO_FACE_NORMAL, LightingMath.lodFaceNormal(none), 0f, "no face " + none);
        }
        //The shader's table: the fallback at 0, then the faces at index + 1
        Matcher m = Pattern.compile("const vec3 FACE_NORMALS\\[7\\] = vec3\\[7\\]\\(([^;]*)\\);").matcher(GLSL);
        assertTrue(m.find());
        Matcher v = Pattern.compile("vec3\\(([-0-9.]+), ([-0-9.]+), ([-0-9.]+)\\)").matcher(m.group(1));
        List<float[]> table = new ArrayList<>();
        while (v.find()) table.add(new float[]{Float.parseFloat(v.group(1)), Float.parseFloat(v.group(2)), Float.parseFloat(v.group(3))});
        assertEquals(7, table.size());
        assertArrayEquals(LightingMath.NO_FACE_NORMAL, table.get(0), 0f);
        for (int face = 0; face < 6; face++) assertArrayEquals(expected[face], table.get(face + 1), 0f);
        assertTrue(GLSL.contains("uint face = (packed >> 8u) & 0xFFu;"));
        assertTrue(GLSL.contains("return worldToView(FACE_NORMALS[face <= 6u ? face : 0u], east, up);"));
        assertTrue(GLSL.contains("return east*w.x + up*w.y + cross(east, up)*w.z;"), "south is east x up");
        //The pass uses the shared helpers (the composite calls the same ones)
        assertTrue(COMP.contains("n = lodFaceNormal(texelFetch(lodLight, lt, 0).r, eastView.xyz, upView.xyz);"));
        assertTrue(COMP.contains("n = snapNormal(depthNormal(mainDepth, invVanillaProj, p, fullSize, view), eastView.xyz, upView.xyz);"));
        assertFalse(COMP.contains("vec3 depthNormal("), "defined once, in lighting.glsl");
    }

    @Test
    void lodFaceNormalsTurnWithTheView() {
        Matrix4f modelView = new Matrix4f().rotateX(0.5f).rotateY(1.1f);
        Vector3f east = modelView.transformDirection(new Vector3f(1, 0, 0));
        Vector3f up = modelView.transformDirection(new Vector3f(0, 1, 0));
        float[][] world = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (int face = 0; face < 6; face++) {
            Vector3f want = modelView.transformDirection(new Vector3f(world[face][0], world[face][1], world[face][2]));
            Vector3f got = LightingMath.lodFaceNormalView((face + 1) << 8, east, up);
            assertEquals(0f, got.distance(want), 1e-5f, "face " + face);
        }
    }

    // ---- scenes for 4 and 6 ----

    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f, true);
    private static final Matrix4f INV_PROJ = PROJ.invert(new Matrix4f());
    private static final Matrix4f LOD_PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 8f, 30000f, true);
    private static final Matrix4f INV_LOD_PROJ = LOD_PROJ.invert(new Matrix4f());
    /** Looking 30 degrees down; the camera's world y is 64.5. */
    private static final Matrix4f MODEL_VIEW = new Matrix4f().rotateX((float) Math.toRadians(30));
    private static final Vector3f UP = MODEL_VIEW.transformDirection(new Vector3f(0, 1, 0));
    private static final Vector3f EAST = MODEL_VIEW.transformDirection(new Vector3f(1, 0, 0));
    private static final float CAMERA_Y_FRAC = 0.5f;
    /** Blocks a pixel covers per block of distance: FOV 70 at 1440 rows. */
    private static final float PIXEL_BLOCKS = LightingMath.blocksPerPixel(1, PROJ.m11(), 1440);

    /** {u, v, depth} of a camera-relative world point through proj. */
    private static float[] screen(Matrix4f proj, Vector3f world) {
        Vector3f view = MODEL_VIEW.transformPosition(new Vector3f(world));
        Vector4f clip = proj.transform(new Vector4f(view, 1));
        return new float[]{clip.x / clip.w * 0.5f + 0.5f, clip.y / clip.w * 0.5f + 0.5f, clip.z / clip.w};
    }

    /** The point where the ray through a camera-relative world point meets world height dy (relative to the camera). */
    private static Vector3f alongRay(Vector3f world, float dy) {
        return new Vector3f(world).mul(dy / world.y);
    }

    private static final Vector3f TOWARD = new Vector3f(1.3f, -1, -5.2f);

    private static final Vector3f TOWARD_UP = new Vector3f(1.3f, 0.3f, -5.2f);

    private static LightingMath.Surface vanilla(float surfaceDy, float floorDy, Vector3f normal) {
        Vector3f dir = surfaceDy < 0 ? TOWARD : TOWARD_UP;
        Vector3f surface = alongRay(dir, surfaceDy), floor = alongRay(dir, floorDy);
        float[] s = screen(PROJ, surface), f = screen(PROJ, floor);
        LightingMath.Depths d = new LightingMath.Depths(s[2], f[2], s[2], 1, 1);
        return LightingMath.classify(s[0], s[1], d, INV_PROJ, INV_LOD_PROJ, false, true, CAMERA_Y_FRAC, UP, normal, PIXEL_BLOCKS);
    }

    // ---- 4. no LOD inputs ----

    @Test
    void withoutLodDepthNoPixelIsAnLod() {
        Vector3f far = alongRay(TOWARD, -60), floor = alongRay(TOWARD, -70);
        float[] lod = screen(LOD_PROJ, far), lodFloor = screen(LOD_PROJ, floor), main = screen(PROJ, far);
        //A pixel that would be an LOD in every way the pass knows (clamped main depth, matching depth, LOD translucent)
        for (float mainDepth : new float[]{LodClamp.LOD_CLAMP_DEPTH, main[2], main[2] + 30 * LightingMath.DEPTH_STEP}) {
            LightingMath.Depths d = new LightingMath.Depths(mainDepth, mainDepth, mainDepth, lod[2], lodFloor[2]);
            LightingMath.Surface withLod = LightingMath.classify(lod[0], lod[1], d, INV_PROJ, INV_LOD_PROJ, true, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
            LightingMath.Surface without = LightingMath.classify(lod[0], lod[1], d, INV_PROJ, INV_LOD_PROJ, false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
            assertTrue(withLod.fromLod(), "depth " + mainDepth);
            assertTrue(withLod.kind() > 0);
            assertFalse(without.fromLod(), "depth " + mainDepth);
            assertEquals(0f, without.kind(), 0f);
        }
        //Every LOD read in the shader sits behind depthParams.y (viewpos.glsl's own reads do too)
        assertTrue(COMP.contains("if (!vanillaTranslucent && lodOn) {"));
        assertTrue(COMP.contains("bool lodOn = depthParams.y > 0.5;"));
        assertTrue(COMP.contains("if (depthParams.y > 0.5 && before >= depthParams.x) {"));
        assertEquals(3, count(COMP, "texelFetch(lodOpaque"), "lodOpaque reads: behindDistance, B4, the LOD kind (fromLod only)");
    }

    // ---- 6. translucent kinds ----

    @Test
    void opaqueIsZeroAndWaterIsItsLength() {
        Vector3f surface = alongRay(TOWARD, -1.5f);
        float[] s = screen(PROJ, surface);
        LightingMath.Surface opaque = LightingMath.classify(s[0], s[1], new LightingMath.Depths(s[2], s[2], s[2], 1, 1), INV_PROJ, INV_LOD_PROJ,
                false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
        assertEquals(0f, opaque.kind(), 0f);
        assertFalse(opaque.sky());
        //Water: its top at 8/9 of a block (camera at 64.5, surface at 62 + 8/9), the floor three blocks lower
        float waterDy = 62 + 8f / 9 - 64.5f;
        LightingMath.Surface water = vanilla(waterDy, waterDy - 3, UP);
        float expected = alongRay(TOWARD, waterDy - 3).length() - alongRay(TOWARD, waterDy).length();
        assertTrue(water.kind() > 0);
        assertEquals(expected, water.kind(), 0.02f * expected);
        //Nothing behind it (sky): the longest length
        float[] w = screen(PROJ, alongRay(TOWARD, waterDy));
        LightingMath.Surface open = LightingMath.classify(w[0], w[1], new LightingMath.Depths(w[2], 1, w[2], 1, 1), INV_PROJ, INV_LOD_PROJ,
                false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
        assertEquals(LightingMath.WATER_MAX_LENGTH, open.kind(), 0f);
        //Without valid copies nothing is translucent
        LightingMath.Depths d = new LightingMath.Depths(w[2], screen(PROJ, alongRay(TOWARD, waterDy - 3))[2], w[2], 1, 1);
        assertEquals(0f, LightingMath.classify(w[0], w[1], d, INV_PROJ, INV_LOD_PROJ, false, false, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS).kind(), 0f);
        //A particle drawn over the water (main depth nearer than the translucent copy) is not water
        LightingMath.Depths particle = new LightingMath.Depths(screen(PROJ, alongRay(TOWARD, -0.5f))[2], d.before(), w[2], 1, 1);
        assertEquals(0f, LightingMath.classify(w[0], w[1], particle, INV_PROJ, INV_LOD_PROJ, false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS).kind(), 0f);
        //Flowing water levels are water too
        for (int level = 1; level <= 8; level++) {
            float dy = 62 + level / 9f - 64.5f;
            assertTrue(vanilla(dy, dy - 2, UP).kind() > 0, "level " + level);
        }
    }

    @Test
    void iceAndGlassOnABlockBoundaryAreNegative() {
        //Ice: its top on y = 63 (whole), the floor two blocks lower
        LightingMath.Surface ice = vanilla(63 - 64.5f, 61 - 64.5f, UP);
        assertTrue(ice.kind() < 0, "ice " + ice.kind());
        //Seen from below (a glass ceiling at y = 66)
        LightingMath.Surface ceiling = vanilla(66 - 64.5f, 70 - 64.5f, new Vector3f(UP).negate());
        assertTrue(ceiling.kind() < 0);
        //A vertical translucent surface (a window): glass
        LightingMath.Surface window = vanilla(62.4f - 64.5f, 61 - 64.5f, EAST);
        assertTrue(window.kind() < 0);
        //A water pixel whose rebuilt normal tilted at an edge (up to 60 degrees from up) stays water
        float waterDy = 62 + 8f / 9 - 64.5f;
        Vector3f tilted = new Vector3f(UP).mul(0.55f).add(new Vector3f(EAST).mul((float) Math.sqrt(1 - 0.55 * 0.55)));
        assertTrue(vanilla(waterDy, waterDy - 3, tilted).kind() > 0);
        assertEquals(LightingMath.KIND_MIN_LENGTH, -vanilla(63 - 64.5f, 63 - 64.5f - 1e-3f, UP).kind(), 0.01f, "never 0");
    }

    /** D24: the depth rounded down to a 2^-24 step, as the main depth buffer stores it. */
    private static float d24(float depth) {
        return (float) (Math.floor(depth * 16777216.0) / 16777216.0);
    }

    @Test
    void farWaterStaysWaterThoughTheDepthIsCoarse() {
        //Water-surface dashes (CTE2 2026-10-22): a lake ~300 blocks away, the camera 197 blocks above its top (62 + 8/9). One D24 step
        // there moves the rebuilt height by about a tenth of a block, so a row whose rounding lands near the whole block above was
        // ice/glass (no water colour): rows of lighter dashes across the lake. Too coarse a depth to tell a boundary: water
        float dy = 62 + 8f / 9 - 259.5f;
        for (int k = 0; k < 200; k++) {
            Vector3f dir = new Vector3f(0.3f, -1, -1.2f - k * 0.002f);
            Vector3f surface = alongRay(dir, dy), floor = alongRay(dir, dy - 3);
            float[] s = screen(PROJ, surface), f = screen(PROJ, floor);
            LightingMath.Depths d = new LightingMath.Depths(d24(s[2]), d24(f[2]), d24(s[2]), 1, 1);
            LightingMath.Surface water = LightingMath.classify(s[0], s[1], d, INV_PROJ, INV_LOD_PROJ, false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
            assertTrue(water.kind() > 0, "row " + k + ": " + water.kind());
        }
        //Ice there is water too (the depth cannot tell it); near (iceAndGlassOnABlockBoundaryAreNegative) it stays ice
        Vector3f dir = new Vector3f(0.3f, -1, -1.2f);
        float iceDy = 63 - 259.5f;
        float[] s = screen(PROJ, alongRay(dir, iceDy)), f = screen(PROJ, alongRay(dir, iceDy - 2));
        LightingMath.Depths ice = new LightingMath.Depths(d24(s[2]), d24(f[2]), d24(s[2]), 1, 1);
        assertTrue(LightingMath.classify(s[0], s[1], ice, INV_PROJ, INV_LOD_PROJ, false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS).kind() > 0);
        //Too coarse a depth for any normal (past KIND_STRIDE_MAX pixels): water, whatever the normal says
        assertTrue(LightingMath.vanillaKind(3, new Vector3f(0, -10, -5), EAST, UP, CAMERA_Y_FRAC, 0.2f, false) > 0);
        assertTrue(LightingMath.vanillaKind(3, new Vector3f(0, -10, -5), EAST, UP, CAMERA_Y_FRAC, 0.001f, true) < 0, "resolved: a window");
    }

    @Test
    void lodTranslucentsAreWaterAlsoWhenTheDepthsDrift() {
        Vector3f surface = alongRay(TOWARD, -60), floor = alongRay(TOWARD, -64);
        float[] lod = screen(LOD_PROJ, surface), lodFloor = screen(LOD_PROJ, floor), main = screen(PROJ, surface);
        float u = lod[0], v = lod[1];
        //Past vanilla's far plane (clamped)
        LightingMath.Depths clamped = new LightingMath.Depths(LodClamp.LOD_CLAMP_DEPTH, LodClamp.LOD_CLAMP_DEPTH, LodClamp.LOD_CLAMP_DEPTH, lod[2], lodFloor[2]);
        LightingMath.Surface a = LightingMath.classify(u, v, clamped, INV_PROJ, INV_LOD_PROJ, true, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
        assertTrue(a.fromLod());
        assertEquals(floor.length() - surface.length(), a.kind(), 0.02f * (floor.length() - surface.length()));
        //Inside it, the main depth matching the LOD surface
        LightingMath.Depths matching = new LightingMath.Depths(main[2], main[2], main[2], lod[2], lodFloor[2]);
        assertTrue(LightingMath.classify(u, v, matching, INV_PROJ, INV_LOD_PROJ, true, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS).kind() > 0);
        //B4: the main depth 30 steps off the LOD surface (more than viewpos.glsl's 16) is still water, at the LOD's distance
        float drifted = main[2] + 30 * LightingMath.DEPTH_STEP;
        float stepBlocks = LightingMath.viewPosition(INV_PROJ, u, v, main[2] + 16 * LightingMath.DEPTH_STEP).length()
                - LightingMath.viewPosition(INV_PROJ, u, v, main[2]).length();
        assertTrue(stepBlocks > LightingMath.VIEWPOS_LOD_MATCH_MIN, "the scene is far enough for the step tolerance to decide");
        for (float depth : new float[]{drifted, main[2] - 30 * LightingMath.DEPTH_STEP}) {
            LightingMath.Depths d = new LightingMath.Depths(depth, depth, depth, lod[2], lodFloor[2]);
            LightingMath.Surface s = LightingMath.classify(u, v, d, INV_PROJ, INV_LOD_PROJ, true, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
            assertTrue(s.fromLod(), "depth " + depth);
            assertTrue(s.kind() > 0, "depth " + depth);
            assertEquals(surface.length(), s.distance(), 0.01f * surface.length());
        }
        //A vanilla surface well in front of the LOD water stays itself
        float[] near = screen(PROJ, alongRay(TOWARD, -40));
        LightingMath.Depths front = new LightingMath.Depths(near[2], near[2], near[2], lod[2], lodFloor[2]);
        LightingMath.Surface f = LightingMath.classify(u, v, front, INV_PROJ, INV_LOD_PROJ, true, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS);
        assertFalse(f.fromLod());
        assertEquals(0f, f.kind(), 0f);
        //An opaque LOD (no LOD translucent) is no water
        LightingMath.Depths opaque = new LightingMath.Depths(LodClamp.LOD_CLAMP_DEPTH, 1, 1, lod[2], lod[2]);
        assertEquals(0f, LightingMath.classify(u, v, opaque, INV_PROJ, INV_LOD_PROJ, true, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS).kind(), 0f);
    }

    @Test
    void viewposMirrorMatchesTheCleanHelper() {
        String viewpos = ShaderFiles.read("post/viewpos.glsl");
        assertTrue(viewpos.contains("const float LOD_MATCH_DEPTH_STEPS = " + (int) LightingMath.VIEWPOS_LOD_MATCH_DEPTH_STEPS + ".0;"));
        assertTrue(viewpos.contains("const float LOD_MATCH_MIN = " + LightingMath.VIEWPOS_LOD_MATCH_MIN + ";"));
        assertTrue(viewpos.contains("min(depth + LOD_MATCH_DEPTH_STEPS*" + "5.9604645e-8, 1.0)"));
        assertEquals(1.0 / (1 << 24), LightingMath.DEPTH_STEP, 1e-15);
    }

    // ---- 5. odd sizes ----

    @Test
    void oddSizesMapInsideAndPlantSunCoversEveryPixel() {
        int[][] sizes = {{1, 1}, {2, 3}, {3, 2}, {5, 7}, {37, 23}, {2561, 3}, {3, 1441}};
        for (int[] s : sizes) {
            int w = s[0], h = s[1], hw = LightingMath.halfSize(w), hh = LightingMath.halfSize(h);
            assertEquals((int) Math.ceil(w / 2.0), hw);
            int[] covered = new int[w * h];
            for (int qy = 0; qy < hh; qy++) {
                for (int qx = 0; qx < hw; qx++) {
                    int px = LightingMath.fullPixel(qx, w), py = LightingMath.fullPixel(qy, h);
                    assertTrue(px >= 0 && px < w && py >= 0 && py < h);
                    assertEquals(qx * 2, px, "texel q stands for pixel 2q");
                    for (int[] p : LightingMath.plantPixels(qx, qy, w, h)) covered[p[1] * w + p[0]]++;
                }
            }
            for (int i = 0; i < covered.length; i++) assertEquals(1, covered[i], w + "x" + h + " pixel " + i);
        }
        assertTrue(COMP.contains("ivec2 p = min(q*2, fullSize - 1);"));
        assertTrue(COMP.contains("ivec2 pp = q*2 + ivec2(k & 1, k >> 1);"));
        assertTrue(COMP.contains("if (any(greaterThanEqual(pp, fullSize))) {"));
    }

    // ---- 7. no shadow maps ----

    @Test
    void withoutShadowMapsTheSunIsFullyVisibleAndEveryOutputIsWritten() {
        assertEquals(1f, LightingMath.combineCascades(ShadowMath.BLEND, new float[]{-1, -1, -1}, new float[]{0, 0, 0}), 0f);
        assertEquals(1f, LightingMath.combineCascades(ShadowMath.BLEND, new float[0], new float[0]), 0f);
        //A covering cascade decides inside its band and fades into the next (or lit) across it
        assertEquals(0f, LightingMath.combineCascades(ShadowMath.BLEND, new float[]{0, 1, 1}, new float[]{0.5f, 0, 0}), 0f);
        assertEquals(0.25f, LightingMath.combineCascades(ShadowMath.BLEND, new float[]{-1, 0.25f, 1}, new float[]{0, 0.3f, 0}), 1e-6f);
        assertEquals(0.5f, LightingMath.combineCascades(ShadowMath.BLEND, new float[]{-1, 0, -1}, new float[]{0, 0.95f, 0}), 1e-6f);
        assertEquals(1f, LightingMath.combineCascades(ShadowMath.BLEND, new float[]{-1, 0, -1}, new float[]{0, 1, 0}), 1e-6f);
        //Every cascade is read only when ShadowUniforms says it is drawn; the close one only with the near one
        assertTrue(COMP.contains("if (shadowParams.x > 0.5 && closeParams.x > 0.5) {"));
        assertTrue(COMP.contains("if (rest > 0.0 && shadowParams.x > 0.5) {"));
        assertTrue(COMP.contains("if (rest > 0.0 && shadowParams.y > 0.5) {"));
        assertTrue(COMP.contains("float visible = acc + rest;") && COMP.contains("return visible;"));
        assertEquals(3, count(COMP, "= cascade("));
        //main() returns early only outside the half-resolution image, and stores each output once
        String main = COMP.substring(COMP.indexOf("void main() {"));
        assertEquals(1, count(main, "return;"));
        assertTrue(main.indexOf("return;") < main.indexOf("fullSize = textureSize(mainDepth, 0);"));
        assertEquals(1, count(main, "imageStore(lightOut, q, light);"));
        assertEquals(1, count(main, "imageStore(sunOut, q, sun);"));
        assertEquals(1, count(main, "imageStore(plantSunOut, pp, vec4(plant));"));
        assertTrue(main.contains("vec4 light = vec4(1.0, 1.0, SKY_DISTANCE, 0.0);"), "sky: no AO, facing 1, no distance, no kind");
        assertTrue(main.contains("vec4 sun = vec4(1.0, 0.0, 0.0, 0.0);"), "sky: lit");
        assertTrue(main.contains("light = vec4(1.0, g, dist, kind);"), "AO (W10's) is 1");
    }

    @Test
    void thePcfWeightsAreBilinear() {
        assertEquals(1f, LightingMath.pcf(new float[]{1, 1, 1, 1}, 0.3f, 0.8f), 0f);
        assertEquals(0.25f, LightingMath.pcf(new float[]{0, 1, 0, 0}, 0.5f, 0.5f), 1e-6f);
        assertEquals(1f, LightingMath.pcf(new float[]{0, 1, 0, 0}, 1, 0), 1e-6f);
        //textureGather's order: x (i0, j1), y (i1, j1), z (i1, j0), w (i0, j0)
        assertTrue(COMP.contains("return mix(mix(lit.w, lit.z, f.x), mix(lit.x, lit.y, f.x), f.y);"));
    }

    // ---- 8. the blur weight ----

    @Test
    void theBlurWeightIsLargestAtEqualDistancesAndFallsAsTheyDiffer() {
        int r = LightingMath.BLUR_RADIUS;
        assertTrue(r >= 1);
        for (float centre : new float[]{0.5f, 8, 120, 3000}) {
            assertEquals((r + 1) * (r + 1), LightingMath.blurWeight(0, 0, centre, centre), 1e-3f, "the centre's weight (lighting_blur.comp)");
            for (int j = -r; j <= r; j++) {
                for (int i = -r; i <= r; i++) {
                    float same = LightingMath.blurWeight(i, j, centre, centre);
                    assertTrue(same > 0);
                    float last = same;
                    for (float diff = 0.01f; diff < centre * 2; diff *= 1.5f) {
                        float nearer = LightingMath.blurWeight(i, j, centre, centre - diff), farther = LightingMath.blurWeight(i, j, centre, centre + diff);
                        assertTrue(nearer < same && farther < same, "centre " + centre + " diff " + diff);
                        assertTrue(farther <= last);
                        last = farther;
                    }
                }
            }
        }
        assertTrue(GLSL.contains("float tent = float((BLUR_RADIUS + 1 - abs(i))*(BLUR_RADIUS + 1 - abs(j)));"));
        assertTrue(GLSL.contains("return tent*exp(-abs(sampleDist - centreDist)/(BLUR_DEPTH_SHARE*abs(centreDist) + BLUR_DEPTH_MIN));"));
    }

    // ---- the marks, the constants, the uniforms ----

    @Test
    void theAlphaCodesAreFoliageMarkersMarks() {
        assertEquals(LightingMath.PLANT_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.ALPHA));
        assertEquals(LightingMath.EMISSIVE_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.EMISSIVE_ALPHA));
        //What an 8-bit attachment gives back
        assertEquals(LightingMath.PLANT_ALPHA_CODE, LightingMath.alphaCode(254f / 255f));
        assertEquals(255, LightingMath.alphaCode(1f));
        assertTrue(GLSL.contains("return int(floor(alpha*255.0 + 0.5));"));
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        int found = 0;
        for (String text : new String[]{COMP, GLSL}) {
            Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
            while (m.find()) {
                Field f = LightingMath.class.getField(m.group(2));
                double want = Double.parseDouble(m.group(3));
                assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
                found++;
            }
        }
        assertTrue(found >= 33, "constants found: " + found);
        for (Field f : LightingMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class)) continue;
            Pattern p = Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b");
            assertTrue(p.matcher(COMP).find() || p.matcher(GLSL).find(), f.getName() + " missing in the shaders");
        }
    }

    @Test
    void thePassDeclaresEveryUniformTheCleanWritersWrite() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get("postLighting");
        LooseUniformWriter w = new LooseUniformWriter(GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta());
        for (int location = 0; location <= 16; location++) assertTrue(w.has(location), "location " + location);
        ShadowUniforms.off().write(w);
        ShadowUniforms.off().writeSteps(w);
        ShadowUniforms.off().writeClose(w);
        LightingUniforms.off().writeLighting(w);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }
}
