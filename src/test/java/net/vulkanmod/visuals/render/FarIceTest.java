package net.vulkanmod.visuals.render;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import net.vulkanmod.visuals.dh.DhVertexFormat;
import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.lod.LodTarget;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16_UINT;

/**
 * Far ice stays ice on every path that draws translucent terrain (session 17, 2026-11-13).
 * <p>
 * The user: "ice is disappearing in the outer, Distant Horizons part; it seems to be a recurring bug". eca8547 (2026-11-09) made the
 * vanilla terrain mark its water, so lighting.comp stopped calling far vanilla ice water. The LOD branch was left as it had been since
 * the first version: "every LOD translucent is water". So DH's frozen lakes, rivers and seas past the render distance took the water's
 * colour, ripples and mirror. It looked like the same bug coming back, at the render distance's edge instead of ~70 blocks out.
 * <p>
 * The fix: DH's vertex carries its block material, and terrain.vert sets LOD_WATER_BIT in the LOD light attachment for DH's WATER.
 * lighting.comp then calls an LOD translucent water only where that bit is set. This test follows the chain from DH's material id to
 * the pass's kind, for both paths. It also guards the pass's source: every place that gives a translucent kind must go through a mark.
 */
class FarIceTest {
    private static final String COMP = ShaderFiles.read("post/lighting.comp");
    private static final String GLSL = ShaderFiles.read("post/lighting.glsl");
    private static final String VERT = ShaderFiles.read("dh/terrain.vert");
    private static final String FRAG = ShaderFiles.read("dh/terrain.frag");

    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f, true);
    private static final Matrix4f INV_PROJ = PROJ.invert(new Matrix4f());
    private static final Matrix4f LOD_PROJ = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 8f, 30000f, true);
    private static final Matrix4f INV_LOD_PROJ = LOD_PROJ.invert(new Matrix4f());
    /** From a hill, looking 20 degrees down toward the horizon; the camera's world y is 64.5. */
    private static final Matrix4f MODEL_VIEW = new Matrix4f().rotateX((float) Math.toRadians(20));
    private static final Vector3f UP = MODEL_VIEW.transformDirection(new Vector3f(0, 1, 0));
    private static final float CAMERA_Y_FRAC = 0.5f;
    private static final float PIXEL_BLOCKS = LightingMath.blocksPerPixel(1, PROJ.m11(), 1440);
    /** Sky 15, block 0: the meta word's low byte. */
    private static final int META = 0x0F;
    private static final int UP_FACE = 1;

    private static float[] screen(Matrix4f proj, Vector3f world) {
        Vector3f view = MODEL_VIEW.transformPosition(new Vector3f(world));
        Vector4f clip = proj.transform(new Vector4f(view, 1));
        return new float[]{clip.x / clip.w * 0.5f + 0.5f, clip.y / clip.w * 0.5f + 0.5f, clip.z / clip.w};
    }

    /** The point where the ray through a camera-relative world point meets world height dy (relative to the camera). */
    private static Vector3f alongRay(Vector3f world, float dy) {
        return new Vector3f(world).mul(dy / world.y);
    }

    /** What DH's terrain.vert writes for an up face of this material, in a translucent LOD quad. */
    private static int attachment(EDhApiBlockMaterial material) {
        return DhVertexFormat.lightAttachment(META, UP_FACE, material.index);
    }

    /** An LOD translucent surface (frozen or open water's top) at height surfaceDy above its floor, seen distance blocks out. */
    private static LightingMath.Surface lod(float distance, boolean clamped, int lodLight) {
        Vector3f dir = new Vector3f(0.2f, -1, -1).normalize().mul(distance);
        float surfaceDy = dir.y;
        Vector3f surface = alongRay(dir, surfaceDy), floor = alongRay(dir, surfaceDy - 4);
        float[] l = screen(LOD_PROJ, surface), lf = screen(LOD_PROJ, floor);
        float main = clamped ? LodClamp.LOD_CLAMP_DEPTH : screen(PROJ, surface)[2];
        LightingMath.Depths d = new LightingMath.Depths(main, main, main, l[2], lf[2]);
        return LightingMath.classify(l[0], l[1], d, INV_PROJ, INV_LOD_PROJ, true, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS, false, 0, lodLight);
    }

    @Test
    void onlyDhWaterIsMarkedAsWater() {
        for (EDhApiBlockMaterial m : EDhApiBlockMaterial.values()) {
            int a = attachment(m);
            assertEquals(m == EDhApiBlockMaterial.WATER, LightingMath.lodWater(a), m.name());
            //The light and the face read as before
            assertEquals(META, a & 0xFF, m.name());
            assertArrayEquals(new float[]{0, 1, 0}, LightingMath.lodFaceNormal(a), 0f, m.name());
        }
        assertEquals(EDhApiBlockMaterial.WATER.index, DhVertexFormat.MATERIAL_WATER);
        assertEquals(LightingMath.LOD_WATER_BIT, DhVertexFormat.LIGHT_WATER_BIT);
        //Above the light byte and the face (index + 1, at most 6: 3 bits), inside the attachment's 16 bits
        assertEquals(0, LightingMath.LOD_WATER_BIT & 0x7FF);
        assertTrue(LightingMath.LOD_WATER_BIT < 1 << 16);
        assertEquals(VK_FORMAT_R16_UINT, LodTarget.LIGHT_FORMAT);
        //Every face still decodes with the water bit set
        for (int face = 0; face < DhVertexFormat.FACES; face++) {
            int dry = DhVertexFormat.lightAttachment(META, face, EDhApiBlockMaterial.STONE.index);
            int wet = DhVertexFormat.lightAttachment(META, face, EDhApiBlockMaterial.WATER.index);
            assertEquals(dry | LightingMath.LOD_WATER_BIT, wet);
            assertArrayEquals(LightingMath.lodFaceNormal(dry), LightingMath.lodFaceNormal(wet), 0f, "face " + face);
        }
    }

    @Test
    void farLodIceIsIceAndFarLodWaterIsWater() {
        //What DH gives ice, packed ice and blue ice (glass sound: METAL in DH 3.3.5, else UNKNOWN or STONE), stained glass and slime:
        // every material but WATER
        for (float distance : new float[]{150, 300, 600, 1200, 3000}) {
            for (boolean clamped : new boolean[]{true, false}) {
                if (!clamped && distance > 600) continue;//inside vanilla's far plane only near
                String at = distance + " blocks" + (clamped ? ", past vanilla's far plane" : ", the main depth matching the LOD");
                LightingMath.Surface water = lod(distance, clamped, attachment(EDhApiBlockMaterial.WATER));
                assertTrue(water.fromLod(), at);
                assertTrue(water.kind() > 0, "water: " + at);
                for (EDhApiBlockMaterial m : EDhApiBlockMaterial.values()) {
                    if (m == EDhApiBlockMaterial.WATER) continue;
                    LightingMath.Surface s = lod(distance, clamped, attachment(m));
                    assertTrue(s.fromLod(), at);
                    assertTrue(s.kind() < 0, m.name() + " is not water: " + at);
                    assertEquals(-water.kind(), s.kind(), 1e-3f * water.kind(), "the same length, " + at);
                }
            }
        }
    }

    @Test
    void farVanillaIceStaysIceWithTheWaterMarks() {
        //eca8547's path, which this session leaves as it is: looking down from 200 blocks up, far past BOUNDARY_STEP_MAX
        Vector3f dir = new Vector3f(0.3f, -1, -1.2f);
        float iceDy = 63 - 264.5f;
        float[] s = screen(PROJ, alongRay(dir, iceDy)), f = screen(PROJ, alongRay(dir, iceDy - 3));
        float[] under = screen(PROJ, alongRay(dir, iceDy - 1 - 1f / 9));
        LightingMath.Depths d = new LightingMath.Depths(s[2], f[2], s[2], 1, 1);
        assertTrue(LightingMath.classify(s[0], s[1], d, INV_PROJ, INV_LOD_PROJ, false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS, true,
                TerrainLightMath.packWaterMark(under[2])).kind() < 0, "ice over marked water");
        float[] w = screen(PROJ, alongRay(dir, iceDy - 1f / 9)), wf = screen(PROJ, alongRay(dir, iceDy - 3));
        LightingMath.Depths wd = new LightingMath.Depths(w[2], wf[2], w[2], 1, 1);
        assertTrue(LightingMath.classify(w[0], w[1], wd, INV_PROJ, INV_LOD_PROJ, false, true, CAMERA_Y_FRAC, UP, UP, PIXEL_BLOCKS, true,
                TerrainLightMath.packWaterMark(w[2])).kind() > 0, "marked water");
    }

    @Test
    void theShadersCarryTheMaterialToThePass() {
        //DH's terrain.vert: the material byte decides the bit; terrain.frag writes the attachment unchanged
        assertTrue(VERT.contains("const uint DH_WATER_MATERIAL = " + DhVertexFormat.MATERIAL_WATER + "u;"));
        assertTrue(VERT.contains("const uint LOD_WATER_BIT = " + LightingMath.LOD_WATER_BIT + "u;"));
        assertTrue(VERT.contains("vertexLight = lights | ((vFace.y < 6u ? vFace.y + 1u : 0u) << 8u) | (vFace.x == DH_WATER_MATERIAL ? LOD_WATER_BIT : 0u);"));
        assertTrue(FRAG.contains("fragLight = vertexLight;"));
        //lighting.glsl: the bit, and the face read from its own three bits
        assertTrue(GLSL.contains("const uint LOD_WATER_BIT = " + LightingMath.LOD_WATER_BIT + "u;"));
        assertTrue(GLSL.contains("return (packed & LOD_WATER_BIT) != 0u;"));
        assertTrue(GLSL.contains("uint face = (packed >> 8u) & 7u;"));
        //lighting.comp's LOD branch asks the bit
        assertTrue(COMP.contains("kind = lodWater(lodPacked) ? lodLength : -lodLength;"));
        assertFalse(COMP.contains("every LOD translucent is water"));
    }

    /**
     * The guard against the next path: lighting.comp may give a pixel a translucent kind in exactly two places, the vanilla one (whose
     * vanillaKind lets the water marks decide) and the LOD one (whose sign is the LOD water bit). A third assignment, or one of these
     * two without its mark, fails here and must say how it tells ice from water.
     */
    @Test
    void everyTranslucentKindInThePassGoesThroughAMark() {
        Matcher m = Pattern.compile("(?m)^\\s*kind = ([^;]*);").matcher(COMP);
        List<String> found = new ArrayList<>();
        while (m.find()) found.add(m.group(1));
        assertEquals(List.of("lodWater(lodPacked) ? lodLength : -lodLength",
                "vanillaKind(vanillaLength, view, kindNormal, vanillaHeightStep, resolved, vanillaMark)"), found);
        assertTrue(COMP.contains("if (waterMark >= 0) {\n        return waterMark > 0 ? len : -len;"), "vanillaKind: the mark first");
    }
}
