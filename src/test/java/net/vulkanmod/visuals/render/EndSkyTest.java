package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.config.OldFile;
import net.vulkanmod.visuals.config.PostOn;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-23 (the user: "엔드 배경은 우주 느낌이 나도록 만들어줘"; the Anime palette after two screenshots, Default (then also Realistic and Cinematic)
 * after two more): endsky.glsl against its mirror (EndSkyMath), the two skies' shapes, the palettes' pick of the End sky, an older file's
 * End sky, and where the composite draws it.
 */
class EndSkyTest {
    private static final String END = ShaderFiles.read("post/endsky.glsl");
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp").replace(String.valueOf((char) 13), "");
    private static final String ENDSKY = ShaderFiles.read("post/endsky.glsl").replace(String.valueOf((char) 13), "");
    private static final int[] PALETTES = {ColourPresets.DEFAULT, ColourPresets.ANIME, ColourPresets.HORROR};
    private static final int[] STYLES = {DimensionUniforms.END_SKY_VANILLA, DimensionUniforms.END_SKY_SPACE, DimensionUniforms.END_SKY_ANIME};

    private static float[] dir(float azimuthDeg, float elevationDeg) {
        double a = Math.toRadians(azimuthDeg), e = Math.toRadians(elevationDeg);
        return new float[]{(float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e), (float) (-Math.cos(e) * Math.cos(a))};
    }

