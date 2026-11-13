package net.vulkanmod.visuals.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.config.VisualsPresets;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-10 (shader roadmap session 9): the LabPBR materials: PbrMath against pbr_terrain.glsl and pbr.glsl, the settings and presets. */
class PbrMathTest {
    private static final String TERRAIN = core("include/pbr_terrain.glsl");
    private static final String POST = ShaderFiles.read("post/pbr.glsl");
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp");
    private static final String REFLECTION = ShaderFiles.read("post/reflection.comp");

    private static String core(String path) {
        try (InputStream in = PbrMathTest.class.getResourceAsStream("/assets/vulkanmod/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        Pattern p = Pattern.compile("(?m)^const\\s+(float|uint|int)\\s+(PBR_\\w+)\\s*=\\s*(-?[0-9.]+)u?\\s*;");
        int found = 0;
        for (String text : new String[]{TERRAIN, POST}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                Field f = PbrMath.class.getField(m.group(2));
                double have = ((Number) f.get(null)).doubleValue();
                assertEquals(Double.parseDouble(m.group(3)), have, 1e-6, m.group(2));
                found++;
            }
        }
        assertTrue(found >= 45, "constants compared: " + found);
        //The predefined metals' F0 in the shader are the mirror's, and both are LabPBR's n and k at normal incidence
        for (int i = 0; i < 8; i++) {
            float[] f = PbrMath.PBR_METAL_F0[i];
            assertTrue(POST.contains(String.format("vec3(%.4f, %.4f, %.4f)", f[0], f[1], f[2])), "metal " + i);
            for (int c = 0; c < 3; c++) {
                double n = PbrMath.LABPBR_METAL_NK[i][0][c], k = PbrMath.LABPBR_METAL_NK[i][1][c];
                assertEquals(((n - 1) * (n - 1) + k * k) / ((n + 1) * (n + 1) + k * k), f[c], 1e-4, "metal " + i + " channel " + c);
            }
        }
    }

    @Test
    void aNormalRoundTripsWithinAFifthOfADegree() {
        int light = TerrainLightMath.pack(0.4f, 15, 3);
        float[][] normals = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {0, 0, -1}, {0.3f, 0.9f, -0.2f}, {-0.5f, -0.5f, 0.7f}, {0.1f, -0.2f, -0.97f}};
        for (float[] n : normals) {
            float[] u = PbrMath.normalize(n);
            PbrMath.Material m = PbrMath.material(light, PbrMath.packNormal(u[0], u[1], u[2], 0.5f, light), PbrMath.packSpecular(0, 0, 0, 255, light));
            assertNotNull(m);
            double angle = Math.toDegrees(Math.acos(Math.min(1, PbrMath.dot(u, m.normal()))));
            assertTrue(angle < 0.2, "angle " + angle);
            assertEquals(4 / 7f, m.ao(), 1e-6, "AO in 3 bits");
            assertFalse(m.specular(), "an all-zero specular texel (a sprite without a map, emission 255 = none) is no specular map");
        }
    }

    @Test
    void anotherFragmentsOrAnUnwrittenMaterialIsNotRead() {
        int light = TerrainLightMath.pack(0.4f, 15, 3), other = TerrainLightMath.pack(0.4f + 1f / 1048576f, 15, 3);
        int n = PbrMath.packNormal(0, 1, 0, 1, light), s = PbrMath.packSpecular(200, 10, 64, 255, light);
        assertNotNull(PbrMath.material(light, n, s));
        assertNull(PbrMath.material(other, n, s), "the light's depth tag differs");
        assertNull(PbrMath.material(light, n & ~PbrMath.PBR_NORMAL_WRITTEN, s), "not written (a cleared image)");
        assertNull(PbrMath.material(light, n, PbrMath.packSpecular(200, 10, 64, 255, other)), "the specular from another fragment");
        assertNull(PbrMath.material(light, 0, 0));
    }

