package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-25 (the user: "사진을 참고해서 애니메이션 구름의 수채화 색감을 더 살려줘", after a Genshin Impact daytime scene, looked at only):
 * the anime clouds' colour washes (CloudMath.animeWash): warm cream on the lit tops, pink and lavender through the bodies, blue-violet in
 * the shade, warmer toward the sun, the edges thinning to dusty pink and lilac, gentler at sunset and at night.
 */
class AnimeWashTest {
    private static final byte[] MAP = CloudMath.withNoise(new byte[CloudMath.MAP * CloudMath.MAP * 4]);
    private static final float[] UP = {0, 1, 0};

    private static float[] vec3(String text, String name) {
        Matcher m = Pattern.compile("const\\s+vec3\\s+" + name + "\\s*=\\s*vec3\\(([^)]*)\\)\\s*;").matcher(text);
        assertTrue(m.find(), name + " in clouds.glsl");
        String[] p = m.group(1).split(",");
        return new float[]{Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()), Float.parseFloat(p[2].trim())};
    }

    private static float luma(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    @Test
    void theShaderMatchesTheMirror() throws Exception {
        String clouds = ShaderFiles.read("post/clouds.glsl");
        assertArrayEquals(CloudMath.ANIME_WASH_DEEP, vec3(clouds, "ANIME_WASH_DEEP"), 0f);
        assertArrayEquals(CloudMath.ANIME_WASH_SHADE, vec3(clouds, "ANIME_WASH_SHADE"), 0f);
        assertArrayEquals(CloudMath.ANIME_WASH_MID, vec3(clouds, "ANIME_WASH_MID"), 0f);
        assertArrayEquals(CloudMath.ANIME_WASH_LIT, vec3(clouds, "ANIME_WASH_LIT"), 0f);
        assertTrue(clouds.contains("l.x += (cloudNoise((cloudOffset.xy + p.xz)*ANIME_WASH_SCALE).a - 0.5)*ANIME_WASH_VARY;"));
        assertEquals(Math.round(CloudMath.ANIME_WASH_SCALE), CloudMath.ANIME_WASH_SCALE, 0f, "a whole number: the map's wrap never shows");
        //the composite and the reflection pass the wash to cloudOver, which turns the colour to it before the haze
        assertTrue(ShaderFiles.read("post/scene_copy.comp").contains("animeWash(m, dir, a.sun.xyz, nightClouds(a))"));
        String reflection = ShaderFiles.read("post/reflection.comp");
        assertTrue(reflection.contains("animeWash(m, r, a.sun.xyz, nightClouds(a))"));
        assertTrue(!reflection.contains("cloudOver(a, CloudLight(cloudLit, cloudAmbient, cloudLightDir), reflectedCloudMarch("), "every reflected cloud washed");
        assertTrue(ShaderFiles.read("post/atmosphere.glsl").contains("cloud.rgb = mix(cloud.rgb, luminance(cloud.rgb)*wash.rgb, wash.a);"));
    }

    /** The hues keep the cloud's brightness (luminance 1) and run warm to cool: cream, pink, lavender, blue-violet. */
    @Test
    void theRampRunsFromCreamThroughPinkToBlueViolet() {
        for (float[] c : new float[][]{CloudMath.ANIME_WASH_DEEP, CloudMath.ANIME_WASH_SHADE, CloudMath.ANIME_WASH_MID, CloudMath.ANIME_WASH_LIT}) {
            assertEquals(1, luma(c), 0.03f);
        }
        float[] lit = CloudMath.animeWashHue(1), mid = CloudMath.animeWashHue(CloudMath.ANIME_WASH_MID_AT), deep = CloudMath.animeWashHue(0);
        assertTrue(lit[0] > lit[2], "the lit top is warm");
        assertTrue(mid[0] > mid[1] && mid[2] > mid[1], "the mid-tone is pink-mauve");
        assertTrue(deep[2] > deep[0] * 1.5f, "the shade is blue-violet");
        //no hard steps: neighbouring places differ little
        for (float s = 0; s < 1; s += 0.01f) {
            float[] a = CloudMath.animeWashHue(s), b = CloudMath.animeWashHue(s + 0.01f);
            for (int i = 0; i < 3; i++) assertEquals(a[i], b[i], 0.03f, "at " + s);
        }
    }

    @Test
    void lightHeightSunSideAndEdges() {
        float[] sun = {0.8f, 0.5f, 0}, toward = {1, 0.2f, 0}, away = {-1, 0.2f, 0};
        float litTop = CloudMath.animeWashTone(new float[]{1, 1, 1, 0}, toward, sun);
        float shadedBase = CloudMath.animeWashTone(new float[]{CloudMath.ANIME_TONE_DARK, CloudMath.ANIME_AMBIENT_LOW, 1, 0}, away, sun);
        assertTrue(litTop > 0.95f && shadedBase < 0.1f, litTop + " " + shadedBase);
        float[] body = {0.85f, 0.8f, 1, 0};
        assertTrue(CloudMath.animeWashTone(body, toward, sun) > CloudMath.animeWashTone(body, away, sun), "warmer toward the sun");
        //thin cloud moves toward the edge's tone
        float[] thin = {0.85f * 0.2f, 0.8f * 0.2f, 0.2f, 0};
        assertTrue(Math.abs(CloudMath.animeWashTone(thin, toward, sun) - CloudMath.ANIME_WASH_EDGE)
                < Math.abs(CloudMath.animeWashTone(body, toward, sun) - CloudMath.ANIME_WASH_EDGE));
        //full by day, gentler at sunset and at night; nothing without cloud
        assertEquals(CloudMath.ANIME_WASH, CloudMath.animeWash(body, toward, sun, 0)[3], 1e-6f);
        assertEquals(CloudMath.ANIME_WASH * CloudMath.ANIME_WASH_DUSK, CloudMath.animeWash(body, toward, new float[]{1, 0, 0}, 0)[3], 1e-6f);
        assertEquals(CloudMath.ANIME_WASH * CloudMath.ANIME_WASH_NIGHT, CloudMath.animeWash(body, toward, new float[]{1, -0.5f, 0}, 1)[3], 1e-6f);
        assertArrayEquals(new float[4], CloudMath.animeWash(new float[4], toward, sun, 0), 0f);
        //the shade darker than the lit top, both more colourful than the hue (the tone mapping drains bright colour)
        float[] top = CloudMath.animeWash(new float[]{1, 1, 1, 0}, toward, sun, 0);
        float[] base = CloudMath.animeWash(new float[]{CloudMath.ANIME_TONE_DARK, CloudMath.ANIME_AMBIENT_LOW, 1, 0}, away, sun, 0);
        assertTrue(luma(base) < 0.75f * luma(top), luma(base) + " " + luma(top));
        assertTrue(base[2] / base[0] > CloudMath.animeWashHue(0)[2] / CloudMath.animeWashHue(0)[0], "the shade's blue stronger than the hue's");
    }

    /** Toward the horizon at noon the clouds take every part of the ramp: shaded blue-violet, pink and lavender bodies, cream tops. */
    @Test
    void theWashesVaryThroughTheClouds() {
        CloudMath.Realistic r = new CloudMath.Realistic(200, CloudMath.ANIME_THICKNESS, 8192, 1000, 1500, CloudMath.REAL_COVER);
        float[] sun = {0.3f, 0.9f, 0.3f};
        float len = (float) Math.sqrt(0.18f + 0.81f);
        sun = new float[]{sun[0] / len, sun[1] / len, sun[2] / len};
        int shade = 0, mid = 0, lit = 0, n = 0;
        for (int i = 0; i < 3000; i++) {
            double az = i * 0.0317, el = Math.toRadians(3 + (i % 9) * 2);
            float[] d = {(float) (Math.cos(el) * Math.sin(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.cos(az))};
            float[] m = CloudMath.animeMarch(MAP, r, new float[3], d, 1e6f, sun, 0.5f, CloudMath.ANIME_SAMPLES_HIGH);
            if (!(m[2] > 0.5f)) continue;
            float s = CloudMath.animeWashTone(m, d, sun);
            if (s < CloudMath.ANIME_WASH_SHADE_AT) shade++;
            else if (s < CloudMath.ANIME_WASH_MID_AT) mid++;
            else lit++;
            n++;
        }
        assertTrue(n > 100, "clouds hit: " + n);
        assertTrue(shade > n / 10 && mid > n / 10 && lit > n / 10, "shade " + shade + ", mid " + mid + ", lit " + lit + " of " + n);
    }
}
