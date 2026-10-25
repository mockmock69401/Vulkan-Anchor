package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PushConstantWriterTest {
    private static final VulkanShaderMeta META = new VulkanShaderMeta(List.of(
            new VulkanShaderMeta.PushMember("count", "uint", 0, 0),
            new VulkanShaderMeta.PushMember("queueIdx", "uint", 5, 4),
            new VulkanShaderMeta.PushMember("scale", "float", 6, 8)), 16, List.of());

    @Test
    void valuesLandAtTheirMembersOffsets() {
        ByteBuffer b = new PushConstantWriter(META).putInt(5, 3).putFloat(6, 0.5f).putInt(0, 9).buffer();
        assertEquals(16, b.remaining());
        assertEquals(9, b.getInt(0));
        assertEquals(3, b.getInt(4));
        assertEquals(0.5f, b.getFloat(8));
    }

    @Test
    void unknownLocationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PushConstantWriter(META).putInt(4, 1));
    }

    @Test
    void uniformBlockFallbackHasNoPushConstants() {
        VulkanShaderMeta inUbo = new VulkanShaderMeta(META.push(), 144, true, List.of());
        assertThrows(IllegalArgumentException.class, () -> new PushConstantWriter(inUbo));
    }
}