    @Test
    void labPbrChannelsDecode() {
        int light = TerrainLightMath.pack(0.2f, 10, 0);
        int n = PbrMath.packNormal(0, 0, 1, 1, light);
        PbrMath.Material sand = PbrMath.material(light, n, PbrMath.packSpecular(30, 10, 64, 255, light));
        assertEquals(1f, sand.porosity(), 1e-6, "porosity 64 = 100%");
        assertEquals(0f, sand.sss());
        assertEquals(0f, sand.emission(), "255 = no emission");
        assertEquals(10 / 255f, sand.f0(), 1e-6);
        assertEquals(-1, sand.metal());
        PbrMath.Material wool = PbrMath.material(light, n, PbrMath.packSpecular(0, 0, 38, 255, light));
        assertEquals(38 / 64f, wool.porosity(), 1f / 30);
        PbrMath.Material leaves = PbrMath.material(light, n, PbrMath.packSpecular(0, 0, 255, 255, light));
        assertEquals(1f, leaves.sss(), 1e-6);
        assertEquals(0f, leaves.porosity());
        assertEquals(0f, PbrMath.material(light, n, PbrMath.packSpecular(0, 0, 65, 255, light)).sss(), 1e-6, "65 = no scattering");
        PbrMath.Material lamp = PbrMath.material(light, n, PbrMath.packSpecular(0, 0, 0, 254, light));
        assertEquals(1f, lamp.emission(), 1e-6, "254 = full emission");
        for (int g = 230; g <= 237; g++) assertEquals(g - 230, PbrMath.material(light, n, PbrMath.packSpecular(255, g, 0, 255, light)).metal());
        assertEquals(8, PbrMath.material(light, n, PbrMath.packSpecular(255, 255, 0, 255, light)).metal(), "255: the albedo's metal");
        assertEquals(8, PbrMath.material(light, n, PbrMath.packSpecular(255, 240, 0, 255, light)).metal(), "an undefined id: as 255");
    }

    @Test
    void rainDarkensPorousMaterialsAndGlossesClosedOnes() {
        int light = TerrainLightMath.pack(0.2f, 15, 0);
        int n = PbrMath.packNormal(0, 1, 0, 1, light);
        PbrMath.Material sand = PbrMath.material(light, n, PbrMath.packSpecular(20, 10, 64, 255, light));
        PbrMath.Material stone = PbrMath.material(light, n, PbrMath.packSpecular(20, 10, 0, 1, light));
        float[] up = {0, 1, 0}, side = {1, 0, 0};
        Object[] wetSand = PbrMath.wet(sand, 1, up), wetStone = PbrMath.wet(stone, 1, up);
        assertEquals(1 - PbrMath.PBR_WET_DARKEN, (float) wetSand[1], 1e-6, "a fully porous surface darkens");
        assertEquals(PbrMath.PBR_WET_POROUS_SMOOTHNESS, ((PbrMath.Material) wetSand[0]).smoothness(), 1e-6, "and gets a little glossier");
        assertEquals(1f, (float) wetStone[1], "a closed one keeps its colour");
        assertEquals(PbrMath.PBR_WET_FILM_SMOOTHNESS, ((PbrMath.Material) wetStone[0]).smoothness(), 1e-6, "under a film of water");
        assertTrue(PbrMath.reflects((PbrMath.Material) wetStone[0]) && !PbrMath.reflects(stone));
        assertTrue(((PbrMath.Material) PbrMath.wet(stone, 1, side)[0]).smoothness() < PbrMath.PBR_WET_FILM_SMOOTHNESS, "less film on a wall");
        assertEquals(1f, (float) wetStone[2], 1e-6, "a full film on an up face");
        assertSame(sand, PbrMath.wet(sand, 0, up)[0], "dry: unchanged");
        //A film lies flat over the bumps
        PbrMath.Material bumpy = PbrMath.material(light, PbrMath.packNormal(0.3f, 0.95f, 0, 1, light), PbrMath.packSpecular(20, 10, 0, 255, light));
        assertEquals(1f, ((PbrMath.Material) PbrMath.wet(bumpy, 1, up)[0]).normal()[1], 1e-6);
        PbrMath.Material bare = PbrMath.material(light, n, PbrMath.packSpecular(0, 0, 0, 255, light));
        assertSame(bare, PbrMath.wet(bare, 1, up)[0], "without a specular map nothing says how it takes water");
        float[] darker = PbrMath.wetColour(new float[]{0.6f, 0.5f, 0.3f}, (float) wetSand[1]);
        assertTrue(darker[0] < 0.6f * 0.5f && darker[2] < 0.3f * 0.5f, "darker");
        assertEquals(0f, PbrMath.wetAt(1, 13), "under a roof (sky light below 14): dry");
        assertEquals(1f, PbrMath.wetAt(1, 15), "open sky");
        assertEquals(0f, PbrMath.wetAt(0, 15));
    }

