package net.vulkanmod.visuals.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * One descriptor pool per frame slot, reset when that slot starts recording (Reforged has waited its fence, so no
 * submitted frame still uses the sets). Every dispatch allocates fresh sets, so recorded sets are never rewritten.
 * 2026-10-30 (audit): a frame that uses up its pool gets another one for that slot (it threw mid-recording before).
 */
public final class VkDescriptorRing {
    public static final VkDescriptorRing INSTANCE = new VkDescriptorRing();
    private static final int MAX_SETS = 1024;

    private final Map<Integer, List<Long>> pools = new HashMap<>();
    private List<Long> slotPools;
    private int index;
    private long current;

    private VkDescriptorRing() {
    }

    public void onRecordingStarted(int slot) {
        List<Long> list = this.pools.computeIfAbsent(slot, s -> new ArrayList<>());
        if (list.isEmpty()) list.add(createPool());
        for (long pool : list) vkResetDescriptorPool(VkContext.device(), pool, 0);
        this.slotPools = list;
        this.index = 0;
        this.current = list.get(0);
    }

    public long allocate(long setLayout) {
        if (this.current == 0) throw new IllegalStateException("descriptor ring used outside a recorded frame");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            for (int tries = 0; ; tries++) {
                int result = vkAllocateDescriptorSets(VkContext.device(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(this.current).pSetLayouts(stack.longs(setLayout)), p);
                if (result == VK_SUCCESS) return p.get(0);
                boolean full = result == VK11.VK_ERROR_OUT_OF_POOL_MEMORY || result == VK_ERROR_FRAGMENTED_POOL;
                if (!full || tries > 0) throw new IllegalStateException("vkAllocateDescriptorSets failed: " + result + " (frame pool exhausted?)");
                this.nextPool();
            }
        }
    }

    /** The slot's next pool (made when it has none yet), already reset at this slot's recording start. */
    private void nextPool() {
        this.index++;
        if (this.index == this.slotPools.size()) this.slotPools.add(createPool());
        this.current = this.slotPools.get(this.index);
    }

    private static long createPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4096);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(512);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(512);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(512);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(VkContext.device(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(MAX_SETS).pPoolSizes(sizes), null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateDescriptorPool failed: " + result);
            return p.get(0);
        }
    }
}
