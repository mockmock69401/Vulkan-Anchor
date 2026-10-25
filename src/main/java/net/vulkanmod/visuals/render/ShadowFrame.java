package net.vulkanmod.visuals.render;

import org.joml.Vector3f;

/**
 * P7j design 3.1, 3.2: this frame's sun views, made in frame() before the LOD passes. None when P7d's sun shading is off
 * (not chosen, night or rain: dayFactor 0, a cave: sky exposure 0) or the setting is off.
 */
public record ShadowFrame(long serial, ShadowMath.Cascade near, ShadowMath.Cascade far, boolean farDue, float timeOfDay, ShadowMath.Cascade close) {
    /** Below this camera sky exposure the sun shading is about 0 (a cave): no maps. */
    public static final float MIN_EXPOSURE = 0.01f;

    public static ShadowFrame of(long serial, boolean sunChosen, boolean setting, float timeOfDay, float rain, float skyExposure,
                                 double cameraX, double cameraY, double cameraZ, int frameId) {
        if (!sunChosen || !setting || skyExposure < MIN_EXPOSURE) return null;
        Vector3f sun = SunMath.sunDirection(timeOfDay);
        if (SunMath.dayFactor(sun.y, rain) <= 0) return null;
        return new ShadowFrame(serial, ShadowMath.cascade(sun, cameraX, cameraY, cameraZ, ShadowMath.NEAR_RADIUS, ShadowMath.NEAR_WIDTH,
                ShadowMath.NEAR_HEIGHT),
                ShadowMath.cascade(sun, cameraX, cameraY, cameraZ, ShadowMath.FAR_RADIUS, ShadowMath.FAR_WIDTH, ShadowMath.FAR_HEIGHT), Math.floorMod(frameId, ShadowMath.FAR_INTERVAL) == 0,
                timeOfDay, ShadowMath.cascade(sun, cameraX, cameraY, cameraZ, ShadowMath.CLOSE_RADIUS, ShadowMath.CLOSE_WIDTH,
                ShadowMath.CLOSE_HEIGHT));
    }
}