    @Test
    void theWetnessEasesInAndDries() {
        float w = 0;
        for (int i = 0; i < 80; i++) w = PbrMath.easeWetness(w, 1, 0.1f);
        assertEquals(1f, w, 1e-5, "full after WET_RISE_SECONDS");
        assertEquals(0.5f, PbrMath.easeWetness(0, 1, PbrMath.WET_RISE_SECONDS / 2), 1e-6);
        assertEquals(1 - 0.5f, PbrMath.easeWetness(1, 0, PbrMath.WET_DRY_SECONDS / 2), 1e-6, "dries slower");
        assertEquals(0.3f, PbrMath.easeWetness(0.2f, 0.3f, 10), 1e-6, "never past the rain");
    }

    @Test
    void theMarkCarriesSmoothnessAndReflectanceExactlyInAHalfFloat() {
        int light = TerrainLightMath.pack(0.2f, 15, 0);
        int n = PbrMath.packNormal(0, 1, 0, 1, light);
        for (int g : new int[]{0, 10, 60, 229, 230, 237, 255}) {
            for (int r : new int[]{120, 200, 255}) {
                PbrMath.Material m = PbrMath.material(light, n, PbrMath.packSpecular(r, g, 0, 255, light));
                float[] albedo = {0.75f, 0.6f, 0.25f};
                for (float film : new float[]{0, 1}) {
                    float mark = PbrMath.mark(m, film, albedo);
                    assertTrue(PbrMath.isMark(mark) && mark < -4.5f, "below every other mark");
                    assertTrue(mark >= -2048 && mark == Math.rint(mark), "an integer half floats hold exactly");
                    float[] u = PbrMath.unmark(mark);
                    assertEquals(Math.round(r / 255f * 15) / 15f, u[0], 1e-6);
                    assertEquals(PbrMath.f0Code(m, albedo), (int) u[1]);
                    assertEquals(film, u[2]);
                }
                float[] u = PbrMath.unmark(PbrMath.mark(m, 0, albedo));
                float[] f0 = PbrMath.codeF0((int) u[1], new float[]{1f, 0.6f, 0.1f});
                if (m.metal() < 0) assertEquals(Math.min(m.f0(), PbrMath.PBR_MIRROR_F0_MAX), f0[0], 0.03f, "a dielectric's F0 within its sqrt step, at most diamond's");
                else if (m.metal() < 8) assertArrayEquals(PbrMath.PBR_METAL_F0[m.metal()], f0);
                else assertArrayEquals(PbrMath.f0(m, albedo), new float[]{f0[0], f0[0] * 0.6f, f0[0] * 0.1f}, 0.06f, "the albedo's own F0 in linear units, within a sqrt step (session 16)");
            }
        }
        //Session 16: a dielectric mirrors at most like diamond (Bare Bones PBR's diamond at 0.47 looked like glass); the highlight keeps 0.47
        PbrMath.Material shiny = PbrMath.material(light, n, PbrMath.packSpecular(235, 120, 0, 255, light));
        assertEquals(PbrMath.PBR_MIRROR_F0_MAX, PbrMath.codeF0(PbrMath.f0Code(shiny, new float[3]), new float[3])[0], 1e-6);
        assertEquals(120 / 255f, PbrMath.f0(shiny, new float[3])[0], 1e-6);
        assertEquals(0.17f, (float) Math.pow((2.42 - 1) / (2.42 + 1), 2), 0.005f, "diamond's F0");
        //Session 16: an albedo metal is never a perfect mirror: its F0 is its linear albedo, and the brightest albedo still fits a half float
        PbrMath.Material albedoMetal = PbrMath.material(light, n, PbrMath.packSpecular(220, 255, 0, 255, light));
        float grey = PostMath.displayToLinear(0.5f);
        assertArrayEquals(new float[]{grey, grey, grey}, PbrMath.f0(albedoMetal, new float[]{0.5f, 0.5f, 0.5f}), 1e-6f);
        assertTrue(grey < 0.25f, "a mid grey texture is a dim mirror, not a perfect one");
        assertEquals(-(PbrMath.PBR_MARK_BASE + 15 + 16f * (PbrMath.PBR_CODE_METAL_ALBEDO + PbrMath.PBR_ALBEDO_F0_STEPS) + PbrMath.PBR_MARK_FILM),
                PbrMath.mark(PbrMath.material(light, n, PbrMath.packSpecular(255, 255, 0, 255, light)), 1, new float[]{1, 1, 1}));
        assertTrue(PbrMath.mark(PbrMath.material(light, n, PbrMath.packSpecular(255, 255, 0, 255, light)), 1, new float[]{1, 1, 1}) >= -2048);
        assertFalse(PbrMath.isMark(BlockReflectionMath.METAL_MARK) || PbrMath.isMark(-0.5f) || PbrMath.isMark(1));
        assertEquals(0f, PbrMath.reflectivity(PbrMath.PBR_REFLECT_SMOOTH_MIN, 0.04f, 1), "fades in from the threshold");
        assertEquals(0.04f, PbrMath.reflectivity(1, 0.04f, 1), 1e-6, "F0 head on");
        assertEquals(1f, PbrMath.reflectivity(1, 0.04f, 0), 1e-6, "all at grazing");
        //Session 16: a rough surface's grazing mirror reaches only its smoothness (Faithful PBR's cobblestone at 0.64 wore the sky)
        assertEquals(0.64f, PbrMath.fresnelRough(0.04f, 0, 0.64f), 1e-6);
        assertTrue(PbrMath.reflectivity(0.64f, 0.04f, 0) < 0.5f * PbrMath.fresnel(0.04f, 0));
        assertEquals(0.04f, PbrMath.fresnelRough(0.04f, 1, 0.5f), 1e-6, "head on unchanged");
        assertEquals(0.9f, PbrMath.fresnelRough(0.9f, 0, 0.5f), 1e-6, "never below F0");
    }

