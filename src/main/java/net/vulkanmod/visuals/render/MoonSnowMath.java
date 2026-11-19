package net.vulkanmod.visuals.render;

import org.joml.Vector3f;

/**
 * 2026-11-18 (the user, with Complementary-family screenshots as pictures only: "the snow lit by the moon glows softly; can the Vulkan
 * side do something like it without the scene getting darker?"): snowglow.glsl's moonlit snow, mirrored. Snow-white ground under the moon
 * takes a factor of at least 1 on its lit colour (nothing gets darker): a broad sheen where it faces the moon, strongest where the view
 * reflects toward it, a little in the shade, cool lavender, all scaled by the moon's weight (MoonShadowMath.weight), so none by day or at
 * new moon. The composite (scene_copy.comp) applies it at night (slot 10 .w negative) with weight = slot 10 .y x the sky light.
 */
public final class MoonSnowMath {
    public static final float WHITE_LOW = 0.62f, WHITE_HIGH = 0.8f, LIGHTMAP_MIN = 0.02f;
    public static final float GLOW_DIRECT = 0.4f, GLOW_AMBIENT = 0.12f, GLOW_SHEEN = 1.6f, SHEEN_POWER = 3;
    public static final float[] GLOW_TINT = {0.86f, 0.84f, 1.0f};

    private MoonSnowMath() {
    }

    /** How much of a texel (display albedo) is snow-white: its darkest channel between WHITE_LOW and WHITE_HIGH. */
    public static float whiteness(float r, float g, float b) {
        return smoothstep(WHITE_LOW, WHITE_HIGH, Math.min(r, Math.min(g, b)));
    }

    /**
     * The factor per channel (each at least 1) on the lit colour: albedo display, n the world normal (unit), dir camera to surface (unit),
     * moonDir toward the moon (unit), directShare the moon's direct share 0..1, weight the moon's (0..1).
     */
    public static float[] glow(float[] albedo, Vector3f n, Vector3f dir, Vector3f moonDir, float directShare, float weight) {
        float w = clamp01(weight) * whiteness(albedo[0], albedo[1], albedo[2]);
        if (!(w > 0)) return new float[]{1, 1, 1};
        //reflect(dir, n) = dir - 2 dot(n, dir) n
        float nd = n.dot(dir);
        Vector3f r = new Vector3f(dir).sub(new Vector3f(n).mul(2 * nd));
        float lobe = (float) Math.pow(clamp01(r.dot(moonDir)), SHEEN_POWER);
        float k = w * (GLOW_AMBIENT + GLOW_DIRECT * clamp01(directShare) * (1 + GLOW_SHEEN * lobe));
        return new float[]{1 + GLOW_TINT[0] * k, 1 + GLOW_TINT[1] * k, 1 + GLOW_TINT[2] * k};
    }

    private static float clamp01(float v) {
        return v > 0 ? Math.min(v, 1) : 0;
    }

    private static float smoothstep(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }
}
