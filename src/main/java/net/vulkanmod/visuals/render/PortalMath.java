package net.vulkanmod.visuals.render;

/**
 * 2026-11-13 (shader roadmap session 19): the Java mirror of portal.glsl (nether portals' violet light and glow). Every const and function
 * there is here under the same name (DimensionEffectsTest compares them). Positions are camera-relative blocks {x, y, z}.
 */
public final class PortalMath {
    public static final float PORTAL_LEVEL = 11.0f;
    public static final float PORTAL_SHARE_SPAN = 2.0f;
    public static final float PORTAL_EDGE = 2.0f;
    public static final float PORTAL_R = 0.75f;
    public static final float PORTAL_G = 0.2f;
    public static final float PORTAL_B = 1.0f;
    public static final float PORTAL_LIGHT_GAIN = 0.5f;
    public static final float PORTAL_SURFACE = 0.05f;
    public static final float PORTAL_GLOW = 4.0f;
    public static final float PORTAL_PULSE = 0.15f;
    public static final float PORTAL_PULSE_SPEED = 2.4f;

    private PortalMath() {
    }

    /** portal.glsl portalDistance. */
    public static float portalDistance(float[] pos, float[] lo, float[] hi) {
        float d = 0;
        for (int i = 0; i < 3; i++) d += Math.max(Math.max(lo[i] - pos[i], pos[i] - hi[i]), 0);
        return d;
    }

    /** portal.glsl portalShare. */
    public static float portalShare(float[] pos, float block, float[] lo, float[] hi) {
        float expected = PORTAL_LEVEL - portalDistance(pos, lo, hi);
        if (!(expected > 0) || !(block > 0)) return 0;
        return clamp((expected + PORTAL_SHARE_SPAN - block) / PORTAL_SHARE_SPAN) * clamp(expected / PORTAL_EDGE);
    }

    /** portal.glsl portalGlowGain. */
    public static float portalGlowGain(float[] worldPos, float t, float strength) {
        float phase = (float) (Math.floor(worldPos[0]) * 0.37 + Math.floor(worldPos[1]) * 0.61 + Math.floor(worldPos[2]) * 0.23);
        return 1 + PORTAL_GLOW * strength * (1 - PORTAL_PULSE + PORTAL_PULSE * (float) Math.sin(t * PORTAL_PULSE_SPEED + phase));
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
