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
    //2026-11-11 (session 13): the star type the buffer was built with (StarMath.TYPE_*), and the round point's texture
    private static int builtType = StarMath.TYPE_VANILLA;
    private static int cornerIndex;
    private static net.minecraft.resources.ResourceLocation roundTexture;

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

    /** The size factor the buffer is built with now (recorded as built); round stars are StarMath.ROUND_SIZE x larger. */
    public static float sizeScale() {
        VisualsConfig c = settings();
        builtSize = c == null ? 1f : StarMath.sizeScale(c.starSize);
        return builtType == StarMath.TYPE_ROUND ? builtSize * StarMath.ROUND_SIZE : builtSize;
    }

    /** Session 13: the star type to build with now (recorded as built; first in drawStars, before the sizes). */
    public static int type() {
        cornerIndex = 0;
        builtType = type(settings());
        return builtType;
    }

    private static int type(VisualsConfig c) {
        return c == null ? StarMath.TYPE_VANILLA : Math.max(0, Math.min(StarMath.TYPE_MAX, c.starType));
    }

    /** Whether the built buffer holds round stars (texture coordinates; drawn with the round point). */
    public static boolean round() {
        return builtType == StarMath.TYPE_ROUND;
    }

    /** The next vertex's corner (0..3) while a round buffer is built. */
    public static int nextCorner() {
        return cornerIndex++ & 3;
    }

    /** The round point (StarMath.roundAlpha, white), made once and kept by the texture manager. */
    public static net.minecraft.resources.ResourceLocation roundTexture() {
        if (roundTexture == null) {
            int n = StarMath.ROUND_TEXTURE;
            com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(n, n, false);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float dx = (x + 0.5f) / n * 2f - 1f, dy = (y + 0.5f) / n * 2f - 1f;
                    int a = Math.round(StarMath.roundAlpha((float) Math.sqrt(dx * dx + dy * dy)) * 255f);
                    image.setPixelRGBA(x, y, a << 24 | 0xFFFFFF);
                }
            }
            net.minecraft.client.renderer.texture.DynamicTexture texture = new net.minecraft.client.renderer.texture.DynamicTexture(image);
            texture.setFilter(true, false);
            roundTexture = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("vulkanmod", "visuals/star_round");
            Minecraft.getInstance().getTextureManager().register(roundTexture, texture);
        }
        return roundTexture;
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
        if (tries == builtTries && size == builtSize && type(c) == builtType) return;
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        if (renderer == null) return;
        try {
            ((LevelRendererStarsInvoker) renderer).visuals$createStars();
            VisualsLog.info("Visuals stars rebuilt: " + builtTries + " tries, size x" + builtSize + ", type " + builtType);
        } catch (RuntimeException e) {
            VisualsLog.warn("Visuals stars could not be rebuilt", e);
            builtTries = tries;
            builtSize = size;
            builtType = type(c);
        }
    }
}
