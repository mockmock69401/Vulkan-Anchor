package net.vulkanmod.visuals.render;

/**
 * The flags the post-process leaves for the next frame (the far clouds and, session 6, the shader weather replacing vanilla's, the
 * lightmap's sky flattening, the TAA jitter). A frame that does not post-process, fails, or shuts the renderer down puts all of them back to vanilla.
 */
public final class FrameFlags {
    private FrameFlags() {
    }

    /** Vanilla's clouds and weather, vanilla's lightmap and an unjittered projection from the next frame on. */
    public static void resetForSkippedPost() {
        FarClouds.set(false);
        ShaderWeather.set(false);
        SunShadows.set(0);
        TaaJitter.setActive(false);
    }
}
