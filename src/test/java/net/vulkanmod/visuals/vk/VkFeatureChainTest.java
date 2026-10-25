package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LWJGL's typed VkDeviceCreateInfo.pNext(struct) prepends the struct and overwrites its pNext with the current chain
 * head, so linking 1.2 features behind the 1.1 struct before Reforged's pNext(f11) call loses them. Attach after.
 */
class VkFeatureChainTest {
    @Test
    void vulkan12FeaturesStayInTheChain() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().drawIndirectCount(true);

            VkFeatureChain.attach(info, f11, f12);

            assertEquals(f12.address(), info.pNext(), "info -> f12");
            assertEquals(f11.address(), f12.pNext(), "f12 -> f11");
            assertEquals(0L, f11.pNext(), "f11 ends the chain");
        }
    }

    @Test
    void withoutVulkan12OnlyTheOriginalChain() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();

            VkFeatureChain.attach(info, f11, null);

            assertEquals(f11.address(), info.pNext());
            assertEquals(0L, f11.pNext());
        }
    }
}
