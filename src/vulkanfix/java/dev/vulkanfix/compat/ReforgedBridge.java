package dev.vulkanfix.compat;

/**
 * Calls VulkanMod-Reforged's GlCapabilitiesFallback.install() by name, so vulkanfix has no compile-time
 * dependency on Reforged. install() is idempotent; Reforged calls it again from initRenderer and Minecraft.<init>.
 */
public final class ReforgedBridge {
    public static final String FALLBACK_CLASS = "net.vulkanmod.compat.opengl.GlCapabilitiesFallback";

    private ReforgedBridge() {
    }

    public static boolean installEmulatedGl() {
        return installEmulatedGl(FALLBACK_CLASS, ReforgedBridge.class.getClassLoader());
    }

    static boolean installEmulatedGl(String fallbackClass, ClassLoader loader) {
        try {
            Class.forName(fallbackClass, true, loader).getMethod("install").invoke(null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            VrGuard.LOGGER.warn("[vulkanfix] Could not install VulkanMod's emulated GL capabilities early", e);
            return false;
        }
    }
}