    @Test
    void normalMapsShadeTheSunAndScatteringLetsItThrough() {
        float[] up = {0, 1, 0}, sun = PbrMath.normalize(new float[]{0.6f, 0.5f, 0});
        assertEquals(1f, PbrMath.facingRatio(up, up, sun), 1e-6, "a flat texel: the face's own sun");
        assertTrue(PbrMath.facingRatio(PbrMath.normalize(new float[]{0.4f, 1, 0}), up, sun) > 1, "a bump toward the sun is brighter");
        assertTrue(PbrMath.facingRatio(PbrMath.normalize(new float[]{-0.4f, 1, 0}), up, sun) < 1, "away darker");
        assertEquals(1f, PbrMath.facingRatio(up, new float[]{0, -1, 0}, sun), "a face turned away is left to its shadow");
        assertTrue(PbrMath.facingRatio(new float[]{1, 0, 0}, up, PbrMath.normalize(new float[]{1, 0.01f, 0})) <= PbrMath.PBR_FACING_RATIO_MAX);
        assertEquals(PbrMath.PBR_SSS_SHARE, PbrMath.scatter(0, 1, 1), 1e-6);
        assertEquals(0.8f, PbrMath.scatter(0.8f, 1, 1), 1e-6, "never less than the face gets");
    }

