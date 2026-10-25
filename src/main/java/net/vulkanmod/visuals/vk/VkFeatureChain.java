package net.vulkanmod.visuals.vk;

import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/**
 * Builds Reforged's device feature chain with the visuals' Vulkan 1.2 features. LWJGL's typed pNext(struct) prepends the
 * struct and overwrites its pNext with the current head, so each struct is attached with its own call, 1.1 first.
 */
public final class VkFeatureChain {
    private VkFeatureChain() {
    }

    /** Result: info -> f12 -> f11 (or info -> f11 when f12 is null). */
    public static VkDeviceCreateInfo attach(VkDeviceCreateInfo info, VkPhysicalDeviceVulkan11Features f11,
                                            VkPhysicalDeviceVulkan12Features f12) {
        info.pNext(f11);
        if (f12 != null) {
            info.pNext(f12);
        }
        return info;
    }
}
