package net.vulkanmod.visuals.render;

import org.joml.Vector3f;

/**
 * 2026-11-24 (the user: "엔드에서 가장 밝은 별로 인한 빛 그림자도 구현해줘. 달빛그림자 처럼 진하게 할 필요는 없지만 코러스 식물 등에 그림자가
 * 비쳐져야 해. 엔드는 시간 개념이 없으니 그림자를 구현할 때 성능 비용을 잘 고려해줘"): the Anime End sky's bright star (endsky.glsl END_STAR_*)
 * casts soft shadows. The sun's shadow maps (VkShadowMaps) are drawn from its fixed direction instead of the sun's, and lighting.comp
 * looks them up as for the sun; the composite's End branch darkens what the star does not reach by SHADOW (endsky.glsl endStarShade),
 * the composite's sun shading staying off (LightingUniforms.STAR_ONLY).
 * <p>The light never moves, so the maps are only drawn again when the camera has moved away (each cascade's own reach) or, for blocks
 * placed or broken and chunks loading, every REFRESH_NANOS (the close and near maps) or FAR_REFRESH_NANOS (the far one), instead of
 * every few frames as for the turning sun. Entities do not cast (their map is drawn every frame); vanilla's round shadow stays under
 * them. With the Space sky there is no bright star, and with Shadows off there are no maps: no star shadows.
 */
public final class EndStarLight {
    /** How much of the light the star's shadow takes (the moon's: MoonShadowMath.MOON_SHADOW, the sun's: all of the direct light). */
    public static final float SHADOW = 0.4f;
    /** The close and near maps' refresh while the camera stays (blocks placed or broken, chunks loaded). */
    public static final long REFRESH_NANOS = 1_000_000_000L;
    /** The far map's refresh while the camera stays. */
    public static final long FAR_REFRESH_NANOS = 4_000_000_000L;
    /** The maps' time of day: any fixed one (their sun steps never turn); the direction is the star's. */
    public static final float TIME_OF_DAY = 0;

    private static Vector3f fixed;

    private EndStarLight() {
    }

    /** Whether the star casts this frame: the effects and Shadows on, the End with the Anime sky. */
    public static boolean on(PostProcessSettings s, boolean end) {
        return s != null && s.enabled() && s.sunShadows() && end && s.endSky() == DimensionUniforms.END_SKY_ANIME;
    }

    /** Toward the star (world, unit). */
    public static Vector3f direction() {
        return new Vector3f(EndSkyMath.END_STAR_X, EndSkyMath.END_STAR_Y, EndSkyMath.END_STAR_Z).normalize();
    }

    /** The render thread, each frame: whether the shadow maps are the star's this frame. */
    public static void use(boolean on) {
        fixed = on ? direction() : null;
    }

    public static boolean active() {
        return fixed != null;
    }

    /** The shadow maps' light at the light's time of day: the star's while it casts, else the sun's (SunMath.sunDirection). */
    public static Vector3f toward(float lightTime) {
        Vector3f f = fixed;
        return f != null ? new Vector3f(f) : SunMath.sunDirection(lightTime);
    }
}
