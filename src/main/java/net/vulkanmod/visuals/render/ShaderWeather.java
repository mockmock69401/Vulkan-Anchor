package net.vulkanmod.visuals.render;

/**
 * 2026-11-09 (session 6): whether the last post-processed frame could draw the shader weather (the setting on, the post-process ran),
 * so LevelRendererWeatherMixin leaves vanilla's rain and snow columns out (as FarClouds does for the clouds). The render thread sets and
 * reads it; the post-process sets it every frame and the render system clears it when it shuts down. Off, vanilla's weather is drawn
 * exactly as before.
 */
public final class ShaderWeather {
    private static boolean replacing;

    private ShaderWeather() {
    }

    public static boolean replacesVanilla() {
        return replacing;
    }

    public static void set(boolean on) {
        replacing = on;
    }
}
