package net.vulkanmod.config;

import net.vulkanmod.Initializer;

public abstract class UpdateChecker {
    public static final String RELEASES_URL = "https://github.com/TrulyRin/VulkanMod-Reforged/releases";

    private static boolean updateAvailable = false;

    /**
     * VulkanMod-shaded (2026-10-25): no update check. The upstream check compared this build's version with VulkanMod Reforged's
     * GitHub releases, which would offer Reforged as an "update" to this build.
     */
    public static void checkForUpdates() {
        Initializer.LOGGER.info("VulkanMod-shaded: update check disabled.");
    }

    public static boolean isUpdateAvailable() {
        return updateAvailable;
    }
}
