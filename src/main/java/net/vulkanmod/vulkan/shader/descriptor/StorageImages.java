package net.vulkanmod.vulkan.shader.descriptor;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;

import java.util.function.LongSupplier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Storage images a pipeline JSON names ("storageImages"), by name: the view bound in GENERAL layout when a descriptor set is
 * updated. The owner keeps its image in GENERAL; a supplier that has none (0), or a name nobody registered, gets a 1x1 R32_UINT
 * stand-in, which shaders must not touch (their owner's uniforms say so).
 */
public final class StorageImages {
    private static final Object2ObjectOpenHashMap<String, LongSupplier> VIEWS = new Object2ObjectOpenHashMap<>();
    private static VulkanImage standIn;

    private StorageImages() {
    }

    public static void register(String name, LongSupplier view) {
        VIEWS.put(name, view);
    }

    /** The view to bind for name. */
    public static long view(String name) {
        LongSupplier supplier = VIEWS.get(name);
        long view = supplier != null ? supplier.getAsLong() : 0;
        return view != 0 ? view : standIn().getImageView();
    }

    private static VulkanImage standIn() {
        if (standIn == null) {
            VulkanImage image = VulkanImage.builder(1, 1).setFormat(VK_FORMAT_R32_UINT).setUsage(VK_IMAGE_USAGE_STORAGE_BIT)
                    .setLinearFiltering(false).setClamp(true).createVulkanImage();
            //Into GENERAL with its own submit, as VulkanImage.readOnlyLayout does (a descriptor update may run inside a render pass)
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().getCommandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanImage.transitionLayout(stack, commandBuffer.getHandle(), image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0);
            }
            DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            Synchronization.INSTANCE.addCommandBuffer(commandBuffer);
            standIn = image;
        }
        return standIn;
    }
}