    @Test
    void highlightsFollowSmoothnessAndStayBounded() {
        int light = TerrainLightMath.pack(0.2f, 15, 0);
        int n = PbrMath.packNormal(0, 1, 0, 1, light);
        PbrMath.Material rough = PbrMath.material(light, n, PbrMath.packSpecular(40, 10, 0, 255, light));
        PbrMath.Material smooth = PbrMath.material(light, n, PbrMath.packSpecular(250, 10, 0, 255, light));
        float[] l = PbrMath.normalize(new float[]{0.5f, 1, 0}), v = PbrMath.normalize(new float[]{-0.5f, 1, 0});
        float hr = PbrMath.highlight(rough, 0.04f, v, l, 2), hs = PbrMath.highlight(smooth, 0.04f, v, l, 2);
        assertTrue(hs > hr && hr > 0, hs + " " + hr);
        assertTrue(hs <= PbrMath.HIGHLIGHT_MAX);
        assertEquals(0f, PbrMath.highlight(smooth, 0.04f, v, new float[]{0, -1, 0}, 2), "no light from below");
    }

    @Test
    void theParallaxLookupMapsCellsToTheirSprites() {
        List<PbrAtlas.Sprite> sprites = List.of(new PbrAtlas.Sprite(ResourceLocation.fromNamespaceAndPath("minecraft", "block/a"), 0, 0, 32, 16),
                new PbrAtlas.Sprite(ResourceLocation.fromNamespaceAndPath("minecraft", "block/b"), 32, 0, 16, 32));
        int[] g = PbrMath.spriteCells(sprites, 64, 32, 4, 1024);
        assertNotNull(g);
        assertEquals(4, g[0], "16-pixel cells");
        assertEquals(2, g[1]);
        assertEquals(1 | (0 << 8) | (2 << 16) | (1 << 24), g[2 + 1], "the second cell of a: x 1 of 2 by 1");
        assertEquals(0 | (1 << 8) | (1 << 16) | (2 << 24), g[2 + 4 + 2], "b's lower cell: y 1 of 1 by 2");
        assertEquals(0, g[2 + 4 + 3], "an empty cell");
        assertNull(PbrMath.spriteCells(List.of(new PbrAtlas.Sprite(ResourceLocation.fromNamespaceAndPath("minecraft", "block/c"), 3, 0, 16, 16)), 64, 32, 4, 1024),
                "a sprite off the grid: no parallax");
    }

