package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-23: the terrain light's value, which terrain_light.glsl writes and lightvolume.glsl reads (TerrainLightMath mirrors both). */
class TerrainLightMathTest {
    private static final String PACK = core("include/terrain_light.glsl");
    private static final String READ = ShaderFiles.read("post/lightvolume.glsl");
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp");

    private static String core(String path) {
        try (InputStream in = TerrainLightMathTest.class.getResourceAsStream("/assets/vulkanmod/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theNearestFragmentWinsInAnyOrder() {
        int near = TerrainLightMath.pack(0.30f, 15, 0), far = TerrainLightMath.pack(0.60f, 3, 14);
        assertEquals(near, TerrainLightMath.nearest(near, far));
        assertEquals(near, TerrainLightMath.nearest(far, near));
        assertEquals(TerrainLightMath.NONE, TerrainLightMath.nearest());
        //Depth 1.0 (the far plane) is still below the cleared value
        assertNotEquals(TerrainLightMath.NONE, TerrainLightMath.pack(1, 15, 15));
    }

    @Test
    void theLevelsComeBackInQuarterSteps() {
        float[] l = TerrainLightMath.at(TerrainLightMath.pack(0.5f, 13.6f, 2.1f), 0.5f);
        assertArrayEquals(new float[]{13.5f, 2f, TerrainLightMath.SOURCE}, l, 0f);
        assertEquals(TerrainLightMath.TERRAIN_LIGHT_LEVEL_MAX, TerrainLightMath.level(15));
        assertEquals(TerrainLightMath.TERRAIN_LIGHT_LEVEL_MAX, TerrainLightMath.level(99));
        assertEquals(0, TerrainLightMath.level(-3));
        assertEquals(30, TerrainLightMath.level(7.5f));
    }

    @Test
    void onlyTheSurfaceAtTheMainDepthIsRead() {
        int v = TerrainLightMath.pack(0.5f, 15, 0);
        float step = 1f / TerrainLightMath.TERRAIN_LIGHT_DEPTH_STEPS;
        assertNotNull(TerrainLightMath.at(v, 0.5f));
        assertNotNull(TerrainLightMath.at(v, 0.5f + step), "the depth buffer's rounding");
        assertNull(TerrainLightMath.at(v, 0.5f + 3 * step), "an entity or particle in front");
        assertNull(TerrainLightMath.at(v, 0.4f));
        assertNull(TerrainLightMath.at(TerrainLightMath.NONE, 1f), "no terrain drawn");
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        Pattern p = Pattern.compile("(?m)^\\s*const\\s+(float|uint|int)\\s+(TERRAIN_(?:LIGHT|ALBEDO)_(?!MATCH_)\\w+)\\s*=\\s*([0-9.]+)u?\\s*;");
        int found = 0;
        for (String text : new String[]{PACK, READ, COPY}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                double want = Double.parseDouble(m.group(3));
                double have;
                if (m.group(2).equals("TERRAIN_LIGHT_NONE")) {
                    have = Integer.toUnsignedLong(TerrainLightMath.NONE);
                } else if (m.group(2).equals("TERRAIN_ALBEDO_WRITTEN")) {
                    have = Integer.toUnsignedLong(TerrainLightMath.TERRAIN_ALBEDO_WRITTEN);
                } else {
                    Field f = TerrainLightMath.class.getField(m.group(2));
                    have = ((Number) f.get(null)).doubleValue();
                }
                assertEquals(want, have, 1e-6, m.group(2));
                found++;
            }
        }
        assertEquals(14, found);
        //The read's step and slack are the write's
        assertTrue(READ.contains("float((v >> 6) & 63u)/TERRAIN_LIGHT_LEVEL_STEPS, float(v & 63u)/TERRAIN_LIGHT_LEVEL_STEPS, 2.0"));
        assertTrue(PACK.contains("return (d << 12) | (terrainLightLevel(light.x) << 6) | terrainLightLevel(light.y);"));
        assertTrue(PACK.contains("return TERRAIN_ALBEDO_WRITTEN | (((light >> 12) & TERRAIN_ALBEDO_TAG_MASK) << 24) | (c.b << 16) | (c.g << 8) | c.r;"));
        assertTrue(COPY.contains("return a >= TERRAIN_ALBEDO_WRITTEN && ((a >> 24) & TERRAIN_ALBEDO_TAG_MASK) == ((v >> 12) & TERRAIN_ALBEDO_TAG_MASK);"));
        //Only the nearest-so-far, opaque and unfogged fragment writes its albedo
        assertTrue(PACK.contains("if (v < before && unfogged && colour.a >= TERRAIN_ALBEDO_MIN_ALPHA) {"));
    }

    @Test
    void theAlbedoIsTakenOnlyFromTheFragmentTheLightHolds() {
        int near = TerrainLightMath.pack(0.30f, 15, 0), far = TerrainLightMath.pack(0.60f, 3, 14);
        float[] a = TerrainLightMath.albedoAt(near, TerrainLightMath.packAlbedo(0.5f, 0.25f, 1f, near));
        assertArrayEquals(new float[]{128 / 255f, 64 / 255f, 1}, a, 1e-6f);
        //A farther fragment's albedo that landed after the nearer one's
        assertNull(TerrainLightMath.albedoAt(near, TerrainLightMath.packAlbedo(0.5f, 0.25f, 1f, far)));
        assertNull(TerrainLightMath.albedoAt(near, 0), "cleared: not written");
        assertNotEquals(0, TerrainLightMath.packAlbedo(0, 0, 0, TerrainLightMath.pack(0, 0, 0)), "black is still written");
    }

    @Test
    void theWritersWriteAfterTheCutoutBeforeTheLightmapAndOnlyWhenAsked() throws Exception {
        String fsh = core("basic/terrain/terrain.fsh"), fshZ = core("basic/terrain/terrain_z.fsh"), vsh = core("basic/terrain/terrain.vsh");
        String json = core("basic/terrain/terrain.json");
        String write = "writeSceneLight(lightLevels, vec4(texel.rgb * albedoTint, texel.a), vertexDistance <= FogStart);";
        assertTrue(fsh.indexOf("discard;") < fsh.indexOf(write), "a cut-out texel writes nothing");
        assertTrue(fshZ.contains(write) && fshZ.contains("layout(early_fragment_tests) in;"));
        for (String f : new String[]{fsh, fshZ}) {
            assertTrue(f.contains("if (LightOut > 0.5) {"));
            //Both read the fragment block as terrain.json lays it out (terrain_z's skipped AlphaCutout keeps LightOut's offset)
            assertTrue(f.replace("\r\n", "\n").contains("float FogEnd;\n    float AlphaCutout;\n    float LightOut;\n"));
            //2026-10-28: water's opacity scale after LightOut, as terrain.json lays it out
            assertTrue(f.replace("\r\n", "\n").contains("    float WaterAlpha;\n};"));
            assertTrue(f.replace("\r\n", "\n").contains("#define SCENE_LIGHT_BINDING 4\n#include \"terrain_light.glsl\""));
        }
        assertTrue(json.indexOf("\"AlphaCutout\"") < json.indexOf("\"LightOut\""));
        assertTrue(json.indexOf("\"LightOut\"") < json.indexOf("\"WaterAlpha\""));
        assertTrue(json.replace("\r\n", "\n").contains("\"storageImages\": [\n        { \"name\": \"TerrainLight\" },\n        { \"name\": \"TerrainAlbedo\" }\n    ]"));
        assertTrue(json.indexOf("\"samplers\"") < json.indexOf("\"storageImages\""));
        assertTrue(vsh.contains("lightLevels = vec2(bitfieldExtract(packedLight, 12, 4), bitfieldExtract(packedLight, 4, 4));"));
        assertTrue(vsh.contains("albedoTint = Color.rgb;"));
        assertEquals("TerrainLight", TerrainLightTarget.NAME);
        assertEquals("TerrainAlbedo", TerrainLightTarget.ALBEDO_NAME);
        //The core entity, block model and particle shaders: LightOut after FogEnd, the images right after the samplers, the write
        // before the lightmap
        int writers = 0;
        for (String s : CORE_WRITERS) {
            String j = core("minecraft/core/" + s + "/" + s + ".json"), f = core("minecraft/core/" + s + "/" + s + ".fsh").replace("\r\n", "\n");
            String v = core("minecraft/core/" + s + "/" + s + ".vsh");
            JsonObject o = JsonParser.parseString(j).getAsJsonObject();
            int ubo = 0;
            for (var u : o.getAsJsonArray("UBOs")) ubo = Math.max(ubo, u.getAsJsonObject().get("binding").getAsInt());
            int first = ubo + 1 + o.getAsJsonArray("samplers").size();
            assertTrue(f.contains("#define SCENE_LIGHT_BINDING " + first + "\n#include \"terrain_light.glsl\""), s);
            assertTrue(f.contains("float FogEnd;\n    float LightOut;\n};"), s);
            assertTrue(j.indexOf("\"FogEnd\"") < j.indexOf("\"LightOut\"") && j.contains("\"TerrainAlbedo\""), s);
            assertTrue(v.contains("sceneLightLevels = vec2(UV2.y, UV2.x) / 16.0;"), s);
            int at = f.indexOf("writeSceneLight(");
            assertTrue(at > 0 && f.lastIndexOf("if (LightOut > 0.5) {", at) > 0, s);
            int lm = f.indexOf("color *= lightMapColor;");
            if (lm >= 0) assertTrue(at < lm, s + ": before the lightmap");
            else assertTrue(f.contains("writeSceneLight(sceneLightLevels, texture(Sampler0, texCoord0) * albedoTint * ColorModulator,"), s);
            writers++;
        }
        assertEquals(16, writers);
    }

    private static final String[] CORE_WRITERS = {"rendertype_entity_solid", "rendertype_entity_cutout", "rendertype_entity_cutout_no_cull",
            "rendertype_entity_cutout_no_cull_z_offset", "rendertype_entity_smooth_cutout", "rendertype_entity_translucent",
            "rendertype_entity_translucent_cull", "rendertype_item_entity_translucent_cull", "rendertype_armor_cutout_no_cull",
            "rendertype_entity_no_outline", "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent",
            "rendertype_tripwire", "particle"};

    @Test
    void theCompositeTakesItOnlyWhereTheFrameFilledIt() {
        assertTrue(READ.replace("\r\n", "\n").contains("if (lightCamera.w > 1.5) {\n        vec3 t = terrainLightAt(p, texelFetch(mainDepth, p, 0).r);"));
        //LOD pixels keep the LOD pass's light: their branch returns first
        assertTrue(READ.indexOf("clampedToLod(texelFetch(mainDepth, p, 0).r)") < READ.indexOf("terrainLightAt(p,"));
        LightVolumeUniforms.Values v = new LightVolumeUniforms.Values(true, new float[3], new float[3], new LightVolumeMath.Layout(1, 1, 1, 0), 0, 0,
                true, false, false);
        assertFalse(v.withTerrain(true).withTerrain(false).terrain());
        assertTrue(v.withTerrain(true).terrain());
        assertFalse(LightVolumeUniforms.off().withTerrain(true).terrain(), "never without the per-pixel light");
        assertArrayEquals(new float[]{0, 1, 0}, SceneCompositeMath.sourceView(2, false, true));
        assertArrayEquals(new float[]{0, 1, 1}, SceneCompositeMath.sourceView(2, false, false));
        assertArrayEquals(new float[]{0, 0, 1}, SceneCompositeMath.sourceView(1, false, false));
        assertArrayEquals(new float[]{1, 1, 0}, SceneCompositeMath.sourceView(1, true, false));
        assertArrayEquals(new float[]{SceneCompositeMath.DEBUG_UNKNOWN_RED, SceneCompositeMath.DEBUG_UNKNOWN_GREEN, SceneCompositeMath.DEBUG_UNKNOWN_BLUE},
                SceneCompositeMath.sourceView(0, false, false));
        assertTrue(COPY.contains("sourceView(pl.z, fromLod, hasAlbedo)"));
        assertTrue(COPY.contains("relit = vanillaToScene(albedo*lightmapDisplay(blockT, skyT));"));
        assertTrue(COPY.contains("lit = relit*perLight*ao;"));
        assertEquals(PostProcessSettings.LIGHT_DEBUG_MAX, SceneCompositeMath.DEBUG_SOURCE_VIEW);
    }

    /** 2026-10-23 (CTE2: grass edges flickered): another layer's albedo (the dirt under the grass side's overlay) does not match the pixel. */
    @Test
    void anAlbedoOnlyCountsWhereItGivesVanillasPixel() {
        SceneCompositeMath.Lightmap half = (block, sky) -> new float[]{PostMath.displayToLinear(0.8f), PostMath.displayToLinear(0.8f), PostMath.displayToLinear(0.8f)};
        float[] grass = {0.35f, 0.6f, 0.2f}, dirt = {0.5f, 0.35f, 0.25f};
        float[] vanillaGrass = {grass[0] * 0.8f, grass[1] * 0.8f, grass[2] * 0.8f};
        assertTrue(SceneCompositeMath.albedoMatches(grass, vanillaGrass, 0, 15, half));
        assertFalse(SceneCompositeMath.albedoMatches(dirt, vanillaGrass, 0, 15, half), "the dirt under the overlay");
        //A decal or the entity shadow drawn over it darkens vanilla's pixel
        assertFalse(SceneCompositeMath.albedoMatches(grass, new float[]{vanillaGrass[0] * 0.6f, vanillaGrass[1] * 0.6f, vanillaGrass[2] * 0.6f}, 0, 15, half));
        assertTrue(COPY.contains("bool hasAlbedo = pl.z > 1.5 && terrainAlbedoAt(p, albedo) && albedoMatches(albedo, colour.rgb, pl.y, pl.x);"));
    }
}
