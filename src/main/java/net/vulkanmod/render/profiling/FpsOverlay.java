package net.vulkanmod.render.profiling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.vulkanmod.Initializer;

/**
 * The FPS overlay in the top-left corner (Config.fpsOverlay, 2026-10-26): off, simple (average), basic (average, min, max) or
 * detailed (average, 1% low, 0.1% low), over the last five seconds of rendered frames (FpsStats) and refreshed twice a second.
 * Hidden with the HUD (F1) and while the debug screen (F3) is up. Counts frames Minecraft renders, not generated ones.
 */
public final class FpsOverlay {
    public static final int OFF = 0, SIMPLE = 1, BASIC = 2, DETAILED = 3;
    private static final long REFRESH_NS = 500_000_000L;
    private static final int BACKGROUND = 0xC0303030, TEXT = 0xE0E0E0;
    private static final float LAYER_Z = 500.0F;

    private static final FpsStats STATS = new FpsStats();
    private static long lastFrame, lastRefresh;
    private static String line;

    private FpsOverlay() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> render(event.getGuiGraphics()));
    }

    /** At the start of every rendered frame (Renderer.beginFrame). */
    public static void frame() {
        if (Initializer.CONFIG.fpsOverlay == OFF) {
            lastFrame = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrame == 0) {
            STATS.clear();
            line = null;
        } else {
            STATS.add(now - lastFrame);
        }
        lastFrame = now;
        if (line == null || now - lastRefresh >= REFRESH_NS) {
            lastRefresh = now;
            line = format(Initializer.CONFIG.fpsOverlay, STATS.compute());
        }
    }

    static String format(int mode, FpsStats.Result r) {
        if (r == null) return null;
        String fps = Math.round(r.average()) + " FPS";
        return switch (mode) {
            case SIMPLE -> fps;
            case BASIC -> fps + "  min " + Math.round(r.min()) + "  max " + Math.round(r.max());
            case DETAILED -> fps + "  1% low " + Math.round(r.low1()) + "  0.1% low " + Math.round(r.low01());
            default -> null;
        };
    }

    private static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        String text = line;
        if (Initializer.CONFIG.fpsOverlay == OFF || text == null || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) return;
        int width = minecraft.font.width(text);
        //Above the rest of the HUD: packs put their own panels in this corner too (Tensura's draws over z 0)
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, LAYER_Z);
        graphics.fill(1, 1, width + 5, minecraft.font.lineHeight + 3, BACKGROUND);
        graphics.drawString(minecraft.font, text, 3, 3, TEXT, false);
        graphics.pose().popPose();
    }
}
