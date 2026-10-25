package net.vulkanmod.visuals.framegen;

/**
 * When frame generation pauses (FG design 3.5): the pacer then presents real frames only. The first reason that applies names the
 * pause, in this order: the device cannot (no timeline semaphores), no level, a screen open (menus, inventories, chat; their blur and
 * post chains read the main target), an overlay (the resource reload's loading overlay: textures are re-uploaded under it), a
 * screenshot (the one frame after F2: its hand, GUI, vignette and crosshair draw into the world framebuffer the deferred grab reads),
 * the game paused, GameRenderer's post effect (it reads the main target after the hand), entity outlines (their post chain is drawn
 * over the level), F3's debug charts or Alt+F3's FPS chart, the camera on another entity, and last the pilot's forced pause (gate
 * G1.2). Pure: the caller reads the game state.
 *
 * Only "forced" keeps the UI layer (the frame goes through the layer composite, nothing is generated); every other reason draws the
 * GUI into the world framebuffer, which is captured whole at the end of the frame.
 */
public final class PauseRules {
    public static final String UNSUPPORTED = "unsupported", NO_WORLD = "no world", SCREEN = "screen", OVERLAY = "overlay",
            SCREENSHOT = "screenshot", PAUSED = "paused", POST_EFFECT = "post effect", OUTLINES = "outlines", DEBUG_CHARTS = "debug charts",
            SPECTATING = "spectating", FORCED = "forced";

    private PauseRules() {
    }

    /** The pause reason, or null when generation may run. */
    public static String reason(boolean level, boolean screen, boolean overlay, boolean screenshot, boolean paused, boolean postEffect,
                                boolean entityOutlines, boolean debugCharts, boolean spectatingOther, boolean supported, boolean forced) {
        if (!supported) return UNSUPPORTED;
        if (!level) return NO_WORLD;
        if (screen) return SCREEN;
        if (overlay) return OVERLAY;
        if (screenshot) return SCREENSHOT;
        if (paused) return PAUSED;
        if (postEffect) return POST_EFFECT;
        if (entityOutlines) return OUTLINES;
        if (debugCharts) return DEBUG_CHARTS;
        if (spectatingOther) return SPECTATING;
        if (forced) return FORCED;
        return null;
    }

    /** Whether a frame with this reason (null: none) draws its hand and GUI into the UI layer. */
    public static boolean keepsUiLayer(String reason) {
        return reason == null || FORCED.equals(reason);
    }
}
