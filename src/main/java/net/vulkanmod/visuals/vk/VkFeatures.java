package net.vulkanmod.visuals.vk;

/** The plan applied when Reforged created the device (null before that). */
public final class VkFeatures {
    private static volatile VkFeaturePlan plan;
    private static volatile boolean depthBounds;

    private VkFeatures() {
    }

    public static VkFeaturePlan plan() {
        return plan;
    }

    /** Whether the depthBounds feature was enabled on the device (the LOD composite's depth bounds test needs it). */
    public static boolean depthBounds() {
        return depthBounds;
    }

    /**
     * Whether the device turns depthBounds on: only when the GPU has it and a LOD renderer (Distant Horizons with its render API) will
     * composite; without one the device is created as before (the composite keeps its depth-test fallback when the feature is off).
     */
    public static boolean enablesDepthBounds(boolean available, boolean lodRenderer) {
        return available && lodRenderer;
    }

    public static void setDepthBounds(boolean enabled) {
        depthBounds = enabled;
    }

    public static void set(VkFeaturePlan applied) {
        plan = applied;
    }
}
