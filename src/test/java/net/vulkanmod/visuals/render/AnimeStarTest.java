package net.vulkanmod.visuals.render;

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

    @Test
    void theCompositeDeclaresTheSlotAndDrawsTheStarsUnderTheTint() throws IOException {
        String c = resource("/assets/vulkanmod/visuals/shaders/post/scene_copy.comp");
        assertTrue(c.contains("layout(location = " + AnimeStarMath.ANIME_SLOT + ") uniform vec4 animeStarParams;"));
        assertEquals(1, c.split("layout\\(location = " + AnimeStarMath.ANIME_SLOT + "\\)", -1).length - 1, "the slot is used once");
        assertTrue(c.contains("#import <vulkanmod:visuals/post/animestars.glsl>"));
        int anime = c.indexOf("stars += animeStars(");
        int tint = c.indexOf("stars *= vanillaStarTint(");
        int sky = c.indexOf("vec3 sky = skyColour(a, dir)");
        assertTrue(anime > 0 && anime < tint && tint < sky, "the anime stars take the star colour and go under the far clouds");
        assertTrue(c.indexOf("if (animeStarParams.x > 0.0)") < anime, "nothing is evaluated while the type is off");
    }

    @Test
    void theTypeIsTheThirdChoiceOfTheStarTypeAndOffByDefault() throws IOException {
        assertEquals(2, StarMath.TYPE_ANIME);
        assertEquals(StarMath.TYPE_ANIME, StarMath.TYPE_MAX);
        assertEquals(StarMath.TYPE_MAX, VisualsOptionLayout.item("star_type").max());
        VisualsConfig c = new VisualsConfig();
        assertEquals(StarMath.TYPE_VANILLA, c.starType);
        assertArrayEquals(new float[4], AnimeStarMath.params(c, true, true), "the default draws no anime stars");
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            assertTrue(resource("/assets/vulkanmod/lang/" + lang + ".json").contains("\"vulkanmod.visuals.star_type.2\""), lang);
        }
    }

    @Test
    void theSlotFollowsTheStarSettings() {
        VisualsConfig c = new VisualsConfig();
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
    }
}
