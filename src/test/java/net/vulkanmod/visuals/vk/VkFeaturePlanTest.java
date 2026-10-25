package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VkFeaturePlanTest {
    @Test
    void enablesOnlyWhatTheDeviceHas() {
        VkFeaturePlan all = VkFeaturePlan.of(true, true, true, true, true, true, true, true, true);
        assertTrue(all.drawIndirectCount());
        assertTrue(all.shaderInt64());
        assertTrue(all.drawIndirectFirstInstance());
        assertTrue(all.multiDrawIndirect());
        assertEquals(List.of(), all.missing());
        assertTrue(all.spikeSupported());

        VkFeaturePlan none = VkFeaturePlan.of(false, false, false, false, false, false, false, false, false);
        assertEquals(List.of("drawIndirectCount", "shaderInt64", "drawIndirectFirstInstance", "multiDrawIndirect", "fragmentStoresAndAtomics",
                "vertexPipelineStoresAndAtomics", "shaderDrawParameters", "independentBlend", "timelineSemaphore"), none.missing());
        assertFalse(none.spikeSupported(), "the spike draws with vkCmdDrawIndirectCount");
    }

    @Test
    void int64AloneDoesNotBlockTheSpike() {
        assertTrue(VkFeaturePlan.of(true, false, true, true, true, true, true, true, true).spikeSupported());
    }

    /** cmdgen writes firstInstance = section index and quads3.vert reads gl_BaseInstance; MDIC needs every indirect feature. */
    @Test
    void lodDrawsNeedEveryIndirectFeature() {
        assertTrue(VkFeaturePlan.of(true, true, true, true, true, true, true, true, true).lodDrawSupported());
        assertFalse(VkFeaturePlan.of(false, true, true, true, true, true, true, true, true).lodDrawSupported());
        assertFalse(VkFeaturePlan.of(true, false, true, true, true, true, true, true, true).lodDrawSupported());
        assertFalse(VkFeaturePlan.of(true, true, false, true, true, true, true, true, true).lodDrawSupported());
        assertFalse(VkFeaturePlan.of(true, true, true, false, true, true, true, true, true).lodDrawSupported());
    }

    /** Final review I1: raster.frag writes the visibility buffer and raster.vert declares it writable; both need store features. */
    @Test
    void lodDrawsNeedShaderStoresForTheCullRaster() {
        assertFalse(VkFeaturePlan.of(true, true, true, true, false, true, true, true, true).lodDrawSupported());
        assertFalse(VkFeaturePlan.of(true, true, true, true, true, false, true, true, true).lodDrawSupported());
        assertTrue(VkFeaturePlan.of(true, true, true, true, true, true, true, true, true).fragmentStoresAndAtomics());
        assertTrue(VkFeaturePlan.of(true, true, true, true, true, true, true, true, true).vertexPipelineStoresAndAtomics());
    }

    /** quads3.vert reads gl_BaseInstance (SPIR-V DrawParameters); Reforged enables the feature only with its own indirect draws. */
    @Test
    void lodDrawsNeedShaderDrawParameters() {
        assertFalse(VkFeaturePlan.of(true, true, true, true, true, true, false, true, true).lodDrawSupported());
        assertTrue(VkFeaturePlan.of(true, true, true, true, true, true, true, true, true).shaderDrawParameters());
        assertTrue(VkFeaturePlan.of(true, true, true, true, true, true, false, true, true).spikeSupported(), "the spike draws without gl_BaseInstance");
    }

    /** P7l design 3.2: the LOD pipelines' two colour attachments differ in write mask / blendEnable, which needs independentBlend. */
    @Test
    void lodDrawsNeedIndependentBlend() {
        VkFeaturePlan noBlend = VkFeaturePlan.of(true, true, true, true, true, true, true, false, true);
        assertFalse(noBlend.lodDrawSupported());
        assertFalse(noBlend.independentBlend());
        assertEquals(List.of("independentBlend"), noBlend.missing());
        assertTrue(noBlend.spikeSupported(), "the spike has a single colour attachment");
        assertTrue(VkFeaturePlan.of(true, true, true, true, true, true, true, true, true).independentBlend());
    }

    /** Frame generation paces submits with timeline semaphores; without the feature it stays off. */
    @Test
    void frameGenerationNeedsTimelineSemaphores() {
        VkFeaturePlan without = VkFeaturePlan.of(true, true, true, true, true, true, true, true, false);
        assertFalse(without.frameGenerationSupported());
        assertTrue(without.missing().contains("timelineSemaphore"));
        assertTrue(VkFeaturePlan.of(true, true, true, true, true, true, true, true, true).frameGenerationSupported());
    }
}
