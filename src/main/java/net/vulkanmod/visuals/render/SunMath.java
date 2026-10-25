package net.vulkanmod.visuals.render;

import org.joml.Vector3f;

/** The sun's direction and daylight weight from the level's time of day and rain, shared by every pass that lights or fogs by the sun. */
public final class SunMath {
    private SunMath() {
    }

    /** LevelRenderer.renderSky: YP(-90°) then XP(timeOfDay * 360°) applied to (0, 1, 0); timeOfDay 0 is noon. */
    public static Vector3f sunDirection(float timeOfDay) {
        double a = timeOfDay * 2 * Math.PI;
        return new Vector3f((float) -Math.sin(a), (float) Math.cos(a), 0);
    }

    /** Full above 0.2 sun height, none below the horizon or in full rain. */
    public static float dayFactor(float sunHeight, float rainLevel) {
        float t = Math.max(0, Math.min(1, sunHeight / 0.2f));
        return t * t * (3 - 2 * t) * (1 - Math.max(0, Math.min(1, rainLevel)));
    }
}
