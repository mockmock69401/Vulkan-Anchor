package net.vulkanmod.visuals.fsr;

/**
 * FSR design 4.4: FSR's replacement acquire and present do not pass OUT_OF_DATE on to VulkanMod (its non-generated present path
 * drops the real swapchain's result and skips the present when the real acquire fails), so a real swapchain that went stale,
 * e.g. after a resize, would leave the screen frozen. Every {@link #WINDOW} frames this compares the surface size with the
 * swapchain's and checks that FSR's present count moved; a stale swapchain is recreated, and after {@link #GIVE_UP} stalls in a
 * row FSR gives up. Pure: FsrSwapchain feeds it.
 */
public final class FsrPresentWatch {
    public enum Verdict {OK, RECREATE, GIVE_UP}

    public static final int WINDOW = 16;
    public static final int GIVE_UP = 3;

    private int frames;
    private long lastPresents = -1;
    private int stalls;

    /** Counts a frame; true on the frames that should be checked. */
    public boolean due() {
        return ++frames % WINDOW == 0;
    }

    /** Frames counted since the last {@link #reset}. */
    public int frames() {
        return frames;
    }

    /**
     * presents: FSR's present count of the current swapchain; surfaceW/H: the surface's current extent, -1 when the surface does
     * not report one; swapW/H: the swapchain's extent.
     */
    public Verdict check(long presents, int surfaceW, int surfaceH, int swapW, int swapH) {
        //2026-10-28: a 0x0 surface is a minimized window (FsrSwapchain.pausedMinimized pauses the frames), not a stale swapchain
        if (surfaceW == 0 && surfaceH == 0) return Verdict.OK;
        if (surfaceW != -1 && (surfaceW != swapW || surfaceH != swapH)) return Verdict.RECREATE;
        if (lastPresents >= 0) {
            if (presents == lastPresents) return ++stalls >= GIVE_UP ? Verdict.GIVE_UP : Verdict.RECREATE;
            stalls = 0;
        }
        lastPresents = presents;
        return Verdict.OK;
    }

    /** A new swapchain (its present count starts again): the next check only records. Stalls carry over until presents move. */
    public void reset() {
        frames = 0;
        lastPresents = -1;
    }
}
