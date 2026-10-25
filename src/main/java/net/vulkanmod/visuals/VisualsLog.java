package net.vulkanmod.visuals;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** The visuals' log output. */
public final class VisualsLog {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod Visuals");

    private VisualsLog() {
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warn(String message) {
        LOGGER.warn(message);
    }

    public static void warn(String message, Throwable t) {
        LOGGER.warn(message, t);
    }

    public static void error(String message, Throwable t) {
        LOGGER.error(message, t);
    }
}
