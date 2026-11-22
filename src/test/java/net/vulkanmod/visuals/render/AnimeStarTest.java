package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.PostOn;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Session 12 follow-up: the anime star type (StarMath.TYPE_ANIME, AnimeStarMath, AnimeSkyMap, animestars.glsl). */
class AnimeStarTest {
    private static final float EPS = 1e-5f;

    private static String resource(String path) throws IOException {
        try (InputStream in = AnimeStarTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String glsl() throws IOException {
        return resource("/assets/vulkanmod/visuals/shaders/post/animestars.glsl");
    }

    @Test
    void everyConstMatchesTheJavaMirror() throws Exception {
        String g = glsl();
        Matcher m = Pattern.compile("const float (ANIME_[A-Z_]+) = ([-0-9.]+);").matcher(g);
        int n = 0;
        while (m.find()) {
            float v = Float.parseFloat(m.group(2));
            if (m.group(1).equals("ANIME_MAP_SCALE")) {
                assertEquals(AnimeSkyMap.ANIME_MAP_SCALE, v, EPS);
            } else {
                Field f = AnimeStarMath.class.getField(m.group(1));
                assertEquals(f.getFloat(null), v, EPS, m.group(1));
            }
            n++;
        }
        assertTrue(n > 30, "consts found: " + n);
        //and every float const of the mirror is in the shader
        for (Field f : AnimeStarMath.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == float.class) {
                assertTrue(g.contains("const float " + f.getName() + " = "), f.getName());
            }
        }
    }

    /** 2026-11-17: each layer's grid is turned its own way by a rotation (orthonormal, determinant 1), and no two turn alike. */
    @Test
    void eachLayersGridIsTurnedByItsOwnRotation() throws IOException {
        Matcher m = Pattern.compile("const mat3 (ANIME_TURN_[A-Z]+) = mat3\\(([^)]*)\\);").matcher(glsl());
        java.util.List<double[]> turns = new java.util.ArrayList<>();
        while (m.find()) {
            String[] v = m.group(2).split(",");
            assertEquals(9, v.length, m.group(1));
            double[] r = new double[9];
            for (int i = 0; i < 9; i++) r[i] = Double.parseDouble(v[i].trim());
            //columns c0 = r[0..2], c1 = r[3..5], c2 = r[6..8]
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    double dot = r[3 * a] * r[3 * b] + r[3 * a + 1] * r[3 * b + 1] + r[3 * a + 2] * r[3 * b + 2];
                    assertEquals(a == b ? 1 : 0, dot, 1e-5, m.group(1) + " columns " + a + ", " + b);
                }
            }
            double det = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[3] * (r[1] * r[8] - r[2] * r[7]) + r[6] * (r[1] * r[5] - r[2] * r[4]);
            assertEquals(1, det, 1e-5, m.group(1));
            for (double[] o : turns) assertFalse(java.util.Arrays.equals(o, r), "the layers turn differently");
            turns.add(r);
        }
        assertEquals(3, turns.size(), "dust, small and big");
        String g = glsl();
        for (String layer : new String[]{"DUST", "SMALL", "BIG"}) assertTrue(g.contains("animeStarLayer(skyDir, ANIME_TURN_" + layer + ", "), layer);
        //a star anywhere in its cell: no inset, and the four cells around the nearest corner
        assertFalse(g.contains("INSET"));
        assertTrue(g.contains("vec2 base = floor(f - 0.5);"));
        //whether a star is there is read from the map at the star, so all of its pixels agree (not cut along the map's texels)
        assertTrue(g.contains("textureLod(galaxyMap, galaxyMapCoord(sd*turn), 0.0)"));
        assertFalse(g.contains("mapTexel.b"), "no chance from the pixel's own texel");
    }

    @Test
    void theCompositeDeclaresTheSlotAndDrawsTheStarsUnderTheTint() throws IOException {
        String c = resource("/assets/vulkanmod/visuals/shaders/post/scene_copy.comp");
        assertTrue(c.contains("layout(location = " + AnimeStarMath.ANIME_SLOT + ") uniform vec4 animeStarParams;"));
        assertEquals(1, c.split("layout\\(location = " + AnimeStarMath.ANIME_SLOT + "\\)", -1).length - 1, "the slot is used once");
        assertTrue(c.contains("#import <vulkanmod:visuals/post/animestars.glsl>"));
        int anime = c.indexOf("vec3 anime = animeStars(");
        int tint = c.indexOf("stars *= vanillaStarTint(");
        int animeTint = c.indexOf("anime *= starTint.rgb;");
        int added = c.indexOf("stars += anime;");
        int sky = c.indexOf("vec3 sky = skyBelow(a, dir, skyColour(a, dir), sunGlow(a, dir))");
        //2026-11-17 (session 15 follow-up): the plain star colour, not vanillaStarTint's (its white square around vanilla's moon showed on the band)
        assertTrue(tint > 0 && tint < anime && anime < animeTint && animeTint < added && added < sky,
                "the anime stars take the star colour and go under the far clouds");
        assertTrue(c.indexOf("if (animeStarParams.x > 0.0)") < anime, "nothing is evaluated while the type is off");
    }

    @Test
    void theTypeIsTheThirdChoiceOfTheStarTypeAndOffByDefault() throws IOException {
        assertEquals(2, StarMath.TYPE_ANIME);
        assertEquals(StarMath.TYPE_ANIME, StarMath.TYPE_MAX);
        assertEquals(StarMath.TYPE_MAX, VisualsOptionLayout.item("star_type").max());
        VisualsConfig c = PostOn.defaults();
        assertEquals(StarMath.TYPE_VANILLA, c.starType);
        assertArrayEquals(new float[4], AnimeStarMath.params(c, true, true), "the default draws no anime stars");
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            assertTrue(resource("/assets/vulkanmod/lang/" + lang + ".json").contains("\"vulkanmod.visuals.star_type.2\""), lang);
        }
    }

    @Test
    void theSlotFollowsTheStarSettings() {
        VisualsConfig c = PostOn.defaults();
        c.starType = StarMath.TYPE_ANIME;
        assertArrayEquals(new float[]{1, 1, 1, 1}, AnimeStarMath.params(c, true, true), EPS);
        assertArrayEquals(new float[]{1, 1, 1, 0}, AnimeStarMath.params(c, true, false), EPS, "the stars alone until the map is up");
        assertArrayEquals(new float[4], AnimeStarMath.params(c, false, true), "no sky drawn (another dimension's): none");
        assertArrayEquals(new float[4], AnimeStarMath.params(null, true, true), "the post-process off: none");
        c.starCount = 250;
        c.starSize = 60;
        c.starBrightness = 180;
        assertArrayEquals(new float[]{1.8f, 2.5f, 0.6f, 1}, AnimeStarMath.params(c, true, true), EPS);
        c.starBrightness = 0;
        assertArrayEquals(new float[4], AnimeStarMath.params(c, true, true), "brightness 0: no stars");
        c.starBrightness = 100;
        c.enabled = false;
        assertArrayEquals(new float[4], AnimeStarMath.params(c, true, true));
    }

    @Test
    void theStarsTurnWithTheSunAndFadeAtTheHorizon() {
        float[] sun = {0.6f, -0.8f, 0};
        float[] up = AnimeStarMath.skyDir(sun, sun[0], sun[1]);
        assertArrayEquals(new float[]{0, 1, 0}, up, EPS);
        //midnight (the sun straight down): the frame AnimeSkyMap bakes in
        assertArrayEquals(AnimeSkyMap.toSky(new float[]{0.3f, 0.5f, 0.81f}), AnimeStarMath.skyDir(new float[]{0.3f, 0.5f, 0.81f}, 0, -1), EPS);
        assertEquals(0, AnimeStarMath.horizon(0), EPS);
        assertEquals(1, AnimeStarMath.horizon(AnimeStarMath.ANIME_HORIZON_HIGH), EPS);
        assertTrue(AnimeStarMath.horizon(0.1f) > 0 && AnimeStarMath.horizon(0.1f) < 1);
        assertEquals(AnimeStarMath.ANIME_SIZE_A, AnimeStarMath.sizeClass(0.1f));
        assertEquals(AnimeStarMath.ANIME_SIZE_B, AnimeStarMath.sizeClass(0.7f));
        assertEquals(AnimeStarMath.ANIME_SIZE_C, AnimeStarMath.sizeClass(0.95f));
    }

    @Test
    void theBandClimbsTheMidnightSkyWithGapsAndDust() {
        byte[] a = AnimeSkyMap.bake(256, 128);
        assertArrayEquals(a, AnimeSkyMap.bake(256, 128), "the same map every time");
        //along the band's highest point at midnight (about 65 degrees up) it glows; at its pole it does not
        float[] pole = {AnimeSkyMap.POLE_X, AnimeSkyMap.POLE_Y, AnimeSkyMap.POLE_Z};
        float l = (float) Math.sqrt(pole[0] * pole[0] + pole[1] * pole[1] + pole[2] * pole[2]);
        for (int i = 0; i < 3; i++) pole[i] /= l;
        float[] top = {-pole[1] * pole[0], 1 - pole[1] * pole[1], -pole[1] * pole[2]};
        float t = (float) Math.sqrt(top[0] * top[0] + top[1] * top[1] + top[2] * top[2]);
        for (int i = 0; i < 3; i++) top[i] /= t;
        assertTrue(Math.toDegrees(Math.asin(top[1])) > 55, "the band climbs high");
        float[] on = AnimeSkyMap.texel(AnimeSkyMap.toSky(top));
        float[] off = AnimeSkyMap.texel(AnimeSkyMap.toSky(pole));
        assertTrue(on[1] > 0.05f || on[0] > 0.1f, "glow or dust on the band");
        assertTrue(off[0] < 0.06f && off[1] == 0, "nothing at its pole but the faint haze");
        //over the map: the glow fits its scale mostly, the dust is 0..1, and there are gaps (texels near the band darker than around)
        int bright = 0, dusty = 0;
        for (int i = 0; i < a.length; i += 4) {
            if ((a[i] & 0xFF) > 120) bright++;
            if ((a[i + 1] & 0xFF) > 60) dusty++;
        }
        assertTrue(bright > 100 && dusty > 300, bright + " / " + dusty);
        assertTrue(bright < a.length / 4 / 4, "the band is a band, not the sky");
        //2026-11-17: the clump field covers the whole sky, with thick and thin places (about half each way)
        int n = a.length / 4, thin = 0, thick = 0;
        double sum = 0;
        for (int i = 0; i < a.length; i += 4) {
            int b = a[i + 2] & 0xFF;
            sum += b / 255.0;
            if (b < 40) thin++;
            if (b > 215) thick++;
        }
        assertTrue(sum / n > 0.3 && sum / n < 0.7, "mean " + sum / n);
        assertTrue(thin > n / 10 && thick > n / 10, thin + " thin, " + thick + " thick of " + n);
        float[] p = AnimeSkyMap.texel(AnimeSkyMap.toSky(pole));
        assertEquals(3, p.length, "off the band too");
    }

    //dir at (x, y) in the disc plane of centre (water.glsl discPlane's axes)
    private static float[] onDisc(float[] centre, float x, float y) {
        float[] axis = {-centre[0] * centre[2], -centre[1] * centre[2], 1 - centre[2] * centre[2]};
        float al = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        for (int i = 0; i < 3; i++) axis[i] /= al;
        float[] side = {axis[1] * centre[2] - axis[2] * centre[1], axis[2] * centre[0] - axis[0] * centre[2], axis[0] * centre[1] - axis[1] * centre[0]};
        float[] d = new float[3];
        for (int i = 0; i < 3; i++) d[i] = centre[i] + x * axis[i] + y * side[i];
        float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        for (int i = 0; i < 3; i++) d[i] /= l;
        return d;
    }

    /**
     * 2026-11-17 (session 15 follow-up, the user: "the round moon's picture is drawn with its background"): the band and the stars were cut
     * out of a square 1.25 x the moon around it, and the band's missing teal showed as a dark square. Now the round moon's disc hides them
     * (a circle, not a square), the stars stay a little off its limb, and the band goes on behind vanilla's additive square moon.
     */
    @Test
    void onlyTheMoonsDiscHidesTheBand() throws IOException {
        float[] moon = {0.3f, 0.8f, -0.52f};
        float l = (float) Math.sqrt(moon[0] * moon[0] + moon[1] * moon[1] + moon[2] * moon[2]);
        for (int i = 0; i < 3; i++) moon[i] /= l;
        for (float size : new float[]{0.25f, 1, 2}) {
            float[] round = {WaterMath.CELESTIAL_ROUND, 0, 1, size};
            float r = WaterMath.MOON_ROUND_RADIUS * size;
            assertArrayEquals(new float[]{0, 0}, AnimeStarMath.moonKeep(moon, moon, round), EPS, "behind the moon: nothing");
            //the old square's corner (1.1 r on both axes: outside the disc, inside the square of 1.25 r): all of it now
            assertArrayEquals(new float[]{1, 1}, AnimeStarMath.moonKeep(onDisc(moon, 1.1f * r, -1.1f * r), moon, round), EPS, "size " + size);
            //past the margin: both
            assertArrayEquals(new float[]{1, 1}, AnimeStarMath.moonKeep(onDisc(moon, -1.6f * r, 0), moon, round), EPS);
            if (size >= 1) {
                //just off the limb: the band, no stars yet
                float[] near = AnimeStarMath.moonKeep(onDisc(moon, 0, 1.12f * r), moon, round);
                assertEquals(1, near[0], EPS);
                assertEquals(0, near[1], EPS);
            }
            //round: all of the band just past the disc's soft edge at every angle around it (no square)
            double out = r + 2.5 * WaterMath.DISC_EDGE;
            for (int k = 0; k < 16; k++) {
                double t = k * Math.PI / 8;
                float[] keep = AnimeStarMath.moonKeep(onDisc(moon, (float) (out * Math.cos(t)), (float) (out * Math.sin(t))), moon, round);
                assertEquals(1, keep[0], EPS, "angle " + k);
            }
        }
        float[] square = {0, 0, 1, 1};
        float[] centre = AnimeStarMath.moonKeep(moon, moon, square);
        assertEquals(1, centre[0], EPS, "the band goes on behind vanilla's additive square moon");
        assertEquals(0, centre[1], EPS, "no stars over it");
        assertArrayEquals(new float[]{1, 1}, AnimeStarMath.moonKeep(onDisc(moon, 0.08f, 0.08f), moon, square), EPS);
        assertArrayEquals(new float[]{1, 1}, AnimeStarMath.moonKeep(new float[]{-moon[0], -moon[1], -moon[2]}, moon, square), EPS, "the other side");
        //the shader: the band by its own keep, no square cut of the whole light
        String g = glsl();
        assertTrue(g.contains("vec2 animeMoonKeep(vec3 dir, vec3 moon, vec4 celestial)"));
        assertTrue(g.contains("glow*moonKeep.x") && g.contains("ANIME_STAR_BRIGHTNESS*moonKeep.y"));
        assertFalse(g.contains("max(VANILLA_MOON_MASK, MOON_ROUND_RADIUS"), "the old square around the round moon is gone");
    }
}
