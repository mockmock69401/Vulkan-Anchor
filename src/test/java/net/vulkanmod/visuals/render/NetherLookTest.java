package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-24 (the user's six Anime Nether references): under the Anime palette, in the Nether only, the small flames are dark red, the
 * fire is cel-shaded (a yellow body with sky blue blotches) and the lava a gradient with a cyan ribbon along its shore, both coming and
 * going ("나타났다 사라지는 방식으로"); Default and Horror keep the Nether as it was.
 */
class NetherLookTest {
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp").replace(String.valueOf((char) 13), "");
    private static final String LOOK = ShaderFiles.read("post/netherlook.glsl").replace(String.valueOf((char) 13), "");
    private static final String LIGHTING = ShaderFiles.read("post/lighting.glsl").replace(String.valueOf((char) 13), "");

    @Test
    void onlyTheAnimeNether() {
        assertTrue(DimensionUniforms.netherLook(DimensionUniforms.Kind.HOT, ColourPresets.ANIME));
        for (int palette : new int[]{ColourPresets.DEFAULT, ColourPresets.HORROR, ColourPresets.CUSTOM}) {
            assertFalse(DimensionUniforms.netherLook(DimensionUniforms.Kind.HOT, palette), "palette " + palette);
        }
        assertFalse(DimensionUniforms.netherLook(DimensionUniforms.Kind.OTHER, ColourPresets.ANIME));
        assertFalse(DimensionUniforms.netherLook(DimensionUniforms.Kind.END, ColourPresets.ANIME));
        assertEquals(0, DimensionUniforms.off().netherLook());
        assertEquals(1, DimensionUniforms.off().withNetherLook(1).withGalaxy(0).withStarShadow(0).withPortalLook(2).netherLook());
        assertTrue(COPY.contains("#import <vulkanmod:visuals/post/netherlook.glsl>"));
        assertTrue(COPY.contains("if (endLightParams.w > 0.5 && !fromLod) {"));
    }

    /** A fire's quads carry their own mark (243), a light source's like the others' (free at both stages of the terrain shaders). */
    @Test
    void fireHasItsOwnMark() {
        assertEquals(243, Math.round(FoliageMarker.FIRE_ALPHA * 255 - 0.25f));
        assertEquals(243, LightingMath.FIRE_ALPHA_CODE);
        assertTrue(LightingMath.isEmissiveCode(LightingMath.FIRE_ALPHA_CODE));
        assertTrue(LIGHTING.contains("const int FIRE_ALPHA_CODE = 243;"));
        assertTrue(LIGHTING.contains("return code == EMISSIVE_ALPHA_CODE || code == EMISSIVE_FLUID_ALPHA_CODE || code == FIRE_ALPHA_CODE;"));
        //Not one of terrain.vsh's vertex marks (wind 248-250, water 247, hanging 144-239)
        int code = LightingMath.FIRE_ALPHA_CODE;
        assertTrue(code > FoliageMarker.HANG_BASE + 4 * FoliageMarker.HANG_LEVELS - 1 && code < 247);
        //Outside a build thread no block is a fire: the light source's mark as before
        assertEquals(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.alpha(null, 1f, true));
    }

    /**
     * The small flames dark red (red well over green and blue); the embers keep the palette's colour ("불씨만 되돌려줘"); the smoke a warm ash
     * ("일반 불은 끝 부분이 회색으로 보이고 있어": red over green over blue, no grey).
     */
    @Test
    void theSmallFlamesAreDarkRed() {
        assertEquals(2, net.vulkanmod.visuals.particle.ParticleLookAccess.netherAnimeColours().length);
        int flames = net.vulkanmod.visuals.particle.ParticleLookAccess.netherAnimeColour("flames");
        int r = (flames >> 16) & 0xFF, g = (flames >> 8) & 0xFF, b = flames & 0xFF;
        assertTrue(r < 200 && r > 3 * g && r > 3 * b, Integer.toHexString(flames));
        assertEquals(-1, net.vulkanmod.visuals.particle.ParticleLookAccess.netherAnimeColour("embers"));
        int smoke = net.vulkanmod.visuals.particle.ParticleLookAccess.netherAnimeColour("smoke");
        int sr = (smoke >> 16) & 0xFF, sg = (smoke >> 8) & 0xFF, sb = smoke & 0xFF;
        assertTrue(sr > sg + 40 && sg > sb, Integer.toHexString(smoke));
    }

    /** "영혼불은 일반불과 구분이 잘 안가. 영혼불을 본래의 색으로 되돌리고": soul fire and a soul campfire keep a light source's mark. */
    @Test
    void soulFireKeepsItsOwnLook() {
        String marker = readSource("net/vulkanmod/visuals/render/FoliageMarker.java");
        assertTrue(marker.contains("if (state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE) || state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)) {"));
    }

    private static String readSource(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java", path)).replace(String.valueOf((char) 13), "");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * 2026-11-25 (the user: "테두리를 기준으로 잡지 말고 ... 얼룩처럼"): the flame looks at no neighbour and has no fringe outside it; its blue
     * blotches drift with noise fixed in the world.
     */
    @Test
    void theFlameHasNoRim() {
        assertFalse(LOOK.contains("netherFireFringe"));
        assertFalse(COPY.contains("netherFireFringe"));
        assertTrue(LOOK.contains("vec3 netherFireLook(vec3 lit, vec3 vanilla, vec3 pos, float t) {"));
        assertTrue(LOOK.contains("netherNoise(vec3(pos.xz*NETHER_FIRE_BLOTCH_SCALE + pos.y*0.7, t*NETHER_FIRE_BLOTCH_SPEED))"));
        String fire = LOOK.substring(LOOK.indexOf("vec3 netherFireLook("), LOOK.indexOf("//The share of no lava"));
        assertFalse(fire.contains("texelFetch"));
        //"일반불에 흰색 느낌을 줄여줘": no white heart, and no blotch over a flame's dim tips (where it fell to a grey)
        assertFalse(fire.contains("vec3(1.0)"));
        assertTrue(fire.contains("*smoothstep(NETHER_FIRE_BLOTCH_BRIGHT_FROM, NETHER_FIRE_BLOTCH_BRIGHT_TO, m);"));
    }

    /**
     * 2026-11-25 (the user: "얼룩을 용암 테두리를 따라 나타나도록 ... 자연스러운 그라데이션 형태", references 5 and 6): the lava's shore is a soft
     * measure, the gradient and a cyan blotches gathered on the shore follow it, in stretches that come and go.
     * Something much nearer than the lava (a pillar in front of it) is no shore.
     */
    @Test
    void aRibbonAlongTheShore() {
        assertTrue(LOOK.contains("vec3 netherLavaLook(ivec2 p, ivec2 size, vec3 lit, vec3 vanilla, float dist, float pixelAngle, vec3 pos, float t, float up) {"));
        assertTrue(LOOK.contains("if (length(viewPosition(invVanillaProj, pixelUvGl(q, size), texelFetch(mainDepth, q, 0).r)) < cover) {"));
        //"파란색 테두리가 라인에 딱 붙지 않고 있어": the cyan is full against the shore, no strip of lava between
        assertTrue(LOOK.contains("float ribbon = smoothstep(mix(NETHER_RIBBON_FROM_THIN, NETHER_RIBBON_FROM_WIDE, reach),"));
        //"그라데이션을 넣고 구간별 두께도": how far in it reaches changes by stretch, and it grades from deep blue to a light cyan
        assertTrue(LOOK.contains("float deep = smoothstep(0.35, 1.0, ribbon);"));
        assertFalse(LOOK.contains("NETHER_RIBBON_GAP"));
        //"테두리 두께가 너무 칼같이 끊기고 있어. 테두리 중심의 얼룩 느낌": held against a drifting grain, it breaks into blobs inward
        assertTrue(LOOK.contains("float blotch = smoothstep(grain - NETHER_RIBBON_GRAIN_SOFT, grain + NETHER_RIBBON_GRAIN_SOFT, ribbon*stretch);"));
        //"테두리에 나타나지 않은 부분 ... 중앙 용암 색과 다르게": the lava's own colour does not follow the shore
        String lava = LOOK.substring(LOOK.indexOf("vec3 netherLavaLook("));
        String colour = lava.substring(lava.indexOf("vec3 c = mix("), lava.indexOf("vec3 shown ="));
        assertFalse(colour.contains("shore"));
        assertTrue(LOOK.contains("netherNoise(vec3(pos.xz*NETHER_LAVA_BLOTCH_SCALE, t*NETHER_LAVA_BLOTCH_SPEED))"));
        //"내려오는 용암과 바닥용암이 겹치는 부분": only a pool's top takes the look, a lava fall keeps vanilla's
        assertTrue(LOOK.contains("return mix(lit, mix(shown, blue, blotch*NETHER_LAVA_BLOTCH_MOST), pool);"));
        assertTrue(COPY.contains("dot(normalWorld, normalWorld) > 0.25 ? normalWorld.y : 1.0);"));
    }
}
