package net.vulkanmod.visuals.framegen;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.vulkanmod.visuals.fsr.FsrSwapchain;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * 2026-11-02 (the user's request): while a frame generator runs (FSR's swapchain is live or Optical Flow paces the frames), the game
 * renders at most half the monitor's refresh rate, so its real and generated frames together fill the refresh. A lower frame limit
 * still applies; the cap only lowers it. MinecraftMixin.getFramerateLimit. Render thread.
 */
public final class GeneratorFrameCap {
    /** How often the monitor's refresh rate is read again (glfwGetVideoMode asks the OS each time). */
    private static final long REFRESH_POLL_NS = 1_000_000_000L;
    private static int refresh;
    private static long nextPollNs;

    private GeneratorFrameCap() {
    }

    /** The frame limit Minecraft.runTick applies: limit, capped at half the refresh rate while a generator runs. */
    public static int apply(int limit) {
        if (!FsrSwapchain.live() && !FrameGen.paced()) return limit;
        long now = System.nanoTime();
        if (now >= nextPollNs) {
            refresh = currentRefresh();
            nextPollNs = now + REFRESH_POLL_NS;
        }
        return cap(limit, refresh);
    }

    /** limit lowered to half of refresh (rounded down, at least 1); unchanged when the refresh rate is unknown (0 or less). */
    static int cap(int limit, int refresh) {
        if (refresh <= 0) return limit;
        return Math.min(limit, Math.max(1, refresh / 2));
    }

    /**
     * The refresh rate of the monitor the window is on: the fullscreen monitor's current mode (an exclusive mode VulkanMod switched to
     * included), else the monitor most of the window covers; 0 when there is none.
     */
    private static int currentRefresh() {
        Window window = Minecraft.getInstance().getWindow();
        long monitor = GLFW.glfwGetWindowMonitor(window.getWindow());
        if (monitor == 0L) {
            Monitor best = window.findBestMonitor();
            if (best == null) return 0;
            monitor = best.getMonitor();
        }
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        return mode == null ? 0 : mode.refreshRate();
    }
}
