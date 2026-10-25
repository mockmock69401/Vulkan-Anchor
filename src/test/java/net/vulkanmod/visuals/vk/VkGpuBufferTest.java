package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.lwjgl.vulkan.VK10.VK_ERROR_INITIALIZATION_FAILED;
import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_HOST_MEMORY;

/** P7a: out of memory is told apart from other allocation failures, so a large pool can be retried smaller. */
class VkGpuBufferTest {
    @Test
    void outOfMemoryIsToldApart() {
        assertInstanceOf(VkOutOfMemoryException.class, VkGpuBuffer.creationFailure(VK_ERROR_OUT_OF_DEVICE_MEMORY));
        assertInstanceOf(VkOutOfMemoryException.class, VkGpuBuffer.creationFailure(VK_ERROR_OUT_OF_HOST_MEMORY));
        assertFalse(VkGpuBuffer.creationFailure(VK_ERROR_INITIALIZATION_FAILED) instanceof VkOutOfMemoryException);
        assertEquals("vmaCreateBuffer failed: -2", VkGpuBuffer.creationFailure(VK_ERROR_OUT_OF_DEVICE_MEMORY).getMessage());
    }
}
