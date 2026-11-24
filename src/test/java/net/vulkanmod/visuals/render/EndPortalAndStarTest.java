package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-24 (the user, after trying the End sky): the portals' own looks per palette ("네더포탈과 엔드포탈을 일반/애니메에 적절하게 각각
 * 만들어줘"), and the End star's shadows ("엔드에서 가장 밝은 별로 인한 빛 그림자도 구현해줘 ... 성능 비용을 잘 고려해줘").
 */
class EndPortalAndStarTest {
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp").replace(String.valueOf((char) 13), "");
    private static final String LOOK = ShaderFiles.read("post/portallook.glsl").replace(String.valueOf((char) 13), "");
    private static final String END = ShaderFiles.read("post/endsky.glsl").replace(String.valueOf((char) 13), "");
    private static final long SERIAL = 42;

    @AfterEach
    void release() {
        EndStarLight.use(false);
    }

    private static PostProcessSettings settings(int endSky, boolean shadows) {
        VisualsConfig c = new VisualsConfig();
        c.enabled = true;
        c.endSky = endSky;
        c.sunShadows = shadows;
        return PostProcessSettings.from(c);
    }

    /** The Anime palette picks the Anime looks, every other one the Default look; the slot carries it, vanilla's while off. */
    @Test
    void eachPaletteHasItsPortalLook() {
        assertEquals(DimensionUniforms.PORTAL_LOOK_ANIME, DimensionUniforms.portalLook(ColourPresets.ANIME));
        assertEquals(DimensionUniforms.PORTAL_LOOK_DEFAULT, DimensionUniforms.portalLook(ColourPresets.DEFAULT));
        assertEquals(DimensionUniforms.PORTAL_LOOK_DEFAULT, DimensionUniforms.portalLook(ColourPresets.HORROR));
        assertEquals(DimensionUniforms.PORTAL_LOOK_VANILLA, DimensionUniforms.off().portalLook());
        DimensionUniforms.Values v = DimensionUniforms.compute(settings(DimensionUniforms.END_SKY_ANIME, true), DimensionUniforms.Kind.OTHER, 64.0, 0.0)
                .withPortalLook(DimensionUniforms.PORTAL_LOOK_ANIME);
        assertEquals(DimensionUniforms.PORTAL_LOOK_ANIME, v.portalLook());
        assertEquals(DimensionUniforms.PORTAL_LOOK_ANIME, v.withGalaxy(0).withStarShadow(0.4f).portalLook());
        assertTrue(LOOK.contains("const float PORTAL_LOOK_DEFAULT = " + DimensionUniforms.PORTAL_LOOK_DEFAULT + ".0;"));
        assertTrue(LOOK.contains("const float PORTAL_LOOK_ANIME = " + DimensionUniforms.PORTAL_LOOK_ANIME + ".0;"));
        assertTrue(COPY.contains("#import <vulkanmod:visuals/post/portallook.glsl>"));
    }

    /**
     * The nether portal's surface takes the look (its point on the face from portal.glsl's box), the End portal's and gateway's pixels
     * are found by their shader's alpha mark; vanilla's glow stays the scale.
     */
    @Test
    void thePortalsTakeTheirLooks() {
        assertTrue(COPY.contains("lit = endLightParams.z > 0.5 ? netherPortalLook(scene, portalFace.xy, portalFace.zw, dimensionParams.w, endLightParams.z, portalGlow)"));
        assertTrue(COPY.contains("                : lit*portalGlow;"));
        assertTrue(COPY.contains("if (endLightParams.z > 0.5 && !fromLod && alphaCode(colour.a) == END_PORTAL_ALPHA_CODE) {"));
        assertTrue(LOOK.contains("const int END_PORTAL_ALPHA_CODE = 244;"));
        String fsh = new String(readResource("/assets/vulkanmod/shaders/minecraft/core/rendertype_end_portal/rendertype_end_portal.fsh"));
        assertTrue(fsh.contains("fragColor = vec4(color, 244.25/255.0);"));
        assertEquals(244, Math.round(244.25 / 255.0 * 255.0));
        //No other mark there
        assertTrue(FoliageMarker.POLISHED_ALPHA * 255 > 245 && FoliageMarker.POLISHED_ALPHA * 255 < 246);
        //Three star layers deeper and deeper (the parallax), Default's base darker than Anime's
        assertTrue(LOOK.contains("const vec3 END_PORTAL_DEPTHS = vec3(0.6, 2.0, 5.0);"));
        assertTrue(LOOK.contains("vec3 q = pos + dir*(depth/facing);"));
    }

