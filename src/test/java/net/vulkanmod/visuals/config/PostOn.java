package net.vulkanmod.visuals.config;

/**
 * 2026-11-21: a new install starts with the post-process off (VisualsConfig.enabled); a test of what the post-process does starts from
 * the other defaults with it on.
 */
public final class PostOn {
    private PostOn() {
    }

    /** The default settings with the post-process on. */
    public static VisualsConfig defaults() {
        VisualsConfig c = new VisualsConfig();
        c.enabled = true;
        return c;
    }
}
