package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-24 (the user's six Anime Nether references): under the Anime palette, in the Nether only, the small flames are dark red, the
 * fire is cel-shaded (a yellow body, a white rim, a pale blue fringe outside) and the lava a gradient with a pale cyan edge that comes and
 * goes ("나타났다 사라지는 방식으로"); Default and Horror keep the Nether as it was.
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
        assertTrue(COPY.contains("if (endLightParams.w > 0.5 && alphaCode(colour.a) != FIRE_ALPHA_CODE) {"));
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

    /** The small flames dark red (red well over green and blue); the embers keep the palette's colour ("불씨만 되돌려줘"). */
    @Test
    void theSmallFlamesAreDarkRed() {
        assertEquals(1, net.vulkanmod.visuals.particle.ParticleLookAccess.netherAnimeColours().length);
        for (int rgb : net.vulkanmod.visuals.particle.ParticleLookAccess.netherAnimeColours()) {
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            assertTrue(r < 200 && r > 3 * g && r > 3 * b, Integer.toHexString(rgb));
        }
    }

    /** The blue light comes and goes in patches (along the lava's banks, and around the flames); the cel fire's rim only on the flame's outline. */
    @Test
    void theEdgesWaver() {
        //2026-11-24 (the user: "나타났다 사라지는 방식으로"): no outline always there; patches that come and go with time
        assertTrue(LOOK.contains("float bank = (1.0 - smoothstep(0.55, 0.95, edge))*netherGlint(vec3(pos.xz*NETHER_LAVA_GLINT_SCALE + pos.y*0.2, t*NETHER_LAVA_GLINT_SPEED));"));
        assertTrue(LOOK.contains("float glint = netherGlint(vec3(vec2(p)/px*NETHER_FIRE_GLINT_SCALE, t*NETHER_FIRE_GLINT_SPEED));"));
        assertTrue(LOOK.contains("return smoothstep(NETHER_GLINT_FROM, NETHER_GLINT_TO, n);"));
        //"내려오는 용암과 바닥용암이 겹치는 부분": only a pool's top takes the gradient, a lava fall keeps vanilla's
        assertTrue(LOOK.contains("return mix(lit, mix(shown, cyan, bank), pool);"));
        assertTrue(COPY.contains("dot(normalWorld, normalWorld) > 0.25 ? normalWorld.y : 1.0);"));
        assertTrue(LOOK.contains("rim = !netherFireAt(p + dirs[k]*r, size) && !netherFireAt(p + dirs[k]*(r*NETHER_FIRE_RIM_OUTLINE), size);"));
    }
}