    private static String readJava(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/net/vulkanmod/" + path)).replace(String.valueOf((char) 13), "");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The End portal was never drawn (the user's first screenshot): without sky light (the Nether, the End) the section walk is the one
     * without directional culling, which collected no block entity sections; it collects them now, as the culled walk does.
     */
    @Test
    void blockEntitiesAreDrawnWithoutSkyLight() {
        String graph = readJava("render/chunk/graph/SectionGraph.java");
        String spectator = method(graph, "private void updateRenderChunksSpectator() {");
        assertTrue(spectator.contains("if (renderSection.containsBlockEntities()) {"));
        assertTrue(spectator.contains("this.blockEntitiesSections.add(renderSection);"));
        String culled = method(graph, "private void updateRenderChunks() {");
        assertTrue(culled.contains("this.blockEntitiesSections.add(renderSection);"));
    }

    //From signature to the next method's
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int end = source.indexOf("    private ", start + signature.length());
        return source.substring(start, end < 0 ? source.length() : end);
    }

    private static byte[] readResource(String path) {
        try (var in = EndPortalAndStarTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The star casts with the effects and Shadows on, in the End, under the Anime sky only (the Space sky has no bright star). */
    @Test
    void theStarCastsUnderTheAnimeSky() {
        assertTrue(EndStarLight.on(settings(DimensionUniforms.END_SKY_ANIME, true), true));
        assertFalse(EndStarLight.on(settings(DimensionUniforms.END_SKY_ANIME, true), false));
        assertFalse(EndStarLight.on(settings(DimensionUniforms.END_SKY_SPACE, true), true));
        assertFalse(EndStarLight.on(settings(DimensionUniforms.END_SKY_ANIME, false), true));
        assertFalse(EndStarLight.on(null, true));
        Vector3f d = EndStarLight.direction();
        assertEquals(1, d.length(), 1e-5);
        Vector3f s = new Vector3f(EndSkyMath.END_STAR_X, EndSkyMath.END_STAR_Y, EndSkyMath.END_STAR_Z).normalize();
        assertEquals(0, d.distance(s), 1e-6, "toward the star drawn in the sky");
        //The maps' light: the star's while it casts, the sun's otherwise
        assertEquals(0, EndStarLight.toward(0.3f).distance(SunMath.sunDirection(0.3f)), 1e-6);
        EndStarLight.use(true);
        assertTrue(EndStarLight.active());
        assertEquals(0, EndStarLight.toward(0.3f).distance(d), 1e-6);
        EndStarLight.use(false);
        assertFalse(EndStarLight.active());
        assertTrue(EndStarLight.SHADOW > 0.2f && EndStarLight.SHADOW < MoonShadowMath.MOON_SHADOW, "fainter than the moon's");
    }

    /**
     * The light never moves: the maps' time stands still (no sun-step redraws), and the close and near maps refresh every
     * REFRESH_NANOS instead of every 50 ms, the far one when the renderer says (FAR_REFRESH_NANOS).
     */
    @Test
    void theStarsMapsAreRarelyDrawn() {
        ShadowFrame f = ShadowFrame.fixed(SERIAL, EndStarLight.direction(), 10, 70, -5, false);
        assertEquals(EndStarLight.TIME_OF_DAY, f.timeOfDay());
        assertEquals(EndStarLight.REFRESH_NANOS, f.refreshNanos());
        assertFalse(f.farDue());
        Matrix4f view = ShadowMath.lightView(EndStarLight.direction());
        assertEquals(0, new Matrix4f(f.near().view()).sub(view).determinant(), 1e-9);
        assertTrue(EndStarLight.REFRESH_NANOS >= 10 * ShadowMath.REFRESH_MIN_NANOS);
        assertTrue(EndStarLight.FAR_REFRESH_NANOS > EndStarLight.REFRESH_NANOS);
        long second = 1_000_000_000L;
        assertTrue(ShadowMath.refreshDue(ShadowMath.NEAR_PHASE, ShadowMath.NEAR_PHASE, 0, Long.MIN_VALUE, EndStarLight.REFRESH_NANOS), "first draw");
        assertFalse(ShadowMath.refreshDue(ShadowMath.NEAR_PHASE, ShadowMath.NEAR_PHASE, second / 2, 0, EndStarLight.REFRESH_NANOS));
        assertTrue(ShadowMath.refreshDue(ShadowMath.NEAR_PHASE, ShadowMath.NEAR_PHASE, second, 0, EndStarLight.REFRESH_NANOS));
        assertTrue(ShadowMath.refreshDue(ShadowMath.NEAR_PHASE, ShadowMath.NEAR_PHASE, second / 10, 0), "the sun's as before");
        //The sun's frames keep the 50 ms refresh
        assertEquals(ShadowMath.REFRESH_MIN_NANOS, new ShadowFrame(1, null, null, false, 0, null).refreshNanos());
    }

    /**
     * The pass runs for the star alone: no AO, water or reflections, lighting.comp's light on (sunView.w) while the composite's sun shading
     * weight stays 0; the shadow uniforms read the maps; the composite darkens what the star does not reach, keeping block light.
     */
    @Test
    void thePassRunsForTheStarAlone() {
        LightingChoice c = LightingChoice.star(SERIAL, true, true);
        assertTrue(c.sun() && c.star() && c.any());
        assertFalse(c.ao() || c.water() || c.reflections());
        assertFalse(LightingChoice.star(SERIAL, false, true).sun());
        assertFalse(new LightingChoice(SERIAL, true, true, true, true).star());
        AtmosphereFrame frame = AtmosphereFrame.of(SERIAL, new Matrix4f().perspective(1.2f, 16 / 9f, 0.05f, 2048), new Matrix4f(), new Matrix4f(), 70, 63,
                new FogParameters(0, 0, 0, 1, -8, 1e6f, -8, 1e6f), 512, false, 0, 0, 0, null);
        LightingUniforms.Values v = LightingUniforms.compute(settings(DimensionUniforms.END_SKY_ANIME, true), c, frame, SERIAL, false, 0, false, false);
        assertTrue(v.on());
        assertEquals(LightingUniforms.STAR_ONLY, v.sunWeight());
        assertEquals(0, v.aoStrength());
        assertEquals(0, v.waterStrength());
        Vector3f star = EndStarLight.direction();
        assertEquals(star.x, v.sunView()[0], 1e-5);
        assertEquals(star.y, v.sunView()[1], 1e-5);
        String src = readJava("visuals/render/LightingUniforms.java");
        assertTrue(src.contains("this.sunWeight > 0 || this.sunWeight == STAR_ONLY ? 1 : 0"));
        assertTrue(src.contains("Math.max(0, this.sunWeight)"));
        assertTrue(readJava("visuals/render/ShadowUniforms.java")
                .contains("(lighting.sunWeight() <= 0 && lighting.sunWeight() != LightingUniforms.STAR_ONLY)"));
        assertTrue(COPY.contains("if (endLightParams.y > 0.0 && passRan) {"));
        assertTrue(COPY.contains("lit = endStarShade(lit, plant && plantSun >= 0.0 ? plantSun : lt.y, endLightParams.y, blockT);"));
        assertTrue(END.contains("vec3 endStarShade(vec3 lit, float starLit, float strength, float blockLight) {"));
    }

    //endsky.glsl endStarShade, ported (the tint's luminance is 1, so a grey light keeps its luminance's share)
    @Test
    void theShadowIsSoftAndKeepsBlockLight() {
        double strength = EndStarLight.SHADOW;
        double lit = shade(1, strength, 0), shadowed = shade(0, strength, 0), torch = shade(0, strength, 15);
        assertEquals(1, lit, 1e-9);
        assertEquals(1 - strength, shadowed, 0.05, "a soft shadow, not the moon's");
        assertEquals(1, torch, 1e-9, "block light keeps its own");
        assertTrue(EndSkyMath.END_STAR_SHADOW_TINT_B > EndSkyMath.END_STAR_SHADOW_TINT_R, "a little cooler");
    }

    private static double shade(double starLit, double strength, double block) {
        double r = PostMath.displayToLinear(EndSkyMath.END_STAR_SHADOW_TINT_R / 255f), g = PostMath.displayToLinear(EndSkyMath.END_STAR_SHADOW_TINT_G / 255f),
                b = PostMath.displayToLinear(EndSkyMath.END_STAR_SHADOW_TINT_B / 255f);
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double shadow = (1 - strength) * (0.2126 * r + 0.7152 * g + 0.0722 * b) / lum;
        double s = shadow + (1 - shadow) * Math.max(0, Math.min(1, starLit));
        double t = Math.max(0, Math.min(1, (block - EndSkyMath.END_GROUND_BLOCK_FROM) / (EndSkyMath.END_GROUND_BLOCK_TO - EndSkyMath.END_GROUND_BLOCK_FROM)));
        t = t * t * (3 - 2 * t);
        return s + (1 - s) * t;
    }
}
