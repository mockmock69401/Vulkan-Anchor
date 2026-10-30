package net.vulkanmod.vulkan.texture;

import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import it.unimi.dsi.fastutil.shorts.Short2LongOpenHashMap;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.apache.commons.lang3.Validate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSamplerReductionModeCreateInfo;

import java.nio.LongBuffer;

import static net.vulkanmod.vulkan.Vulkan.getVkDevice;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_SAMPLER_REDUCTION_MODE_MAX;
import static org.lwjgl.vulkan.VK12.VK_SAMPLER_REDUCTION_MODE_MIN;

public abstract class SamplerManager {
    static final float MIP_BIAS = -0.5f;

    static final Short2LongMap SAMPLERS = new Short2LongOpenHashMap();

    public static long getTextureSampler(byte maxLod, byte flags) {
        short key = (short) (flags | (maxLod << 8));
        long sampler = SAMPLERS.getOrDefault(key, 0L);

        if (sampler == 0L) {
            sampler = createTextureSampler(maxLod, flags);
            SAMPLERS.put(key, sampler);
        }

        return sampler;
    }

    private static long createTextureSampler(byte maxLod, byte flags) {
        Validate.isTrue(
                (flags & (REDUCTION_MIN_BIT | REDUCTION_MAX_BIT)) != (REDUCTION_MIN_BIT | REDUCTION_MAX_BIT)
        );

        try (MemoryStack stack = stackPush()) {

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);

            if ((flags & LINEAR_FILTERING_BIT) != 0) {
                samplerInfo.magFilter(VK_FILTER_LINEAR);
                samplerInfo.minFilter(VK_FILTER_LINEAR);
            } else {
                samplerInfo.magFilter(VK_FILTER_NEAREST);
                samplerInfo.minFilter(VK_FILTER_NEAREST);
            }

            if ((flags & CLAMP_BIT) != 0) {
                samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            } else {
                samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT);
                samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT);
                samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            }

            samplerInfo.anisotropyEnable(false);
                                               
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE);
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);

            if ((flags & USE_MIPMAPS_BIT) == 0) {
                samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
                samplerInfo.maxLod(0.0F);
                samplerInfo.minLod(0.0F);
            } else {
                if ((flags & MIPMAP_LINEAR_FILTERING_BIT) != 0) {
                    samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);
                } else {
                    samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
                }
                samplerInfo.maxLod(maxLod);
                samplerInfo.minLod(0.0F);
                samplerInfo.mipLodBias(MIP_BIAS);
            }

                            
            if ((flags & (REDUCTION_MAX_BIT | REDUCTION_MIN_BIT)) != 0) {
                VkSamplerReductionModeCreateInfo reductionModeInfo = VkSamplerReductionModeCreateInfo.calloc(stack);
                reductionModeInfo.sType$Default();
                reductionModeInfo.reductionMode((flags & REDUCTION_MAX_BIT) != 0 ? VK_SAMPLER_REDUCTION_MODE_MAX : VK_SAMPLER_REDUCTION_MODE_MIN);
                samplerInfo.pNext(reductionModeInfo.address());
            }

            LongBuffer pTextureSampler = stack.mallocLong(1);

            if (vkCreateSampler(getVkDevice(), samplerInfo, null, pTextureSampler) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create texture sampler");
            }

            return pTextureSampler.get(0);
        }
    }

    /** 2026-10-30: the LOD bias of the anti-aliased font sampler (tuned against the sign captures). */
    public static final float FONT_LOD_BIAS = 0.0f;
    private static long fontSampler;

    /**
     * 2026-10-30: the font pages' sampler. Off: today's (nearest, level 0 only). On: linear, trilinear over the page's
     * FontMipmaps.LEVELS levels, anisotropic up to 16x where the device enabled samplerAnisotropy, clamped to the edge.
     */
    public static long getFontSampler(boolean antialias) {
        if (!antialias) {
            return getTextureSampler((byte) 1, (byte) 0);
        }
        if (fontSampler == 0L) {
            fontSampler = createFontSampler();
        }
        return fontSampler;
    }

    private static long createFontSampler() {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack);
            info.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            info.magFilter(VK_FILTER_LINEAR);
            info.minFilter(VK_FILTER_LINEAR);
            info.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);
            info.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            info.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            info.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            boolean aniso = DeviceManager.device != null && DeviceManager.device.availableFeatures.features().samplerAnisotropy()
                    && DeviceManager.deviceProperties != null;
            info.anisotropyEnable(aniso);
            info.maxAnisotropy(aniso ? Math.min(16.0f, DeviceManager.deviceProperties.limits().maxSamplerAnisotropy()) : 1.0f);
            info.borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE);
            info.compareOp(VK_COMPARE_OP_ALWAYS);
            info.minLod(0.0f);
            info.maxLod(net.vulkanmod.render.text.FontMipmaps.LEVELS - 1);
            info.mipLodBias(FONT_LOD_BIAS);
            LongBuffer p = stack.mallocLong(1);
            if (vkCreateSampler(getVkDevice(), info, null, p) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create the font sampler");
            }
            return p.get(0);
        }
    }

    public static void cleanUp() {
        if (fontSampler != 0L) {
            vkDestroySampler(DeviceManager.vkDevice, fontSampler, null);
            fontSampler = 0L;
        }
        for (long id : SAMPLERS.values()) {
            vkDestroySampler(DeviceManager.vkDevice, id, null);
        }
    }

    public static final byte LINEAR_FILTERING_BIT = 0b1;
    public static final byte CLAMP_BIT = 0b10;
    public static final byte USE_MIPMAPS_BIT = 0b100;
    public static final byte MIPMAP_LINEAR_FILTERING_BIT = 0b1000;
    public static final byte REDUCTION_MIN_BIT = 0b10000;
    public static final byte REDUCTION_MAX_BIT = 0b100000;
}

