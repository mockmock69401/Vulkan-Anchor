package net.vulkanmod.visuals.render;

import org.joml.Vector3f;

/**
 * P7j design 3.1, 3.2: this frame's sun views, made in frame() before the LOD passes. None when P7d's sun shading is off
 * (not chosen, night or rain: dayFactor 0, a cave: sky exposure 0) or the setting is off.
 * <p>2026-11-18: at night the views are the moon's (MoonShadowMath), none at new moon or with the moon below the horizon. timeOfDay is
 * the light's time of day (MoonShadowMath.lightTime: the moon's is half a day on), which the maps' sun steps and the entity map follow.
 */
public record ShadowFrame(long serial, ShadowMath.Cascade near, ShadowMath.Cascade far, boolean farDue, float timeOfDay, ShadowMath.Cascade close) {
    /** Below this camera sky exposure the sun shading is about 0 (a cave): no maps. */
    public static final float MIN_EXPOSURE = 0.01f;

    /** Without the moon (its strength 0): the sun's maps only. */
    public static ShadowFrame of(long serial, boolean sunChosen, boolean setting, float timeOfDay, float rain, float skyExposure,
                                 double cameraX, double cameraY, double cameraZ, int frameId) {
        return of(serial, sunChosen, setting, timeOfDay, rain, skyExposure, cameraX, cameraY, cameraZ, frameId, 0);
    }

    /** 2026-11-18: moonStrength is MoonShadowMath.strength (the moonlight setting and the phase); at night the maps are the moon's. */
    public static ShadowFrame of(long serial, boolean sunChosen, boolean setting, float timeOfDay, float rain, float skyExposure,
                                 double cameraX, double cameraY, double cameraZ, int frameId, float moonStrength) {
        if (!sunChosen || !setting || skyExposure < MIN_EXPOSURE) return null;
        if (!(MoonShadowMath.weight(timeOfDay, rain, moonStrength) > 0)) return null;
        float lightTime = MoonShadowMath.lightTime(timeOfDay);
        Vector3f light = SunMath.sunDirection(lightTime);
        return new ShadowFrame(serial, ShadowMath.cascade(light, cameraX, cameraY, cameraZ, ShadowMath.NEAR_RADIUS, ShadowMath.NEAR_WIDTH,
                ShadowMath.NEAR_HEIGHT),
                ShadowMath.cascade(light, cameraX, cameraY, cameraZ, ShadowMath.FAR_RADIUS, ShadowMath.FAR_WIDTH, ShadowMath.FAR_HEIGHT), Math.floorMod(frameId, ShadowMath.FAR_INTERVAL) == 0,
                lightTime, ShadowMath.cascade(light, cameraX, cameraY, cameraZ, ShadowMath.CLOSE_RADIUS, ShadowMath.CLOSE_WIDTH,
                ShadowMath.CLOSE_HEIGHT));
    }
}
