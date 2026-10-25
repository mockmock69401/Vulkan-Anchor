package dev.vulkanfix.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/** State for blocking Vivecraft VR start-up under VulkanMod: warn once, block always. */
public final class VrGuard {
    public static final Logger LOGGER = LoggerFactory.getLogger("vulkanfix");
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private VrGuard() {
    }

    /** True exactly once per game session: the caller should log the warning. */
    public static boolean claimFirstWarning() {
        return WARNED.compareAndSet(false, true);
    }

    /**
     * Undo a saved vrEnabled=true (title button / hotkey) so it does not re-trigger VR on the next
     * launch via rememberVr, including after switching back to OpenGL. Mirrors VRState.destroyVR(true).
     */
    public static void clearSavedVrEnabled() {
        try {
            Class<?> holderClass = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR", false, VrGuard.class.getClassLoader());
            Object holder = holderClass.getMethod("getInstance").invoke(null);
            Object settings = holderClass.getField("vrSettings").get(holder);
            if (settings != null) {
                clearSavedVrEnabled(settings);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[vulkanfix] Could not reset Vivecraft's saved vrEnabled setting", e);
        }
    }

    /** Clears vrEnabled on a VRSettings-shaped object and saves it; false if it was already off. */
    static boolean clearSavedVrEnabled(Object vrSettings) throws ReflectiveOperationException {
        java.lang.reflect.Field vrEnabled = vrSettings.getClass().getField("vrEnabled");
        if (!vrEnabled.getBoolean(vrSettings)) {
            return false;
        }
        vrEnabled.setBoolean(vrSettings, false);
        vrSettings.getClass().getMethod("saveOptions").invoke(vrSettings);
        return true;
    }

    static void resetForTest() {
        WARNED.set(false);
    }
}
