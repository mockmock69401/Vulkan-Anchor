package dev.vulkanfix.compat;

/** When to resume a render pass that Reforged ended while rebuilding the bound framebuffer's attachments. */
public final class FramebufferRestart {
    private FramebufferRestart() {
    }

    public static boolean shouldRestart(boolean isBound, boolean hasFramebuffer, boolean recording, boolean passActive) {
        return isBound && hasFramebuffer && recording && !passActive;
    }
}
