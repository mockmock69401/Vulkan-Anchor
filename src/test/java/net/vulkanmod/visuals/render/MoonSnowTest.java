package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-18: the moonlit snow's glow (MoonSnowMath, snowglow.glsl). */
class MoonSnowTest {
    private static final String GLSL = ShaderFiles.read("post/snowglow.glsl");
    private static final float[] SNOW = {0.96f, 0.98f, 0.99f}, GRASS = {0.45f, 0.6f, 0.3f}, STONE = {0.5f, 0.5f, 0.5f}, ICE = {0.55f, 0.7f, 0.95f};
    private static final Vector3f UP = new Vector3f(0, 1, 0);
    private static final float MIDNIGHT = 0.5f, EVENING = 0.354f;
    private static final float FULL_MOON = MoonShadowMath.strength(MoonShadowMath.MOONLIGHT_DEFAULT, 0);

    private static float glsl(String name) {
        Matcher m = Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*([-0-9.]+)\\s*;").matcher(GLSL);
        assertTrue(m.find(), name + " missing in snowglow.glsl");
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theShaderAgreesWithTheMirror() {
        assertEquals(MoonSnowMath.WHITE_LOW, glsl("SNOW_WHITE_LOW"));
        assertEquals(MoonSnowMath.WHITE_HIGH, glsl("SNOW_WHITE_HIGH"));
        assertEquals(MoonSnowMath.LIGHTMAP_MIN, glsl("SNOW_LIGHTMAP_MIN"));
        assertEquals(MoonSnowMath.GLOW_DIRECT, glsl("SNOW_GLOW_DIRECT"));
        assertEquals(MoonSnowMath.GLOW_AMBIENT, glsl("SNOW_GLOW_AMBIENT"));
        assertEquals(MoonSnowMath.GLOW_SHEEN, glsl("SNOW_GLOW_SHEEN"));
        assertEquals(MoonSnowMath.SHEEN_POWER, glsl("SNOW_SHEEN_POWER"));
        Matcher m = Pattern.compile("const\\s+vec3\\s+SNOW_GLOW_TINT\\s*=\\s*vec3\\(([-0-9.]+),\\s*([-0-9.]+),\\s*([-0-9.]+)\\)").matcher(GLSL);
        assertTrue(m.find());
        for (int i = 0; i < 3; i++) assertEquals(MoonSnowMath.GLOW_TINT[i], Float.parseFloat(m.group(i + 1)));
        //the composite takes it at night, by the moon's weight times the sky light
        String copy = ShaderFiles.read("post/scene_copy.comp");
        assertTrue(copy.contains("#import <vulkanmod:visuals/post/snowglow.glsl>"));
        assertTrue(copy.contains("lighting.w < 0.0 && lighting.y > 0.0"));
        assertTrue(copy.contains("moonSnowGlow(base, normalWorld, dir, -a.sun.xyz, directShare, lighting.y*skyLight)"));
    }

    /** The user's condition: nothing gets darker. Every channel's factor is at least 1, for any surface, view and light. */
    @Test
    void nothingGetsDarker() {
        float[][] albedos = {SNOW, GRASS, STONE, ICE, {1, 1, 1}, {0, 0, 0}};
        Vector3f[] normals = {UP, new Vector3f(1, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0.6f, 0.8f, 0)};
        Vector3f moon = MoonShadowMath.lightDirection(EVENING);
        for (float[] a : albedos) {
            for (Vector3f n : normals) {
                for (float pitch = -1.4f; pitch < 1.5f; pitch += 0.35f) {
                    for (float yaw = 0; yaw < 6.3f; yaw += 0.7f) {
                        Vector3f dir = new Vector3f((float) (Math.cos(pitch) * Math.cos(yaw)), (float) Math.sin(pitch),
                                (float) (Math.cos(pitch) * Math.sin(yaw)));
                        for (float d = 0; d <= 1; d += 0.5f) {
                            for (float w : new float[]{0, 0.3f, FULL_MOON, 1, 2, -1, Float.NaN}) {
                                for (float f : MoonSnowMath.glow(a, n, dir, moon, d, w)) assertTrue(f >= 1, "factor " + f);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void onlySnowWhiteGlows() {
        assertEquals(1f, MoonSnowMath.whiteness(SNOW[0], SNOW[1], SNOW[2]));
        assertEquals(0f, MoonSnowMath.whiteness(GRASS[0], GRASS[1], GRASS[2]));
        assertEquals(0f, MoonSnowMath.whiteness(STONE[0], STONE[1], STONE[2]));
        assertEquals(0f, MoonSnowMath.whiteness(ICE[0], ICE[1], ICE[2]));
        Vector3f moon = MoonShadowMath.lightDirection(MIDNIGHT), dir = new Vector3f(0, -0.5f, 1).normalize();
        for (float f : MoonSnowMath.glow(GRASS, UP, dir, moon, 1, FULL_MOON)) assertEquals(1f, f);
        for (float f : MoonSnowMath.glow(SNOW, UP, dir, moon, 1, 0)) assertEquals(1f, f);
    }

    /** Soft and broad: brightest where the view reflects toward the moon, falling off smoothly; the moon's shadows stay on it; lavender. */
    @Test
    void aSoftSheenTowardTheMoon() {
        Vector3f moon = MoonShadowMath.lightDirection(EVENING);
        Vector3f toward = new Vector3f(moon.x, -moon.y, moon.z).normalize(), away = new Vector3f(-moon.x, -moon.y, -moon.z).normalize();
        float[] lit = MoonSnowMath.glow(SNOW, UP, toward, moon, 1, FULL_MOON);
        float[] back = MoonSnowMath.glow(SNOW, UP, away, moon, 1, FULL_MOON);
        float[] shade = MoonSnowMath.glow(SNOW, UP, toward, moon, 0, FULL_MOON);
        assertTrue(lit[2] > back[2] + 0.3f, "toward the moon " + lit[2] + " away " + back[2]);
        assertTrue(back[2] > shade[2], "the lit side over the shade");
        assertTrue(shade[2] > 1 && shade[2] < 1.15f, "a little in the shade " + shade[2]);
        assertTrue(lit[2] < 2, "a glow, not a highlight " + lit[2]);
        //Smooth: the factor changes little as the view turns
        float last = Float.NaN;
        for (float yaw = 0; yaw < 6.3f; yaw += 0.02f) {
            Vector3f dir = new Vector3f((float) Math.cos(yaw), -0.6f, (float) Math.sin(yaw)).normalize();
            float f = MoonSnowMath.glow(SNOW, UP, dir, moon, 1, FULL_MOON)[2];
            if (!Float.isNaN(last)) assertTrue(Math.abs(f - last) < 0.02f);
            last = f;
        }
        //Cool lavender: blue most, red over green
        assertTrue(lit[2] > lit[0] && lit[0] > lit[1]);
    }

    /** It follows the moon's weight: none by day, at new moon or at the horizon, so it fades with the moon's shadows at dusk and dawn. */
    @Test
    void itFollowsTheMoon() {
        Vector3f dir = new Vector3f(0, -0.5f, 1).normalize(), moon = MoonShadowMath.lightDirection(MIDNIGHT);
        float full = MoonSnowMath.glow(SNOW, UP, dir, moon, 1, MoonShadowMath.weight(MIDNIGHT, 0, FULL_MOON))[2];
        float half = MoonSnowMath.glow(SNOW, UP, dir, moon, 1, MoonShadowMath.weight(MIDNIGHT, 0, MoonShadowMath.strength(0.5f, 2)))[2];
        float none = MoonSnowMath.glow(SNOW, UP, dir, moon, 1, MoonShadowMath.weight(MIDNIGHT, 0, MoonShadowMath.strength(0.5f, 4)))[2];
        assertTrue(full > half && half > none);
        assertEquals(1f, none);
        assertEquals(1f, MoonSnowMath.glow(SNOW, UP, dir, moon, 1, MoonShadowMath.weight(0.25f, 0, FULL_MOON))[2], 1e-3f);
    }
}
