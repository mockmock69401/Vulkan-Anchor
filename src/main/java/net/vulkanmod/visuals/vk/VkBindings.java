package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/** The resources of one dispatch, addressed by the GL binding numbers the visuals' Java code uses (sets follow the resource kind). */
public final class VkBindings {
    public record Entry(VulkanShaderMeta.Kind kind, int binding, long buffer, long offset, long range, long imageView, long sampler) {
        public int set() {
            return switch (this.kind) {
                case STORAGE_BUFFER -> GlslForVulkan.SET_STORAGE;
                case UNIFORM_BUFFER -> GlslForVulkan.SET_UNIFORM;
                case SAMPLER -> GlslForVulkan.SET_SAMPLER;
                case IMAGE -> GlslForVulkan.SET_IMAGE;
            };
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public VkBindings storage(int glBinding, VkGpuBuffer buffer) {
        return this.add(new Entry(VulkanShaderMeta.Kind.STORAGE_BUFFER, glBinding, buffer.handle(), 0, VK_WHOLE_SIZE, 0, 0));
    }

    public VkBindings storage(int glBinding, VkUploadStream.RawSlice slice) {
        return this.add(new Entry(VulkanShaderMeta.Kind.STORAGE_BUFFER, glBinding, slice.buffer().handle(), slice.offset(), slice.size(), 0, 0));
    }

    /** A range of buffer; offset must meet the device's storage offset alignment (at most 256). */
    public VkBindings storage(int glBinding, VkGpuBuffer buffer, long offset, long range) {
        return this.add(new Entry(VulkanShaderMeta.Kind.STORAGE_BUFFER, glBinding, buffer.handle(), offset, range, 0, 0));
    }

    public VkBindings uniform(int glBinding, VkGpuBuffer buffer) {
        return this.add(new Entry(VulkanShaderMeta.Kind.UNIFORM_BUFFER, glBinding, buffer.handle(), 0, VK_WHOLE_SIZE, 0, 0));
    }

    public VkBindings sampled(int glBinding, VkImage2D image, long sampler) {
        return this.sampledView(glBinding, image.sampledView(), sampler);
    }

    /** A view the visuals does not own (Reforged's lightmap or main depth), in SHADER_READ_ONLY_OPTIMAL when drawn. */
    public VkBindings sampledView(int glBinding, long imageView, long sampler) {
        return this.add(new Entry(VulkanShaderMeta.Kind.SAMPLER, glBinding, 0, 0, 0, imageView, sampler));
    }

    /** A storage image (GL glBindImageTexture), written in GENERAL layout. */
    public VkBindings storageImage(int glBinding, long imageView) {
        return this.add(new Entry(VulkanShaderMeta.Kind.IMAGE, glBinding, 0, 0, 0, imageView, 0));
    }

    VkBindings add(Entry entry) {
        this.entries.add(entry);
        return this;
    }

    /** Every resource the converted shader declares is provided exactly once with the declared kind, and nothing else. */
    public void validate(String program, VulkanShaderMeta meta) {
        Map<String, VulkanShaderMeta.Kind> declared = new HashMap<>();
        for (VulkanShaderMeta.Binding b : meta.bindings()) declared.put(b.set() + ":" + b.binding(), b.kind());
        Set<String> provided = new HashSet<>();
        for (Entry e : this.entries) {
            String key = e.set() + ":" + e.binding();
            VulkanShaderMeta.Kind kind = declared.get(key);
            if (kind == null) throw new IllegalArgumentException(program + ": " + key + " (" + e.kind() + ") is not declared by the shader");
            if (kind != e.kind()) throw new IllegalArgumentException(program + ": " + key + " is " + kind + ", got " + e.kind());
            if (!provided.add(key)) throw new IllegalArgumentException(program + ": " + key + " provided twice");
        }
        for (Map.Entry<String, VulkanShaderMeta.Kind> d : declared.entrySet()) {
            if (!provided.contains(d.getKey())) throw new IllegalArgumentException(program + ": " + d.getKey() + " (" + d.getValue() + ") not provided");
        }
    }

    /**
     * One set per layout from pool (a pool of the caller's, not the frame ring), for the fixed-set programs of threads that must not
     * touch the ring (frame generation's pacer). Any thread that owns the pool.
     */
    static long[] allocate(long pool, long[] setLayouts) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] sets = new long[setLayouts.length];
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < setLayouts.length; i++) {
                int result = vkAllocateDescriptorSets(VkContext.device(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pool).pSetLayouts(stack.longs(setLayouts[i])), p);
                if (result != VK_SUCCESS) throw new IllegalStateException("vkAllocateDescriptorSets failed: " + result + " (pool exhausted?)");
                sets[i] = p.get(0);
            }
            return sets;
        }
    }

    void write(long[] sets) {
        if (this.entries.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(this.entries.size(), stack);
            for (int i = 0; i < this.entries.size(); i++) {
                Entry e = this.entries.get(i);
                VkWriteDescriptorSet w = writes.get(i).sType$Default().dstSet(sets[e.set()]).dstBinding(e.binding()).descriptorCount(1);
                switch (e.kind()) {
                    case STORAGE_BUFFER, UNIFORM_BUFFER -> w.descriptorType(e.kind() == VulkanShaderMeta.Kind.STORAGE_BUFFER
                                    ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(e.buffer()).offset(e.offset()).range(e.range()));
                    case SAMPLER -> w.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(e.sampler()).imageView(e.imageView())
                                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                    case IMAGE -> w.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(e.imageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
            }
            vkUpdateDescriptorSets(VkContext.device(), writes, null);
        }
    }
}
