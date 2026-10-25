package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.VisualsLog;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.file.Path;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;

/** FSR design 3.2: whether FSR 3.1 frame generation can run here, and the one reason when it cannot. */
public final class FsrSupport {
    public enum Reason {OK, NOT_WINDOWS, NOT_X64, VULKAN_BELOW_1_3, DLL, QUEUES, FEATURES, FAILED}

    private static final int VK_API_VERSION_1_3 = 1 << 22 | 3 << 12;

    private static volatile String osName = System.getProperty("os.name", "");
    private static volatile String osArch = System.getProperty("os.arch", "");
    private static volatile int apiVersion;
    private static volatile boolean dllLoaded;
    private static volatile boolean queuesPlanned;
    private static volatile boolean featuresReady = true;
    private static volatile boolean failedAtRuntime;
    private static volatile boolean failureLogged;

    private FsrSupport() {
    }

    /** The verdict; the checks run in the order of {@link Reason}. */
    public static Reason verdict(String osName, String osArch, int apiVersion, boolean dllLoaded, boolean queuesPlanned,
                                 boolean featuresReady, boolean failedAtRuntime) {
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) return Reason.NOT_WINDOWS;
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) return Reason.NOT_X64;
        //the patch number (low 12 bits) does not matter: 1.3.0 and up
        if ((apiVersion & ~0xFFF) < VK_API_VERSION_1_3) return Reason.VULKAN_BELOW_1_3;
        if (!dllLoaded) return Reason.DLL;
        if (!queuesPlanned) return Reason.QUEUES;
        if (!featuresReady) return Reason.FEATURES;
        if (failedAtRuntime) return Reason.FAILED;
        return Reason.OK;
    }

    /** Reads OS and GPU api version and loads the DLL when those allow it; never throws. Called before device creation. */
    public static void probe(VkPhysicalDevice pd) {
        try {
            osName = System.getProperty("os.name", "");
            osArch = System.getProperty("os.arch", "");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(pd, props);
                apiVersion = props.apiVersion();
            }
            dllLoaded = false;
            //only OS, arch and api version are asked here: the DLL and queue inputs are assumed present
            if (verdict(osName, osArch, apiVersion, true, true, true, false) == Reason.OK) {
                FsrNative.loadBundled(cacheDir());
                dllLoaded = true;
            }
        } catch (Throwable t) {
            dllLoaded = false;
            VisualsLog.warn("Visuals FSR: probe failed", t);
        }
        VisualsLog.info("Visuals FSR: " + current());
    }

    /** Task 4 reports here whether the queues FSR needs could be planned. */
    public static void queuesPlanned(boolean planned) {
        queuesPlanned = planned;
    }

    /**
     * Device creation reports here whether the GPU has the device features AMD's shaders declare (storage image reads and
     * writes without a format); without them FSR's contexts would make invalid shader modules.
     */
    public static void featuresReady(boolean ready) {
        featuresReady = ready;
    }

    public static Reason current() {
        return verdict(osName, osArch, apiVersion, dllLoaded, queuesPlanned, featuresReady, failedAtRuntime);
    }

    /** Turns FSR off for the session; only the first call is logged. */
    public static void markFailed(String why, Throwable t) {
        failedAtRuntime = true;
        if (!failureLogged) {
            failureLogged = true;
            if (t != null) VisualsLog.warn("Visuals FSR: failed, " + why, t);
            else VisualsLog.warn("Visuals FSR: failed, " + why);
        }
    }

    private static Path cacheDir() {
        Path gameDir;
        try {
            gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
        } catch (Throwable t) {
            gameDir = Path.of("").toAbsolutePath();
        }
        return gameDir.resolve(".vulkanmod").resolve("natives");
    }
}
