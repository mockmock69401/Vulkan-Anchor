package net.vulkanmod.visuals.vk;

/** An allocation the device or host could not back (VK_ERROR_OUT_OF_DEVICE_MEMORY or _HOST_MEMORY): a smaller one may fit. */
public final class VkOutOfMemoryException extends IllegalStateException {
    public VkOutOfMemoryException(String message) {
        super(message);
    }
}
