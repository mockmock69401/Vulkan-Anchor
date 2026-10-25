package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

import java.util.List;

/** The shading settings as the passes take them: only the edge cover, which hides the gaps beside sections VulkanMod has not built yet. */
public record ShadingSettings(boolean edgeCover) {
    public static final String KEY_PREFIX = "vulkanmod.visuals.";
    public static final List<String> OPTION_NAMES = List.of("edge_cover");

    public static ShadingSettings from(VisualsConfig config) {
        VisualsConfig c = config != null ? config : new VisualsConfig();
        return new ShadingSettings(c.edgeCover);
    }

    /** The values in the settings screen's units, for the log line. */
    public String describe() {
        return "edge cover " + (this.edgeCover ? "on" : "off");
    }
}
