package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** A visuals compute shader from the catalog, converted for Vulkan; every dispatch gets fresh descriptor sets from the frame ring. */
public final class VkComputeProgram {
    private final String name;
    private final VulkanShaderMeta meta;
    private final long[] setLayouts = new long[4];
    private long pipelineLayout, pipeline;

    private VkComputeProgram(String name, VulkanShaderMeta meta) {
        this.name = name;
        this.meta = meta;
    }

    public static VkComputeProgram of(String catalogName) {
        return of(ShaderCatalog.visuals(), catalogName);
    }

    public static VkComputeProgram of(ShaderCatalog catalog, String catalogName) {
        ShaderCatalog.Program p = catalog.get(catalogName).withPlatform(VkContext.shaderPlatform());
        ShaderCatalog.Stage stage = p.stages().get(0);
        if (stage.kind() != ShaderCatalog.StageKind.COMPUTE) throw new IllegalArgumentException(catalogName + " is not a compute program");
        GlslForVulkan.Converted c = GlslForVulkan.toVulkan(p.source(stage, ShaderSource.classpath()), p.defines());
        VkComputeProgram program = new VkComputeProgram(catalogName, c.meta());
        program.create(VkContext.spirv(catalogName + ".comp", c.source(), SPIRVUtils.ShaderKind.COMPUTE_SHADER));
        return program;
    }

    public VulkanShaderMeta meta() {
        return this.meta;
    }

    public static int[] setLayoutSizes(VulkanShaderMeta meta) {
        int[] sizes = new int[4];
        for (VulkanShaderMeta.Binding b : meta.bindings()) {
            if (b.set() >= 0 && b.set() < 4) sizes[b.set()] = Math.max(sizes[b.set()], b.binding() + 1);
        }
        return sizes;
    }

    private static int descriptorType(VulkanShaderMeta.Kind kind) {
        return switch (kind) {
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        };
    }

    private void create(ByteBuffer spirv) {
        VkDevice device = VkContext.device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int set = 0; set < 4; set++) {
                int count = 0;
                for (VulkanShaderMeta.Binding b : this.meta.bindings()) if (b.set() == set) count++;
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(Math.max(count, 1), stack);
                int i = 0;
                for (VulkanShaderMeta.Binding b : this.meta.bindings()) {
                    if (b.set() != set) continue;
                    bindings.get(i++).binding(b.binding()).descriptorType(descriptorType(b.kind())).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
                if (count > 0) info.pBindings(bindings.limit(count));
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateDescriptorSetLayout(device, info, null, p), "set layout " + set);
                this.setLayouts[set] = p.get(0);
            }
            LongBuffer p = stack.mallocLong(1);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(this.setLayouts));
            if (this.meta.pushConstantBytes() > 0) {
                layoutInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(this.meta.pushConstantBytes()));
            }
            check(vkCreatePipelineLayout(device, layoutInfo, null, p), "pipeline layout");
            this.pipelineLayout = p.get(0);

            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, p), "module");
            long module = p.get(0);
            try {
                VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().layout(this.pipelineLayout).stage(VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
                long pipelineStart = FrameRecorder.ON ? System.nanoTime() : 0;
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, p), "pipeline");
                if (FrameRecorder.ON) {
                    FrameRecorder.time(FrameRecorder.PIPELINE, pipelineStart);
                    FrameRecorder.add(FrameRecorder.PIPELINES, 1);
                }
                this.pipeline = p.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
        }
    }

    public void dispatch(VkCommandBuffer cmd, VkBindings bindings, PushConstantWriter push, int x, int y, int z) {
        this.dispatchWithPush(cmd, bindings, push == null ? null : push.buffer(), x, y, z);
    }

    /** With push constants from a LooseUniformWriter (or none); separate name so a null push stays unambiguous. */
    public void dispatchWithPush(VkCommandBuffer cmd, VkBindings bindings, ByteBuffer pushConstants, int x, int y, int z) {
        this.bind(cmd, bindings, pushConstants);
        vkCmdDispatch(cmd, x, y, z);
    }

    public void dispatchIndirect(VkCommandBuffer cmd, VkBindings bindings, PushConstantWriter push, VkGpuBuffer args, long offset) {
        this.bind(cmd, bindings, push == null ? null : push.buffer());
        vkCmdDispatchIndirect(cmd, args.handle(), offset);
    }

    /**
     * Allocates this program's descriptor sets from pool (the caller's own, never the frame ring) and writes bindings into them.
     * The sets stay valid until the pool is destroyed and are not rewritten by dispatchSets, so a thread other than the render
     * thread can dispatch them (frame generation's pacer). Whoever owns the pool.
     */
    public long[] allocateSets(long descriptorPool, VkBindings bindings) {
        bindings.validate(this.name, this.meta);
        long[] sets = VkBindings.allocate(descriptorPool, this.setLayouts);
        bindings.write(sets);
        return sets;
    }

    /** Binds the pipeline and sets from allocateSets (push may be null for a program without push constants) and dispatches; no ring involved. */
    public void dispatchSets(VkCommandBuffer cmd, long[] sets, ByteBuffer push, int x, int y, int z) {
        if (this.meta.pushConstantBytes() > 0 && push == null) throw new IllegalArgumentException(this.name + " needs push constants");
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, sets, null);
        if (this.meta.pushConstantBytes() > 0) {
            vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        }
        vkCmdDispatch(cmd, x, y, z);
    }

    private void bind(VkCommandBuffer cmd, VkBindings bindings, ByteBuffer pushConstants) {
        bindings.validate(this.name, this.meta);
        if (this.meta.pushConstantBytes() > 0 && pushConstants == null) throw new IllegalArgumentException(this.name + " needs push constants");
        long[] sets = new long[4];
        for (int set = 0; set < 4; set++) {
            sets[set] = VkDescriptorRing.INSTANCE.allocate(this.setLayouts[set]);
        }
        bindings.write(sets);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, sets, null);
        if (this.meta.pushConstantBytes() > 0) {
            vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);
        }
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        VkDevice device = VkContext.device();
        vkDestroyPipeline(device, this.pipeline, null);
        vkDestroyPipelineLayout(device, this.pipelineLayout, null);
        for (long layout : this.setLayouts) vkDestroyDescriptorSetLayout(device, layout, null);
    }

    private void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan " + this.name + " " + what + " failed: " + result);
    }
}
