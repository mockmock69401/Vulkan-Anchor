package net.vulkanmod.visuals.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

public final class VkSamplers {
    private VkSamplers() {
    }

    /** Nearest texel and nearest mip, clamped to the edge (HiZ, depth and colour lookups). */
    public static long nearestClamp() {
        return create(VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_LOD_CLAMP_NONE);
    }

    /** LightMapHelper's sampler: linear, clamped to the edge. */
    public static long linearClamp() {
        return create(VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_LOD_CLAMP_NONE);
    }

    /** ModelStore's block sampler: GL_NEAREST_MIPMAP_LINEAR / GL_NEAREST, GL's default repeat, LOD 0 to maxLod. */
    public static long modelAtlas(float maxLod) {
        return create(VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT, maxLod);
    }

    private static long create(int minFilter, int magFilter, int mipmapMode, int addressMode, float maxLod) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(magFilter).minFilter(minFilter).mipmapMode(mipmapMode)
                    .addressModeU(addressMode).addressModeV(addressMode).addressModeW(addressMode)
                    .minLod(0).maxLod(maxLod).borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateSampler(VkContext.device(), info, null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateSampler failed: " + result);
            return p.get(0);
        }
    }

    public static void free(long sampler) {
        vkDestroySampler(VkContext.device(), sampler, null);
    }
}
