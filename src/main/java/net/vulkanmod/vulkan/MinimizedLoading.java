package net.vulkanmod.vulkan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.vulkanmod.Initializer;

/**
 * 2026-11-15 (session 23, Conquest the Kingdom's Throne: "infinite loading"): a minimized window has a 0 x 0 swapchain, so the Renderer
 * skips its frames and sets Minecraft.noRender, and vanilla then skips GameRenderer.render. But the loading overlay's render is where a
 * finished resource reload calls its callback and the overlay goes away (Forge's loading overlay starts its next loading stage from
 * there too): minimized during loading (a fullscreen game minimizes on alt-tab), the game loaded no further until it was restored.
 * Vanilla keeps calling render while minimized; this runs the overlay's render each tick in that case, and BufferUploaderM drops its
 * draws while frames are skipped.
 */
public final class MinimizedLoading {
    private static boolean failed;

    private MinimizedLoading() {
    }

    /** Called where runTick reads noRender. */
    public static void advance(Minecraft mc) {
        if (!mc.noRender || !Renderer.skipRendering || failed) return;
        if (!(mc.getOverlay() instanceof LoadingOverlay overlay)) return;
        try {
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            overlay.render(graphics, 0, 0, mc.getFrameTime());
            graphics.flush();
        } catch (RuntimeException e) {
            failed = true;
            Initializer.LOGGER.warn("The loading screen could not advance while the window is minimized; it continues once restored", e);
        }
    }
}