    private static float lum(float[] c) {
        return PostMath.luminance(c[0], c[1], c[2]);
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = EndSkyTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyShaderConstantEqualsItsMirrorsField() throws Exception {
        int found = 0;
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(END);
        while (m.find()) {
            Field f = EndSkyMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 50, "constants found: " + found);
        for (Field f : EndSkyMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class)) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(END).find(), f.getName() + " missing in the shader");
        }
        assertFalse(Pattern.compile("(?m)^\\s*(layout|uniform)\\b").matcher(END).find(), "no uniforms: any shader imports it");
        //The shader's style numbers are the setting's
        assertEquals(DimensionUniforms.END_SKY_SPACE, (int) EndSkyMath.END_SKY_SPACE);
        assertEquals(DimensionUniforms.END_SKY_ANIME, (int) EndSkyMath.END_SKY_ANIME);
        //The bright star sits on a unit direction above the horizon
        float[] d = {EndSkyMath.END_STAR_X, EndSkyMath.END_STAR_Y, EndSkyMath.END_STAR_Z};
        assertEquals(1, d[0] * d[0] + d[1] * d[1] + d[2] * d[2], 2e-3);
        assertTrue(d[1] > 0.2f);
        assertFalse(END.contains("PLANET") || END.contains("Planet"), "2026-11-24: no planet");
    }

    /**
     * 2026-11-24 (the user: stars on every palette, brighter than the overworld's): both styles draw their own stars, whatever the galaxy
     * setting; wider than the galaxy's and vanilla's (about a pixel), the bright layer at least display white at the bottom of its twinkle.
     */
    @Test
    void bothStylesDrawStarsBrighterThanTheOverworlds() {
        String sky = END.substring(END.indexOf("vec3 endSky("));
        assertEquals(3, sky.split(Pattern.quote("endStars(dir, pixelAngle, t)"), -1).length, "Space and Anime both add endStars");
        float faintest = EndSkyMath.END_STARS_BRIGHT_LIGHT * EndSkyMath.END_STARS_MIN * (1 - EndSkyMath.END_STARS_TWINKLE);
        assertTrue(faintest >= 1, "the bright layer's faintest: " + faintest);
        assertTrue(EndSkyMath.END_STARS_BRIGHT_LIGHT * (EndSkyMath.END_STARS_MIN + 1) > 5, "the brightest bloom");
        assertTrue(EndSkyMath.END_STARS_BRIGHT_SIGMA > GalaxyMath.GALAXY_STAR_SIGMA && EndSkyMath.END_STARS_DIM_SIGMA > GalaxyMath.GALAXY_STAR_SIGMA);
        assertTrue(EndSkyMath.END_STARS_DIM_LIGHT * EndSkyMath.END_STARS_MIN > GalaxyMath.GALAXY_BRIGHT_LIGHT * GalaxyMath.GALAXY_STAR_BRIGHTNESS,
                "even the dim layer outshines the galaxy's bright stars");
        //A star's light is spent before its cell's edge (a pixel reads only its own cell: no square cut), at 2560x1440 and FOV 70
        float pixel = (float) Math.toRadians(70) / 1440;
        for (float[] layer : new float[][]{{EndSkyMath.END_STARS_BRIGHT_CELLS, EndSkyMath.END_STARS_BRIGHT_SIGMA, EndSkyMath.END_STARS_BRIGHT_LIGHT * 1.35f},
                {EndSkyMath.END_STARS_DIM_CELLS, EndSkyMath.END_STARS_DIM_SIGMA, EndSkyMath.END_STARS_DIM_LIGHT * 1.35f}}) {
            float edge = (float) (Math.PI / 2 / layer[0] / pixel) * EndSkyMath.END_STARS_INSET;
            assertTrue(layer[2] * Math.exp(-edge * edge / (2 * layer[1] * layer[1])) < 1e-3, "spent at the edge: " + edge + " px");
        }
    }

    /** Space: near black overhead, a faint blue haze over the horizon, dark again below it (the void). */
    @Test
    void spaceIsANavyNightWithABlueHazeLowDown() {
        float[] up = EndSkyMath.endSpaceSky(dir(0, 90)), high = EndSkyMath.endSpaceSky(dir(0, 45)), horizon = EndSkyMath.endSpaceSky(dir(0, 0));
        float[] below = EndSkyMath.endSpaceSky(dir(0, -60));
        assertTrue(lum(up) < 0.002f, "near black overhead: " + lum(up));
        assertTrue(lum(high) < lum(horizon) && lum(below) < lum(horizon));
        assertTrue(horizon[2] > horizon[1] && horizon[1] > horizon[0], "blue haze");
        assertTrue(up[2] > up[0], "navy, not grey");
        //The same all around
        assertArrayEquals(EndSkyMath.endSpaceSky(dir(0, 10)), EndSkyMath.endSpaceSky(dir(200, 10)), 1e-6f);
    }

    /** Anime: a deep indigo overhead, a bright pink-white horizon, brighter on the star's side. */
    @Test
    void animeIsAnIndigoToPinkGradientBrighterTowardTheStar() {
        float[] up = EndSkyMath.endAnimeSky(dir(0, 90)), mid = EndSkyMath.endAnimeSky(dir(0, 30)), horizon = EndSkyMath.endAnimeSky(dir(0, 1));
        assertTrue(lum(up) < lum(mid) && lum(mid) < lum(horizon));
        assertTrue(up[2] > 3 * up[0] && up[2] > 3 * up[1], "indigo overhead");
        assertTrue(horizon[0] > horizon[1], "pink on the horizon");
        double starAzimuth = Math.toDegrees(Math.atan2(EndSkyMath.END_STAR_X, -EndSkyMath.END_STAR_Z));
        float near = lum(EndSkyMath.endAnimeSky(dir((float) starAzimuth, 2))), far = lum(EndSkyMath.endAnimeSky(dir((float) starAzimuth + 180, 2)));
        assertTrue(near > far, "the star's side glows: " + near + " vs " + far);
        assertTrue(lum(EndSkyMath.endAnimeSky(dir(0, -40))) < lum(horizon), "dimmer below");
        //Space's sky is far darker than Anime's at every height
        for (int e = -30; e <= 90; e += 15) assertTrue(lum(EndSkyMath.endSpaceSky(dir(0, e))) < lum(EndSkyMath.endAnimeSky(dir(0, e))));
        //The galaxy: Anime has no band; Space and vanilla's sky keep it as it was
        assertEquals(0, EndSkyMath.endSkyBand(EndSkyMath.END_SKY_ANIME));
        assertEquals(1, EndSkyMath.endSkyStars(EndSkyMath.END_SKY_ANIME));
        assertEquals(1, EndSkyMath.endSkyBand(EndSkyMath.END_SKY_SPACE));
        assertEquals(1, EndSkyMath.endSkyStars(0));
    }

    /** Anime picks its painted sky; Default picks Space; Horror leaves it; each shows only with its own. */
    @Test
    void thePalettesPickTheEndSky() {
        for (int from : PALETTES) {
            for (int style : STYLES) {
                for (int to : PALETTES) {
                    VisualsConfig c = new VisualsConfig();
                    ColourPresets.apply(c, from);
                    c.endSky = style;
                    ColourPresets.apply(c, to);
                    int want = to == ColourPresets.ANIME ? DimensionUniforms.END_SKY_ANIME : to == ColourPresets.HORROR ? style : DimensionUniforms.END_SKY_SPACE;
                    String what = "palette " + from + " -> " + to + ", style " + style;
                    assertEquals(want, c.endSky, what);
                    assertEquals(to, ColourPresets.detect(c), what);
                }
            }
        }
        for (int palette : PALETTES) {
            for (int style : STYLES) {
                VisualsConfig c = new VisualsConfig();
                ColourPresets.apply(c, palette);
                c.endSky = style;
                boolean own = ColourPresets.endSkies(palette) == ColourPresets.ANY_END_SKY || ColourPresets.endSkies(palette) == style;
                assertEquals(own ? palette : ColourPresets.CUSTOM, ColourPresets.detect(c), "palette " + palette + ", style " + style);
            }
        }
        for (int style : STYLES) assertEquals(style, ColourPresets.endSky(ColourPresets.CUSTOM, style), "Custom changes nothing");
        VisualsConfig fresh = new VisualsConfig();
        assertEquals(DimensionUniforms.END_SKY_SPACE, fresh.endSky);
        assertEquals(ColourPresets.DEFAULT, ColourPresets.detect(fresh));
    }

    /** A file saved before the setting takes its palette's End sky (Space on Horror and Custom) and stays on its palette. */
    @Test
    void anOlderFileTakesItsPalettesEndSky(@TempDir Path dir) throws Exception {
        for (int palette : PALETTES) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, palette);
            Path f = dir.resolve("visuals-" + palette + ".json");
            c.write(f);
            String json = Files.readString(f);
            String without = json.replaceAll("\\s*\"endSky\":\\s*\\d+,?", "");
            assertNotEquals(json, without);
            Files.writeString(f, without);
            VisualsConfig read = VisualsConfig.read(f);
            int want = palette == ColourPresets.ANIME ? DimensionUniforms.END_SKY_ANIME : DimensionUniforms.END_SKY_SPACE;
            assertEquals(want, read.endSky, "palette " + palette);
            assertEquals(palette, read.colourPreset, "palette " + palette);
        }
        //2026-11-24: a file from before the three palettes (their old numbers) takes the new palette's: Anime its sky, a file on Realistic
        // (gone: it takes Default) Space
        for (int palette : PALETTES) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, palette);
            Path f = dir.resolve("old-" + palette + ".json");
            c.write(f);
            Files.writeString(f, Files.readString(f).replaceAll("\\s*\"endSky\":\\s*\\d+,?", ""));
            OldFile.age(f, 27);
            VisualsConfig read = VisualsConfig.read(f);
            assertEquals(palette, read.colourPreset, "old palette " + palette);
            assertEquals(palette == ColourPresets.ANIME ? DimensionUniforms.END_SKY_ANIME : DimensionUniforms.END_SKY_SPACE, read.endSky, "old palette " + palette);
        }
        //A file that has it keeps it (vanilla's sky on Horror)
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.HORROR);
        c.endSky = DimensionUniforms.END_SKY_VANILLA;
        Path f = dir.resolve("kept.json");
        c.write(f);
        assertEquals(DimensionUniforms.END_SKY_VANILLA, VisualsConfig.read(f).endSky);
    }

    /** Only the End gets its sky style; the setting and its labels exist; the composite draws it on the End's sky. */
    @Test
    void theUniformsTheSettingAndTheComposite() throws Exception {
        VisualsConfig c = PostOn.defaults();
        c.endSky = DimensionUniforms.END_SKY_ANIME;
        PostProcessSettings s = PostProcessSettings.from(c);
        assertEquals(DimensionUniforms.END_SKY_ANIME, DimensionUniforms.compute(s, DimensionUniforms.Kind.END, 70, 1).endSky());
        assertEquals(DimensionUniforms.END_SKY_VANILLA, DimensionUniforms.compute(s, DimensionUniforms.Kind.HOT, 70, 1).endSky());
        assertEquals(DimensionUniforms.END_SKY_VANILLA, DimensionUniforms.compute(s, DimensionUniforms.Kind.OTHER, 70, 1).endSky());
        assertEquals(DimensionUniforms.END_SKY_ANIME, DimensionUniforms.compute(s, DimensionUniforms.Kind.END, 70, 1).withGalaxy(0).endSky());
        assertEquals(DimensionUniforms.END_SKY_VANILLA, DimensionUniforms.off().endSky());
        assertEquals(DimensionUniforms.END_SKY_MAX, VisualsOptionLayout.item(ColourPresets.END_SKY_KEY).max());
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            String text = resource("/assets/vulkanmod/lang/" + lang + ".json");
            for (String key : new String[]{"end_sky", "end_sky.tooltip", "end_sky.0", "end_sky.1", "end_sky.2"}) {
                assertTrue(text.contains("\"vulkanmod.visuals." + key + "\""), lang + " " + key);
            }
        }
        assertTrue(COPY.contains("layout(location = 81) uniform vec4 endSkyParams;"));
        assertEquals(81, DimensionUniforms.COPY_END_SKY);
        assertTrue(COPY.contains("#import <vulkanmod:visuals/post/endsky.glsl>"));
        assertTrue(COPY.indexOf("#import <vulkanmod:visuals/post/galaxy.glsl>") < COPY.indexOf("#import <vulkanmod:visuals/post/endsky.glsl>"));
        assertTrue(COPY.contains("vec3 back = endSkyParams.x > 0.5 ? endSky(dir, endSkyParams.x, pixelAngle, dimensionParams.w) : scene;"));
    }

    /** 2026-11-24 (the user: "우주 오로라를 약하게 해봐줘"): the Space sky's aurora fainter, the Anime one's and vanilla's sky's as before. */
    @Test
    void theSpaceSkysAuroraIsFainter() {
        assertTrue(EndSkyMath.END_SPACE_AURORA > 0 && EndSkyMath.END_SPACE_AURORA <= 0.5f, "fainter, still there");
        assertEquals(1, EndSkyMath.END_ANIME_AURORA);
        assertTrue(COPY.contains("auroraScreen(dir, p)*endSkyAurora(endSkyParams.x)"));
        String f = END.substring(END.indexOf("float endSkyAurora("));
        assertTrue(f.contains("END_ANIME_AURORA : abs(style - END_SKY_SPACE) < 0.5 ? END_SPACE_AURORA : 1.0;"));
    }

    //endsky.glsl endPillarLayer and endPillar, ported (scene units, one channel each: the layers' light without colour)
    private static double pillarLayer(double d, double tc, double tMax, double a, double sigma, double light, double pixelAngle) {
        double wide = Math.max(sigma, EndSkyMath.END_PILLAR_MIN_PIXELS * pixelAngle * Math.max(tc, 0));
        double k = a / (1.41421356 * wide);
        double share = 0.5 * (Math.tanh(1.2027606 * k * (tMax - tc)) + Math.tanh(1.2027606 * k * tc));
        return light * (sigma / wide) * Math.exp(-d * d / (2 * wide * wide)) * Math.max(share, 0);
    }

    private static double smooth(double e0, double e1, double x) {
        double k = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return k * k * (3 - 2 * k);
    }

    private static double noise(double x, double y, double z) {
        return GalaxyMath.galaxyNoise((float) x, (float) y, (float) z);
    }

    //endsky.glsl endPillar, ported (the layers' light without colour)
    private static double pillar(double[] rel, double[] dir, double tMax, double pixelAngle, double t) {
        return pillar(rel, dir, tMax, pixelAngle, 60, t);
    }

    private static double pillar(double[] rel, double[] dir, double tMax, double pixelAngle, double depth, double t) {
        return pillar(rel, dir, tMax, pixelAngle, depth, t, 1);
    }

    private static double pillar(double[] rel, double[] dir, double tMax, double pixelAngle, double depth, double t, double fade) {
        double f = smooth(0, 1, fade);
        if (!(f > 0)) return 0;
        double a2 = Math.max(dir[0] * dir[0] + dir[2] * dir[2], 1e-6), a = Math.sqrt(a2);
        double tc = -(rel[0] * dir[0] + rel[2] * dir[2]) / a2;
        double across = (dir[0] * rel[2] - dir[2] * rel[0]) / a;
        double reach = 6 * EndSkyMath.END_PILLAR_HALO * EndSkyMath.END_PILLAR_BASE_WIDTH + EndSkyMath.END_PILLAR_SWAY;
        if (Math.abs(across) > reach + EndSkyMath.END_PILLAR_MIN_PIXELS * 6 * pixelAngle * Math.max(tc, 0) || tc < -reach / a) return 0;
        double h = rel[1] + Math.max(tc, 0) * dir[1];
        double tip = EndSkyMath.END_PILLAR_TOP * (1 + EndSkyMath.END_PILLAR_TIP * (2 * noise(t * 0.7, 3.5, 9.5) - 1))
                * (EndSkyMath.END_PILLAR_IGNITE + (1 - EndSkyMath.END_PILLAR_IGNITE) * f);
        double height = smooth(-depth, EndSkyMath.END_PILLAR_RISE - depth, h) * Math.exp(-Math.max(h, 0) / EndSkyMath.END_PILLAR_HEIGHT)
                * (1 - smooth(0.45 * tip, tip, h));
        if (!(height > 0)) return 0;
        double width = EndSkyMath.END_PILLAR_BASE_WIDTH + (EndSkyMath.END_PILLAR_TOP_WIDTH - EndSkyMath.END_PILLAR_BASE_WIDTH)
                * smooth(0, EndSkyMath.END_PILLAR_TAPER, h);
        double sway = EndSkyMath.END_PILLAR_SWAY * smooth(EndSkyMath.END_PILLAR_SWAY_FROM, EndSkyMath.END_PILLAR_SWAY_FROM + EndSkyMath.END_PILLAR_TAPER, h)
                * (2 * noise(h / 40 - t * 0.45, 1.5, 5.5) - 1);
        double d = Math.abs(across - sway);
        double rise = h / EndSkyMath.END_PILLAR_STREAK_LENGTH - t * EndSkyMath.END_PILLAR_STREAK_SPEED;
        double x = (across - sway) / (EndSkyMath.END_PILLAR_GLOW * width);
        double streak = 0.6 * noise(x * 1.3 + 17, rise, 2.5) + 0.4 * noise(x * 3.1 + 41, rise * 1.7, 6.5);
        double tongue = 1 - EndSkyMath.END_PILLAR_STREAK + 2 * EndSkyMath.END_PILLAR_STREAK * smooth(0.25, 0.75, streak);
        double slant = Math.min(1 / a, EndSkyMath.END_PILLAR_SLANT);
        return f * height * slant * (pillarLayer(d, tc, tMax, a, EndSkyMath.END_PILLAR_CORE * width, EndSkyMath.END_PILLAR_CORE_LIGHT, pixelAngle) * (0.4 + 0.6 * tongue)
                + pillarLayer(d, tc, tMax, a, EndSkyMath.END_PILLAR_GLOW * width, EndSkyMath.END_PILLAR_GLOW_LIGHT, pixelAngle) * tongue
                + pillarLayer(d, tc, tMax, a, EndSkyMath.END_PILLAR_HALO * width, EndSkyMath.END_PILLAR_HALO_LIGHT, pixelAngle));
    }

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /**
     * 2026-11-24 (the user: "노란 빛기둥 ... 엔더섬 정 가운데 빛기둥으로 구현해볼 수 있어?"): a golden beam over the exit portal, bright on its
     * axis and soft at its sides, seen from across the island, nothing below the column's top or behind terrain, and drawn on every
     * pixel of the End with an End sky (the composite's main, after compositeAt).
     */
    @Test
    void theLightPillarStandsOverTheExitPortal() {
        double pixel = 2 * Math.tan(Math.toRadians(35)) / 1440;
        double t = 3.0;
        //From 80 blocks east, 10 above the column's top, looking at the flame 30 blocks up
        double[] rel = {80, 10, 0};
        double[] at = unit(-80, 20, 0);
        double core = pillar(rel, at, 1e9, pixel, t);
        assertTrue(core > 1.5, "white-hot on its axis: " + core);
        //Soft sides: a block and a half aside dimmer, four blocks aside dark
        assertTrue(pillar(rel, unit(-80, 20, 1.5), 1e9, pixel, t) < core * 0.5, "soft sides");
        assertTrue(pillar(rel, unit(-80, 20, 4.0), 1e9, pixel, t) < 0.05, "narrow");
        //Its light runs on below the column's top (the portal's height: the island hides it), not below that, nor behind a surface
        // between the camera and the flame
        assertTrue(pillar(rel, unit(-80, -40, 0), 1e9, pixel, 60, t) > 0.3, "under the column's top");
        assertEquals(0, pillar(rel, unit(-80, -80, 0), 1e9, pixel, 60, t), 1e-6);
        assertTrue(pillar(rel, at, 40, pixel, t) < core * 1e-3, "hidden behind terrain");
        //Fainter higher up, still there 60 blocks up; burnt out by 160 (not too high: the third screenshot)
        double high = pillar(rel, unit(-80, 50, 0), 1e9, pixel, t);
        assertTrue(high < core && high > 0.1, "high: " + high);
        for (double tt = 0; tt < 30; tt += 0.7) {
            assertEquals(0, pillar(new double[]{80, 0, 0}, unit(-80, 160, 0), 1e9, pixel, tt), 1e-9, "burnt out");
        }
        //A flame, not a rod: wider at its foot than up high, and streaked (its light along it varies, and moves with time)
        assertTrue(EndSkyMath.END_PILLAR_BASE_WIDTH > 2 * EndSkyMath.END_PILLAR_TOP_WIDTH);
        double lo = Double.MAX_VALUE, hi = 0;
        for (int i = 0; i < 40; i++) {
            double v = pillar(rel, unit(-80, 10 + i, 0.3), 1e9, pixel, t);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        assertTrue(hi > 1.5 * lo, "streaked: " + lo + " .. " + hi);
        assertNotEquals(pillar(rel, unit(-80, 20, 0.3), 1e9, pixel, t), pillar(rel, unit(-80, 20, 0.3), 1e9, pixel, t + 0.5), "it flickers");
        //Golden: the glow's colour warmer than the core's
        assertTrue(EndSkyMath.END_PILLAR_GOLD_B < EndSkyMath.END_PILLAR_GOLD_G && EndSkyMath.END_PILLAR_GOLD_G < EndSkyMath.END_PILLAR_GOLD_R);
        assertTrue(EndSkyMath.END_PILLAR_CORE_B > EndSkyMath.END_PILLAR_GOLD_B && EndSkyMath.END_PILLAR_GOLD_G > EndSkyMath.END_PILLAR_EMBER_G);
        //The composite: after compositeAt, with the End sky on, the camera from the axis and the column's top in endSkyParams.yzw
        String main = COPY.substring(COPY.indexOf("vec4 composite = compositeAt(p, size, colour, overdrawn);"));
        assertTrue(main.contains("if (endSkyParams.x > 0.5 && endSkyParams.w > -1e8) {"));
        assertTrue(main.contains("float top = floor(endSkyParams.w);"));
        assertTrue(main.contains("vec3 rel = vec3(endSkyParams.y, dimensionParams.z - top, endSkyParams.z);"));
        assertTrue(main.contains("composite.rgb += endPillar(rel, dir, far, pixelAngle, (endSkyParams.w - top)*1024.0, dimensionParams.w, endLightParams.x)*underwaterExposure();"));
        DimensionUniforms.Values v = DimensionUniforms.compute(PostProcessSettings.from(PostOn.defaults()), DimensionUniforms.Kind.END,
                100.5, 80, -20.5, 66, 1);
        assertEquals(100, v.pillarX(), 1e-4);
        assertEquals(-21, v.pillarZ(), 1e-4);
        assertEquals(66, v.pillarBase(), 1e-4);
        assertEquals(v.pillarX(), v.withGalaxy(0).pillarX());
        //2026-11-24 (the user: "빛기둥의 시작지점을 포탈 y좌표만큼 아래로"; then "출력 조건을 드래곤 알 제거 여부에서 엔더드래곤 처치로"): the
        // column's top lowered by the lit portal's y; none while the dragon lives (no lit portal), whether or not its egg is there
        float lit = DimensionUniforms.pillar(68, 64);
        assertEquals(68, DimensionUniforms.pillarTop(lit));
        assertEquals(64, DimensionUniforms.pillarDepth(lit), 1e-3);
        assertEquals(DimensionUniforms.PILLAR_OFF, DimensionUniforms.pillar(68, Integer.MIN_VALUE));
        assertEquals(DimensionUniforms.PILLAR_OFF, DimensionUniforms.compute(PostProcessSettings.from(PostOn.defaults()), DimensionUniforms.Kind.OTHER,
                0, 80, 0, 4, 1).pillarBase());
        assertEquals(DimensionUniforms.PILLAR_OFF, DimensionUniforms.off().pillarBase());
        assertTrue(DimensionUniforms.PILLAR_OFF < -1e8f);
    }

    /**
     * 2026-11-24 (the user: "엔더드래곤 처치 후 빛기둥이 생길 때 페이드인으로 구현해줘"): once the exit portal lights in sight the pillar fades in over
     * PILLAR_FADE_SECONDS, short and dim at first; a portal already lit when its chunk loads shows it at once; the slot carries the fade.
     */
    @Test
    void thePillarFadesInOnceTheDragonDies() {
        DimensionUniforms.PillarFade f = new DimensionUniforms.PillarFade();
        long s = 1_000_000_000L;
        assertEquals(0, f.update(true, false, 0), "the dragon lives");
        assertEquals(0, f.update(true, true, s / 60), 1e-6, "lit: starts dark");
        float mid = 0;
        for (int i = 2; i <= 60 * 2; i++) mid = f.update(true, true, i * s / 60);
        assertEquals(2 / DimensionUniforms.PILLAR_FADE_SECONDS, mid, 0.02, "half way");
        float end = 0;
        for (int i = 121; i <= 60 * 6; i++) end = f.update(true, true, i * s / 60);
        assertEquals(1, end, 1e-6, "in full");
        //Unloaded, then found lit (entering the End after the fight): at once
        assertEquals(0, f.update(false, false, 7 * s));
        assertEquals(1, f.update(true, true, 7 * s + s / 60), 1e-6);
        f.reset();
        assertEquals(1, f.update(true, true, 9 * s), 1e-6, "another level, already lit");
        //A long pause does not jump the fade
        f.update(true, false, 10 * s);
        f.update(true, true, 10 * s + 1);
        assertTrue(f.update(true, true, 20 * s) < 0.1f);
        //In the shader: dark and short at 0, the whole flame at 1
        double pixel = 2 * Math.tan(Math.toRadians(35)) / 1440;
        double[] rel = {80, 10, 0};
        assertEquals(0, pillar(rel, unit(-80, 20, 0), 1e9, pixel, 60, 3, 0), 1e-12);
        double half = pillar(rel, unit(-80, 20, 0), 1e9, pixel, 60, 3, 0.5), full = pillar(rel, unit(-80, 20, 0), 1e9, pixel, 60, 3, 1);
        assertTrue(half > 0 && half < full, half + " " + full);
        assertEquals(0, pillar(rel, unit(-80, 60, 0), 1e9, pixel, 60, 3, 0.2), 1e-9, "short while it ignites");
        assertTrue(pillar(rel, unit(-80, 60, 0), 1e9, pixel, 60, 3, 1) > 0, "tall once lit");
        DimensionUniforms.Values v = DimensionUniforms.compute(PostProcessSettings.from(PostOn.defaults()), DimensionUniforms.Kind.END,
                0, 80, 0, 66, 0.25f, 1);
        assertEquals(0.25f, v.pillarFade(), 1e-6);
        assertEquals(0.25f, v.withGalaxy(0).pillarFade(), 1e-6);
        assertEquals(1, DimensionUniforms.off().pillarFade(), 1e-6);
        assertTrue(COPY.contains("layout(location = 82) uniform vec4 endLightParams;"));
        assertEquals(82, DimensionUniforms.COPY_END_LIGHT);
    }

    /**
     * 2026-11-24 (the user: "엔더드래곤을 처치해야만 하늘이 출력되고 있어. 하늘은 엔더드래곤 처치 여부와 상관없이 항상 보이고 있어야 해"): the dragon's
     * boss bar asks for the world fog, which put the atmosphere in its fog mode for the whole fight; in the End with its own sky the
     * fog is left out of renderLevel (a redirect of BossHealthOverlay.shouldCreateWorldFog), registered with the visuals' mixins.
     */
    @Test
    void theEndSkyShowsDuringTheFight() throws Exception {
        String mixin = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/net/vulkanmod/mixin/visuals/LevelRendererBossFogMixin.java"));
        assertTrue(mixin.contains("@Redirect(method = \"renderLevel\""));
        assertTrue(mixin.contains("Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z"));
        assertTrue(mixin.contains("return overlay.shouldCreateWorldFog() && !DimensionUniforms.endSkyDrawn(Minecraft.getInstance().level);"));
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/vulkanmod.mixins.json"));
        assertTrue(json.contains("\"visuals.LevelRendererBossFogMixin\""));
        assertFalse(DimensionUniforms.endSkyDrawn(null));
    }

    /**
     * 2026-11-24 (the user: "애니메이션 프리셋의 별들이 지평선에 가까울수록 다소 흐릿해지고 있어"): the stars' light rises with the sky's behind
     * them, so over the Anime sky's bright horizon a star keeps about its contrast; over the dark Space sky it barely changes.
     */
    @Test
    void theStarsStayCrispOverTheBrightHorizon() {
        assertTrue(EndSkyMath.END_STARS_LIFT >= 3);
        assertTrue(ENDSKY.contains("return back + endStars(dir, pixelAngle, t)*endStarsLift(back);"));
        assertTrue(ENDSKY.contains("return back + endAnimeStar(dir, pixelAngle) + endStars(dir, pixelAngle, t)*endStarsLift(back);"));
        double horizon = lum(srgb(EndSkyMath.END_ANIME_HORIZON_R, EndSkyMath.END_ANIME_HORIZON_G, EndSkyMath.END_ANIME_HORIZON_B));
        double zenith = lum(srgb(EndSkyMath.END_ANIME_ZENITH_R, EndSkyMath.END_ANIME_ZENITH_G, EndSkyMath.END_ANIME_ZENITH_B));
        double space = lum(srgb(EndSkyMath.END_SPACE_ZENITH_R, EndSkyMath.END_SPACE_ZENITH_G, EndSkyMath.END_SPACE_ZENITH_B));
        assertTrue(1 + EndSkyMath.END_STARS_LIFT * horizon > 3, "lifted at the pink horizon");
        assertTrue(1 + EndSkyMath.END_STARS_LIFT * zenith < 1.1, "as before overhead");
        assertTrue(1 + EndSkyMath.END_STARS_LIFT * space < 1.05, "as before in space");
    }

    private static double srgb(double v) {
        double c = v / 255;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double lum(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double[] srgb(double r, double g, double b) {
        return new double[]{srgb(r), srgb(g), srgb(b)};
    }

    //endsky.glsl endGround, ported
    private static double[] ground(double[] lit, boolean space, double blockLight) {
        float r = space ? EndSkyMath.END_SPACE_GROUND_R : EndSkyMath.END_ANIME_GROUND_R;
        float g = space ? EndSkyMath.END_SPACE_GROUND_G : EndSkyMath.END_ANIME_GROUND_G;
        float b = space ? EndSkyMath.END_SPACE_GROUND_B : EndSkyMath.END_ANIME_GROUND_B;
        double[] tint = {PostMath.displayToLinear(r / 255f), PostMath.displayToLinear(g / 255f), PostMath.displayToLinear(b / 255f)};
        double tl = 0.2126 * tint[0] + 0.7152 * tint[1] + 0.0722 * tint[2];
        double l = 0.2126 * lit[0] + 0.7152 * lit[1] + 0.0722 * lit[2];
        double sat = space ? EndSkyMath.END_SPACE_GROUND_SATURATION : EndSkyMath.END_ANIME_GROUND_SATURATION;
        double exp = space ? EndSkyMath.END_SPACE_GROUND_EXPOSURE : EndSkyMath.END_ANIME_GROUND_EXPOSURE;
        double k = Math.max(0, Math.min(1, (blockLight - EndSkyMath.END_GROUND_BLOCK_FROM) / (EndSkyMath.END_GROUND_BLOCK_TO - EndSkyMath.END_GROUND_BLOCK_FROM)));
        k = k * k * (3 - 2 * k);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double graded = (l + (lit[i] - l) * sat) * tint[i] / tl * exp;
            out[i] = graded + (lit[i] - graded) * k;
        }
        return out;
    }

    /**
     * 2026-11-24 (the user: "엔드 지형이 하늘과 안 어울리게 탁해보여. 적절한 색감으로 튜닝해봐줘"): the end stone's olive green turns a cool navy
     * under the Space sky and a lavender pink under the Anime one, keeping its texture's light and dark; block-lit ground keeps its
     * colour; the composite grades the ambient-lit terrain with the End sky on.
     */
    @Test
    void theEndsGroundTakesItsSkysLight() {
        //End stone as lit (scene units: its pale olive)
        double[] stone = {PostMath.displayToLinear(0.86f), PostMath.displayToLinear(0.87f), PostMath.displayToLinear(0.64f)};
        double[] dim = {stone[0] * 0.5, stone[1] * 0.5, stone[2] * 0.5};
        double[] space = ground(stone, true, 0), anime = ground(stone, false, 0);
        assertTrue(space[2] > space[1] && space[1] > space[0], "Space: blue over green over red");
        assertTrue(anime[2] > anime[1] && anime[0] > anime[1], "Anime: lavender pink (red and blue over green)");
        double ls = 0.2126 * space[0] + 0.7152 * space[1] + 0.0722 * space[2];
        double lo = 0.2126 * stone[0] + 0.7152 * stone[1] + 0.0722 * stone[2];
        assertTrue(ls < 0.7 * lo && ls > 0.35 * lo, "moonlit: darker, not black");
        assertEquals(0.5, ground(dim, true, 0)[1] / space[1], 1e-6, "the texture's light and dark kept");
        double[] torch = ground(stone, true, 14);
        for (int i = 0; i < 3; i++) assertEquals(stone[i], torch[i], 1e-9, "block-lit ground keeps its colour");
        assertTrue(COPY.contains("lit = endGround(lit, endSkyParams.x, blockT);"));
        String f = END.substring(END.indexOf("vec3 endGround("));
        assertTrue(f.contains("if (!space && abs(style - END_SKY_ANIME) >= 0.5) {"), "vanilla's End sky: unchanged");
    }
}
