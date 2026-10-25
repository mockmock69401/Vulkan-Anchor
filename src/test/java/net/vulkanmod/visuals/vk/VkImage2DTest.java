package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.*;

/** Review focus 5: Reforged's depth format may be D24S8; a sampled view of it must cover the depth aspect only. */
class VkImage2DTest {
    @Test
    void aspectsFollowTheFormat() {
        assertEquals(VK_IMAGE_ASPECT_DEPTH_BIT, VkImage2D.aspectsOf(VK_FORMAT_D32_SFLOAT));
        assertEquals(VK_IMAGE_ASPECT_DEPTH_BIT, VkImage2D.aspectsOf(VK_FORMAT_X8_D24_UNORM_PACK32));
        assertEquals(VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, VkImage2D.aspectsOf(VK_FORMAT_D24_UNORM_S8_UINT));
        assertEquals(VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, VkImage2D.aspectsOf(VK_FORMAT_D32_SFLOAT_S8_UINT));
        assertEquals(VK_IMAGE_ASPECT_COLOR_BIT, VkImage2D.aspectsOf(VK_FORMAT_R8G8B8A8_UNORM));
        assertEquals(VK_IMAGE_ASPECT_COLOR_BIT, VkImage2D.aspectsOf(VK_FORMAT_R32_SFLOAT));
    }
}
