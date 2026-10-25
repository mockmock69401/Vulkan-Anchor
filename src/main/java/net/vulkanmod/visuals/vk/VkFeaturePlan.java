package net.vulkanmod.visuals.vk;

import java.util.ArrayList;
import java.util.List;

/** Device features the visuals' Vulkan backend turns on when Reforged creates the device; missing ones are left off. */
public record VkFeaturePlan(boolean drawIndirectCount, boolean shaderInt64, boolean drawIndirectFirstInstance, boolean multiDrawIndirect,
                            boolean fragmentStoresAndAtomics, boolean vertexPipelineStoresAndAtomics, boolean shaderDrawParameters,
                            boolean independentBlend, boolean timelineSemaphore, List<String> missing) {
    public static VkFeaturePlan of(boolean drawIndirectCountAvailable, boolean shaderInt64Available, boolean firstInstanceAvailable,
                                   boolean multiDrawAvailable, boolean fragmentStoresAvailable, boolean vertexStoresAvailable,
                                   boolean shaderDrawParametersAvailable, boolean independentBlendAvailable, boolean timelineSemaphoreAvailable) {
        List<String> missing = new ArrayList<>();
        if (!drawIndirectCountAvailable) missing.add("drawIndirectCount");
        if (!shaderInt64Available) missing.add("shaderInt64");
        if (!firstInstanceAvailable) missing.add("drawIndirectFirstInstance");
        if (!multiDrawAvailable) missing.add("multiDrawIndirect");
        if (!fragmentStoresAvailable) missing.add("fragmentStoresAndAtomics");
        if (!vertexStoresAvailable) missing.add("vertexPipelineStoresAndAtomics");
        if (!shaderDrawParametersAvailable) missing.add("shaderDrawParameters");
        if (!independentBlendAvailable) missing.add("independentBlend");
        if (!timelineSemaphoreAvailable) missing.add("timelineSemaphore");
        return new VkFeaturePlan(drawIndirectCountAvailable, shaderInt64Available, firstInstanceAvailable, multiDrawAvailable,
                fragmentStoresAvailable, vertexStoresAvailable, shaderDrawParametersAvailable, independentBlendAvailable, timelineSemaphoreAvailable, List.copyOf(missing));
    }

    /** Frame generation paces its submits with timeline semaphores (core in Vulkan 1.2, opt-in feature). */
    public boolean frameGenerationSupported() {
        return this.timelineSemaphore;
    }

    public boolean spikeSupported() {
        return this.drawIndirectCount;
    }

    /**
     * MDIC terrain: indirect-count multi-draws whose firstInstance is the section index (gl_BaseInstance), 64-bit shader
     * ints, the cull raster's visibility writes (raster.frag stores, raster.vert declares the buffer writable), and
     * gl_BaseInstance (shaderDrawParameters), and independent blend (P7l design 3.2: the LOD passes' light attachment has
     * a different write mask / blend enable than the colour attachment, VUID-VkPipelineColorBlendStateCreateInfo-pAttachments-00605).
     */
    public boolean lodDrawSupported() {
        return this.drawIndirectCount && this.shaderInt64 && this.drawIndirectFirstInstance && this.multiDrawIndirect
                && this.fragmentStoresAndAtomics && this.vertexPipelineStoresAndAtomics && this.shaderDrawParameters && this.independentBlend;
    }
}
