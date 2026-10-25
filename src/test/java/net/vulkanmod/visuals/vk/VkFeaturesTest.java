package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1: the depthBounds device feature is turned on only when Distant Horizons has its render API (the LOD composite is its only user);
 * without it the device is created as before and the composite, if any, keeps its depth-test fallback.
 */
class VkFeaturesTest {
    @Test
    void depthBoundsNeedsTheFeatureAndADhRenderer() {
        assertTrue(VkFeatures.enablesDepthBounds(true, true));
        assertFalse(VkFeatures.enablesDepthBounds(true, false));
        assertFalse(VkFeatures.enablesDepthBounds(false, true));
        assertFalse(VkFeatures.enablesDepthBounds(false, false));
    }

    @Test
    void theDeviceAsksDhsPresenceBeforeTurningItOn() throws Exception {
        List<String> calls = Bytecode.calls("net/vulkanmod/vulkan/device/DeviceManager", "visualsFeaturePlan");
        assertTrue(Bytecode.inOrder(calls, List.of("DhPresence.drawsLods", "VkFeatures.enablesDepthBounds", "VkFeatures.setDepthBounds")), calls.toString());
    }
}