    @Test
    void mipLevelsAverage() {
        ByteBuffer b = MemoryUtil.memAlloc(2 * 2 * 4);
        try {
            int[] v = {0, 100, 200, 255};
            for (int i = 0; i < 4; i++) for (int c = 0; c < 4; c++) b.put(i * 4 + c, (byte) v[i]);
            ByteBuffer d = PbrMath.downsample(b, 2, 2);
            try {
                assertEquals((0 + 100 + 200 + 255 + 2) / 4, d.get(0) & 255);
            } finally {
                MemoryUtil.memFree(d);
            }
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    @Test
    void theShadersAreWiredAsTheJavaSideBindsThem() {
        //The terrain's PBR build: Sampler3..5 after Sampler0 and Sampler2, then the four storage images
        String json = core("basic/terrain/terrain_pbr.json");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JsonArray samplers = o.getAsJsonArray("samplers"), images = o.getAsJsonArray("storageImages");
        assertEquals(List.of("Sampler0", "Sampler2", "Sampler3", "Sampler4", "Sampler5"), samplers.asList().stream().map(e -> e.getAsJsonObject().get("name").getAsString()).toList());
        assertEquals(List.of(TerrainLightTarget.NAME, TerrainLightTarget.ALBEDO_NAME, TerrainLightTarget.NORMAL_NAME, TerrainLightTarget.SPECULAR_NAME),
                images.asList().stream().map(e -> e.getAsJsonObject().get("name").getAsString()).toList());
        String fsh = core("basic/terrain/terrain.fsh").replace("\r\n", "\n");
        assertTrue(fsh.contains("#define SCENE_LIGHT_BINDING 7\n#define SCENE_PBR_BINDING 9\n"), "2 UBOs + 5 samplers");
        assertTrue(TERRAIN.contains("layout(binding = 4) uniform sampler2D Sampler3;") && TERRAIN.contains("layout(binding = 6) uniform usampler2D Sampler5;"));
        assertTrue(json.indexOf("\"WaterAlpha\"") < json.indexOf("\"PbrParams\""));
        //The material is written only where the albedo is (opaque, unfogged, not water)
        assertTrue(fsh.contains("if (vertexColor.a <= 1.5 && vertexDistance <= FogStart && texel.a >= TERRAIN_ALBEDO_MIN_ALPHA) {"));
        //The composite and the reflection declare the material bindings and the slot in every build, read them only in PBR's
        assertTrue(COPY.contains("layout(binding = 18, r32ui) uniform readonly restrict uimage2D terrainNormal;"));
        assertTrue(COPY.contains("layout(binding = 19, r32ui) uniform readonly restrict uimage2D terrainSpecular;"));
        assertTrue(COPY.contains("layout(location = " + PbrUniforms.COPY_SLOT + ") uniform vec4 pbrParams;"));
        assertTrue(REFLECTION.contains("layout(binding = 10, r32ui) uniform readonly restrict uimage2D terrainNormal;"));
        for (String name : new String[]{"postSceneCopy.PBR", "postSceneCopy.TINTED.PBR", "postReflection.PBR", "postReflection.LEAN.PBR",
                "postReflection.TINTED.PBR", "postReflection.LEAN.TINTED.PBR"}) {
            assertTrue(ShaderCatalog.visuals().get(name).defines().containsKey("PBR"), name);
        }
        assertFalse(ShaderCatalog.visuals().get("postSceneCopy").defines().containsKey("PBR"));
        int pbrUse = COPY.indexOf("pbrMaterial(imageLoad(terrainLight, p).r, imageLoad(terrainNormal, p).r");
        assertTrue(pbrUse > 0 && COPY.lastIndexOf("#ifdef PBR", pbrUse) > COPY.lastIndexOf("#endif", pbrUse), "read only in the PBR build");
    }

    @Test
    void presetsSetPbrAndAFreshConfigIsHigh() {
        VisualsConfig c = new VisualsConfig();
        assertTrue(c.pbr && c.pbrReflections && !c.pbrParallax);
        assertEquals(100, c.pbrNormalStrength);
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsPresets.apply(c, level);
            assertEquals(level >= VisualsPresets.MEDIUM, c.pbr, "level " + level);
            assertEquals(level >= VisualsPresets.HIGH, c.pbrReflections, "level " + level);
            assertEquals(level == VisualsPresets.ULTRA, c.pbrParallax, "level " + level);
        }
        assertEquals(VisualsPresets.HIGH, VisualsPresets.detect(new VisualsConfig()));
        assertEquals(0, VisualsOptionLayout.item("pbr_normal_strength").min());
        assertEquals("pbr", VisualsOptionLayout.item("pbr_parallax").requires());
        assertEquals("per_pixel_light", VisualsOptionLayout.item("pbr").requires());
    }

    @Test
    void aLevelSavedBeforeThePbrSettingsStaysThatLevel(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path f = dir.resolve("v.json");
        //Potato as session 4 saved it (no pbr fields): the PBR defaults would make it Custom; it loads with Potato's PBR values
        VisualsConfig potato = new VisualsConfig();
        VisualsPresets.apply(potato, VisualsPresets.POTATO);
        JsonObject j = com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(potato)).getAsJsonObject();
        for (String key : new String[]{"pbr", "pbrNormalStrength", "pbrReflections", "pbrParallax"}) j.remove(key);
        j.addProperty("revision", 7);
        Files.writeString(f, j.toString());
        VisualsConfig read = VisualsConfig.read(f);
        assertEquals(VisualsPresets.POTATO, read.preset);
        assertFalse(read.pbr);
        //A Custom file keeps the PBR defaults
        j.addProperty("preset", VisualsPresets.CUSTOM);
        Files.writeString(f, j.toString());
        read = VisualsConfig.read(f);
        assertEquals(VisualsPresets.CUSTOM, read.preset);
        assertTrue(read.pbr);
        assertEquals(VisualsConfig.currentRevision(), read.revision);
        assertTrue(VisualsConfig.currentRevision() >= 8, "8 brought the PBR settings (9: the session 8 follow-up's colours)");
    }
}
