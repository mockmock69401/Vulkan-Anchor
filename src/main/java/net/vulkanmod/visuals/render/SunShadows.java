package net.vulkanmod.visuals.render;

/**
 * P7j design 3.4: how much the lightmap's sky brightness is flattened, set by the last post-processed frame (one frame
 * behind, like FarClouds); 0 is vanilla. The render thread sets and reads it.
 */
public final class SunShadows {
    private static float flatten;

    private SunShadows() {
    }

    public static void set(float strength) {
        flatten = Math.max(0, Math.min(1, strength));
    }

    public static float flatten() {
        return flatten;
    }

    /** The value set last (clamped), 0 when the lightmap is vanilla's. */
    public static float current() {
        return flatten;
    }

    /** LightTextureSkyMixin: vanilla's brightness for one sky level, flattened while the shadows are on. */
    public static float skyBrightness(float vanilla) {
        return SkyLightCurve.apply(vanilla, flatten);
    }

    /** Design 3.4, 3.5: the setting's strength while this frame's lighting chose the sun shading and the shadow maps exist; else 0. */
    public static float strength(PostProcessSettings s, LightingChoice choice, long serial, boolean available) {
        return s.sunShadows() && available && choice.serial() == serial && choice.sun() ? s.skyLightAmbient() : 0;
    }
}
