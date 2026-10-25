package dev.vulkanfix.compat;

/**
 * Last viewport requested through GlStateManager._viewport. Reforged writes viewports straight into the command
 * buffer and keeps no copy, but GL semantics keep the viewport across framebuffer attachment changes.
 */
public final class ViewportTracker {
    private static int[] last;

    private ViewportTracker() {
    }

    public static void record(int x, int y, int width, int height) {
        last = new int[]{x, y, width, height};
    }

    public static int[] last() {
        return last == null ? null : last.clone();
    }

    static void resetForTest() {
        last = null;
    }
}
