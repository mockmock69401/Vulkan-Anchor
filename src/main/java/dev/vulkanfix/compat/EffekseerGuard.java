package dev.vulkanfix.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AAA Particles draws through Effekseer's native OpenGL renderer, which looks up GL entry points itself; under
 * VulkanMod there is no GL context, so creating an Effekseer manager jumps to a null function pointer and kills
 * the JVM. AAA Particles already has an "unsupported platform" mode (set when its native library fails to load)
 * that every native path checks; enter it instead of loading the library.
 */
public final class EffekseerGuard {
    public static final String REASON = "VulkanMod renders with Vulkan; Effekseer's native renderer needs an OpenGL context";
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkanfix");
    private static final String NATIVE_PLATFORM = "mod.chloeprime.aaaparticles.client.installer.NativePlatform";

    private EffekseerGuard() {
    }

    /** Marks AAA Particles' platform unsupported; false if that failed and the native library should load as usual. */
    public static boolean markPlatformUnsupported() {
        return markPlatformUnsupported(NATIVE_PLATFORM);
    }

    static boolean markPlatformUnsupported(String nativePlatformClass) {
        try {
            Class.forName(nativePlatformClass, true, EffekseerGuard.class.getClassLoader())
                    .getMethod("setCurrentPlatformUnsupported", Throwable.class)
                    .invoke(null, new UnsupportedOperationException(REASON));
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("[vulkanfix] Could not switch AAA Particles to its unsupported-platform mode", e);
            return false;
        }
        LOGGER.warn("[vulkanfix] AAA Particles' Effekseer effects are off: " + REASON);
        return true;
    }
}
