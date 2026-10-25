package net.vulkanmod.visuals.render;

/** Whether this frame's post-process may record anything (review focus 2: title screen, menus, a minimised window). */
public final class PostGate {
    private PostGate() {
    }

    /** True only with a level, a window of some size, the shaders on, Reforged recording this frame and the post-process not failed. */
    public static boolean shouldRun(boolean hasLevel, int width, int height, boolean enabled, boolean recording, boolean failed) {
        return hasLevel && width > 0 && height > 0 && enabled && recording && !failed;
    }
}
