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
    /** With the pilot, -Dvulkanmod.frameGeneration.uncapped turns the cap off (stress runs of FSR's command list gate). */
    private static final boolean UNCAPPED = System.getProperty(net.vulkanmod.visuals.dev.VisualsPilot.PROPERTY) != null
            && Boolean.getBoolean("vulkanmod.frameGeneration.uncapped");
    /** Minecraft.runTick limits the frame rate only below this. */
    static final int UNLIMITED = 260;
    /** Below this much time left, the pacer spins instead of sleeping (Thread.sleep(1) wakes within about a millisecond). */
    private static final long SPIN_NS = 1_500_000L;
    private static int refresh;
    private static long nextPollNs;
    /** When the next capped frame may start (System.nanoTime), 0 while the cap does not pace. */
    private static long nextFrameNs;

    private GeneratorFrameCap() {
    }

    /**
     * The frame limit Minecraft.runTick applies: limit, or, while a generator runs and half the refresh rate is lower, UNLIMITED after
     * pacing the frame here (2026-11-03: vanilla's limiter waits in glfwWaitEventsTimeout, which wakes on the coarse system timer; a
     * cap of 119 on the 239 Hz monitor gave 71 real fps).
     */
    public static int apply(int limit) {
        if (UNCAPPED || (!FsrSwapchain.live() && !FrameGen.paced())) {
            nextFrameNs = 0L;
            return limit;
        }
        long now = System.nanoTime();
        if (now >= nextPollNs) {
            int was = refresh;
            refresh = currentRefresh();
            nextPollNs = now + REFRESH_POLL_NS;
            if (refresh != was) {
                net.vulkanmod.visuals.VisualsLog.info("Visuals: frame generation caps the frame rate at " + cap(UNLIMITED, refresh)
                        + " fps (monitor refresh " + refresh + " Hz)");
            }
        }
        int capped = cap(limit, refresh);
        if (capped >= limit) {
            nextFrameNs = 0L;
            return limit;
        }
        pace(capped);
        return UNLIMITED;
    }

    /** Waits until this frame's slot at fps: frames start 1/fps apart; one more than a frame late starts the cadence over. */
    private static void pace(int fps) {
        long interval = 1_000_000_000L / fps;
        long now = System.nanoTime();
        if (nextFrameNs == 0L || now - nextFrameNs > interval) nextFrameNs = now;
        long left;
        while ((left = nextFrameNs - System.nanoTime()) > 0L) {
            if (left > SPIN_NS) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                Thread.onSpinWait();
            }
        }
        nextFrameNs += interval;
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
