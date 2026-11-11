package net.vulkanmod.visuals.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.mixin.visuals.LevelRendererStarsInvoker;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;

/**
 * 2026-11-11 (session 12): the star settings (StarMath) applied to vanilla's stars. LevelRendererStarsMixin asks here for the tries and
 * the size while vanilla builds its star buffer (the constructor's createStars, or update's), and for the draw's value each frame. With
 * the post-process off the stars are vanilla's (the settings follow the master switch like every other one). update runs at the start
 * of the game loop's tick, outside the frame's recording (as PbrAtlas does): when the count or the size wanted differ from the built
 * buffer it rebuilds it (vanilla's createStars: a new vertex buffer, the old one freed after the frames in flight).
 */
public final class StarSky {
    private static int builtTries = StarMath.VANILLA_STARS;
    private static float builtSize = 1f;

    private StarSky() {
    }

    private static VisualsConfig settings() {
        VisualsConfig c = VisualsConfig.get();
        return c != null && c.enabled ? c : null;
    }

    /** The tries the buffer is built with now (recorded as built). */
    public static int tries() {
        VisualsConfig c = settings();
        builtTries = c == null ? StarMath.VANILLA_STARS : StarMath.tries(c.starCount);
        return builtTries;
    }

    /** The size factor the buffer is built with now (recorded as built). */
    public static float sizeScale() {
        VisualsConfig c = settings();
        builtSize = c == null ? 1f : StarMath.sizeScale(c.starSize);
        return builtSize;
    }

    /** The stars' draw value for vanilla's b this frame. */
    public static float drawValue(float vanilla) {
        VisualsConfig c = settings();
        return c == null ? vanilla : StarMath.drawValue(vanilla, c.starBrightness);
    }

    /** Rebuilds vanilla's star buffer when the count or size setting changed since it was built. */
    public static void update() {
        VisualsConfig c = settings();
        int tries = c == null ? StarMath.VANILLA_STARS : StarMath.tries(c.starCount);
        float size = c == null ? 1f : StarMath.sizeScale(c.starSize);
        if (tries == builtTries && size == builtSize) return;
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        if (renderer == null) return;
        try {
            ((LevelRendererStarsInvoker) renderer).visuals$createStars();
            VisualsLog.info("Visuals stars rebuilt: " + builtTries + " tries, size x" + builtSize);
        } catch (RuntimeException e) {
            VisualsLog.warn("Visuals stars could not be rebuilt", e);
            builtTries = tries;
            builtSize = size;
        }
    }
}
