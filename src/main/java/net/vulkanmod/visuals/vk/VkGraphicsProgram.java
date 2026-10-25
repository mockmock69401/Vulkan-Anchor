package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A visuals vertex+fragment program from the catalog, converted for Vulkan. One pipeline per render pass it is drawn in,
 * kept until free(); every bind gets fresh descriptor sets from the frame ring. Culling is off unless the state asks for back-face
 * culling (State.withBackFaceCulling, Task 5b: DH's LOD quads, as DH's own GL renderer culls them).
 */
public final class VkGraphicsProgram {
    public static final int RGBA = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    /** Fixed-function state; blending is the visuals' (colour: src alpha, one minus src alpha; alpha: one, one minus src alpha). */
    public record State(boolean depthTest, boolean depthWrite, int depthCompare, int colourWriteMask, boolean blend, int topology, boolean depthOnly,
                        boolean light, boolean depthBoundsAtFar, boolean cullBackFaces) {
        public State(boolean depthTest, boolean depthWrite, int depthCompare, int colourWriteMask, boolean blend, int topology, boolean depthOnly,
                     boolean light, boolean depthBoundsAtFar) {
            this(depthTest, depthWrite, depthCompare, colourWriteMask, blend, topology, depthOnly, light, depthBoundsAtFar, false);
        }

        public State(boolean depthTest, boolean depthWrite, int depthCompare, int colourWriteMask, boolean blend, int topology, boolean depthOnly,
                     boolean light) {
            this(depthTest, depthWrite, depthCompare, colourWriteMask, blend, topology, depthOnly, light, false);
        }

        public State(boolean depthTest, boolean depthWrite, int depthCompare, int colourWriteMask, boolean blend, int topology) {
            this(depthTest, depthWrite, depthCompare, colourWriteMask, blend, topology, false, false);
        }

        public State(boolean depthTest, boolean depthWrite, int depthCompare, int colourWriteMask, boolean blend, int topology, boolean depthOnly) {
            this(depthTest, depthWrite, depthCompare, colourWriteMask, blend, topology, depthOnly, false);
        }

        /** P7j design 3.2: a pass with a depth attachment only (the shadow maps): no colour blend attachment in the pipeline. */
        public static State depthOnly(int depthCompare, int topology) {
            return new State(true, true, depthCompare, 0, false, topology, true);
        }

        /** P7l design 3.2: the LOD passes' second colour attachment (VkOffscreenTarget.LIGHT_FORMAT), written where colour is, not blended. */
        public State withLight() {
            return new State(this.depthTest, this.depthWrite, this.depthCompare, this.colourWriteMask, this.blend, this.topology, this.depthOnly, true,
                    this.depthBoundsAtFar, this.cullBackFaces);
        }

        /**
         * The depth bounds test with both bounds at 1.0 (needs the depthBounds device feature, see VkFeatures): a fragment passes only
         * where the stored depth is still the clear value.
         */
        public State withDepthBoundsAtFar() {
            return new State(this.depthTest, this.depthWrite, this.depthCompare, this.colourWriteMask, this.blend, this.topology, this.depthOnly,
                    this.light, true, this.cullBackFaces);
        }

        /**
         * Task 5b: back faces culled. The front face is GL's (counter-clockwise) as it lands in a pass drawn with a positive viewport
         * (GL's memory layout, no y flip, as the LOD target is): clockwise in Vulkan's framebuffer coordinates.
         */
        public State withBackFaceCulling() {
            return new State(this.depthTest, this.depthWrite, this.depthCompare, this.colourWriteMask, this.blend, this.topology, this.depthOnly,
                    this.light, this.depthBoundsAtFar, true);
        }
    }

    /** The rasterizer's cull mode and front face for a state: none, or back faces with State.withBackFaceCulling's front face. */
    static int[] rasterCull(State state) {
        return state.cullBackFaces() ? new int[]{VK_CULL_MODE_BACK_BIT, VK_FRONT_FACE_CLOCKWISE} : new int[]{VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE};
    }

    /** The colour write mask of each blend attachment: none for depth-only, the light attachment's red where colour is written. */
    static int[] attachmentMasks(State state) {
        if (state.depthOnly()) return new int[0];
        if (!state.light()) return new int[]{state.colourWriteMask()};
        return new int[]{state.colourWriteMask(), state.colourWriteMask() == 0 ? 0 : VK_COLOR_COMPONENT_R_BIT};
    }

    /**
     * P7o design 4.2: vertices read from a bound vertex buffer (binding 0, per vertex): its stride and, per attribute, the shader
     * location, the VkFormat and the byte offset. the visuals' own programs pull from storage buffers and have none.
     */
    public record VertexLayout(int stride, int[] locations, int[] formats, int[] offsets) {
        public VertexLayout {
            if (locations.length != formats.length || locations.length != offsets.length) throw new IllegalArgumentException("attribute arrays differ in length");
        }
    }

    /** A binding of the merged layout with the stages that declare it. */
    public record StagedBinding(int set, int binding, VulkanShaderMeta.Kind kind, int stages) {
    }

    /**
     * Reforged's pipelines keep these dynamic and set them once per command buffer or render pass; binding a pipeline
     * with any of them static would leave that state undefined for Reforged's later draws.
     */
    static final int[] DYNAMIC_STATES = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_LINE_WIDTH};

    private final String name;
    private final State state;
    private final VertexLayout vertexLayout;//null: no vertex input
    private final List<StagedBinding> bindings;
    private final VulkanShaderMeta meta;
    private final int pushStage;
    private final long[] setLayouts = new long[4];
    private long pipelineLayout, vertModule, fragModule;
    private final List<Long> built = new ArrayList<>();
    private final PerRenderPassCache pipelines = new PerRenderPassCache(this::build);
    private static int livePipelines;

    /** Pipelines built and not yet destroyed, over every program (the stats line, G5a). */
    public static int livePipelines() {
        return livePipelines;
    }

    private VkGraphicsProgram(String name, State state, VertexLayout vertexLayout, VulkanShaderMeta vert, VulkanShaderMeta frag) {
        this.name = name;
        this.state = state;
        this.vertexLayout = vertexLayout;
        this.bindings = mergeBindings(vert, frag);
        int looseStage = looseUniformStage(vert, frag);
        VulkanShaderMeta loose = looseStage == VK_SHADER_STAGE_VERTEX_BIT ? vert : frag;
        this.meta = loose.withBindings(this.bindings.stream().map(b -> new VulkanShaderMeta.Binding(b.set(), b.binding(), b.kind())).toList());
        this.pushStage = this.meta.pushConstantBytes() > 0 ? looseStage : 0;
    }

    public static VkGraphicsProgram of(String catalogName, State state) {
        return of(ShaderCatalog.visuals(), catalogName, state, null);
    }

    public static VkGraphicsProgram of(ShaderCatalog catalog, String catalogName, State state) {
        return of(catalog, catalogName, state, null);
    }

    /** P7o design 4.2: a program fed from a bound vertex buffer laid out as vertexLayout (null: none). */
    public static VkGraphicsProgram of(String catalogName, State state, VertexLayout vertexLayout) {
        return of(ShaderCatalog.visuals(), catalogName, state, vertexLayout);
    }

    public static VkGraphicsProgram of(ShaderCatalog catalog, String catalogName, State state, VertexLayout vertexLayout) {
        ShaderCatalog.Program p = catalog.get(catalogName).withPlatform(VkContext.shaderPlatform());
        GlslForVulkan.Converted[] c = convert(p);
        VkGraphicsProgram program = new VkGraphicsProgram(catalogName, state, vertexLayout, c[0].meta(), c[1].meta());
        program.create(VkContext.spirv(catalogName + ".vert", c[0].source(), SPIRVUtils.ShaderKind.VERTEX_SHADER),
                VkContext.spirv(catalogName + ".frag", c[1].source(), SPIRVUtils.ShaderKind.FRAGMENT_SHADER));
        return program;
    }

    /** The vertex and fragment stages of p, converted (catalog order: vertex first). */
    static GlslForVulkan.Converted[] convert(ShaderCatalog.Program p) {
        if (p.stages().size() != 2 || p.stages().get(0).kind() != ShaderCatalog.StageKind.VERTEX
                || p.stages().get(1).kind() != ShaderCatalog.StageKind.FRAGMENT) {
            throw new IllegalArgumentException(p.name() + " is not a vertex+fragment program");
        }
        return new GlslForVulkan.Converted[]{
                GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()),
                GlslForVulkan.toVulkan(p.source(p.stages().get(1), ShaderSource.classpath()), p.defines())};
    }

    /** Both stages' bindings in one layout; a set:binding both declare must be the same kind. */
    static List<StagedBinding> mergeBindings(VulkanShaderMeta vert, VulkanShaderMeta frag) {
        Map<String, StagedBinding> merged = new TreeMap<>();
        add(merged, vert, VK_SHADER_STAGE_VERTEX_BIT);
        add(merged, frag, VK_SHADER_STAGE_FRAGMENT_BIT);
        return List.copyOf(merged.values());
    }

    private static void add(Map<String, StagedBinding> merged, VulkanShaderMeta meta, int stage) {
        for (VulkanShaderMeta.Binding b : meta.bindings()) {
            String key = b.set() + ":" + b.binding();
            StagedBinding prev = merged.get(key);
            if (prev == null) {
                merged.put(key, new StagedBinding(b.set(), b.binding(), b.kind(), stage));
            } else if (prev.kind() != b.kind()) {
                throw new IllegalArgumentException(key + " is " + prev.kind() + " in one stage and " + b.kind() + " in the other");
            } else {
                merged.put(key, new StagedBinding(b.set(), b.binding(), b.kind(), prev.stages() | stage));
            }
        }
    }

    /** The stage whose loose uniforms the program has (0 for none). the visuals' P2b programs declare them in one stage only. */
    static int looseUniformStage(VulkanShaderMeta vert, VulkanShaderMeta frag) {
        if (vert.pushSize() > 0 && frag.pushSize() > 0) {
            throw new IllegalArgumentException("loose uniforms in both stages are not supported");
        }
        if (vert.pushSize() > 0) return VK_SHADER_STAGE_VERTEX_BIT;
        if (frag.pushSize() > 0) return VK_SHADER_STAGE_FRAGMENT_BIT;
        return 0;
    }

    /** A writer for this program's loose uniforms, or null when it has none. */
    public LooseUniformWriter looseUniforms() {
        return this.meta.pushSize() == 0 ? null : new LooseUniformWriter(this.meta);
    }

    private static int descriptorType(VulkanShaderMeta.Kind kind) {
        return switch (kind) {
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        };
    }

    private void create(ByteBuffer vertSpirv, ByteBuffer fragSpirv) {
        VkDevice device = VkContext.device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int set = 0; set < 4; set++) {
                int count = 0;
                for (StagedBinding b : this.bindings) if (b.set() == set) count++;
                VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(Math.max(count, 1), stack);
                int i = 0;
                for (StagedBinding b : this.bindings) {
                    if (b.set() != set) continue;
                    layoutBindings.get(i++).binding(b.binding()).descriptorType(descriptorType(b.kind())).descriptorCount(1).stageFlags(b.stages());
                }
                VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
                if (count > 0) info.pBindings(layoutBindings.limit(count));
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateDescriptorSetLayout(device, info, null, p), "set layout " + set);
                this.setLayouts[set] = p.get(0);
            }
            LongBuffer p = stack.mallocLong(1);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(this.setLayouts));
            if (this.pushStage != 0) {
                layoutInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(this.pushStage).offset(0).size(this.meta.pushConstantBytes()));
            }
            check(vkCreatePipelineLayout(device, layoutInfo, null, p), "pipeline layout");
            this.pipelineLayout = p.get(0);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(vertSpirv), null, p), "vertex module");
            this.vertModule = p.get(0);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(fragSpirv), null, p), "fragment module");
            this.fragModule = p.get(0);
        }
    }

    private long build(long renderPass) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(this.vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(this.fragModule).pName(stack.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (this.vertexLayout != null) {
                VertexLayout v = this.vertexLayout;
                vertexInput.pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(v.stride())
                        .inputRate(VK_VERTEX_INPUT_RATE_VERTEX));
                VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(v.locations().length, stack);
                for (int i = 0; i < v.locations().length; i++) {
                    attributes.get(i).location(v.locations()[i]).binding(0).format(v.formats()[i]).offset(v.offsets()[i]);
                }
                vertexInput.pVertexAttributeDescriptions(attributes);
            }
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(this.state.topology()).primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
            int[] cull = rasterCull(this.state);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(cull[0]).frontFace(cull[1])
                    .depthBiasEnable(false).lineWidth(1.0f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(this.state.depthTest()).depthWriteEnable(this.state.depthWrite()).depthCompareOp(this.state.depthCompare())
                    .depthBoundsTestEnable(this.state.depthBoundsAtFar()).minDepthBounds(1.0f).maxDepthBounds(1.0f).stencilTestEnable(false);
            int[] masks = attachmentMasks(this.state);
            VkPipelineColorBlendAttachmentState.Buffer attachment = VkPipelineColorBlendAttachmentState.calloc(Math.max(masks.length, 1), stack);
            if (masks.length > 0) {
                attachment.get(0).colorWriteMask(masks[0]).blendEnable(this.state.blend());
                if (this.state.blend()) {
                    attachment.get(0).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .alphaBlendOp(VK_BLEND_OP_ADD);
                }
            }
            //P7l design 3.2: integer attachments cannot blend
            if (masks.length > 1) attachment.get(1).colorWriteMask(masks[1]).blendEnable(false);
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().logicOpEnable(false);
            if (masks.length > 0) blend.pAttachments(attachment);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(DYNAMIC_STATES));
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(assembly).pViewportState(viewport)
                    .pRasterizationState(raster).pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(blend)
                    .pDynamicState(dynamic).layout(this.pipelineLayout).renderPass(renderPass).subpass(0);
            LongBuffer p = stack.mallocLong(1);
            long pipelineStart = FrameRecorder.ON ? System.nanoTime() : 0;
            check(vkCreateGraphicsPipelines(VkContext.device(), VK_NULL_HANDLE, info, null, p), "pipeline");
            if (FrameRecorder.ON) {
                FrameRecorder.time(FrameRecorder.PIPELINE, pipelineStart);
                FrameRecorder.add(FrameRecorder.PIPELINES, 1);
            }
            this.built.add(p.get(0));
            livePipelines++;
            return p.get(0);
        }
    }

    /** Inside renderPass: binds its pipeline, fresh descriptor sets and the push constants (when the program has them). */
    public void bind(VkCommandBuffer cmd, long renderPass, VkBindings bindings, LooseUniformWriter loose) {
        this.bind(cmd, renderPass, renderPass, bindings, loose);
    }

    /** Inside renderPass, sharing the pipeline between compatible passes (PerRenderPassCache.compatibilityKey). */
    public void bind(VkCommandBuffer cmd, long renderPass, long compatibilityKey, VkBindings bindings, LooseUniformWriter loose) {
        bindings.validate(this.name, this.meta);
        if (this.pushStage != 0 && loose == null) throw new IllegalArgumentException(this.name + " needs push constants");
        long[] sets = new long[4];
        for (int set = 0; set < 4; set++) {
            sets[set] = VkDescriptorRing.INSTANCE.allocate(this.setLayouts[set]);
        }
        bindings.write(sets);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelines.get(compatibilityKey, renderPass));
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0, sets, null);
        if (this.pushStage != 0) {
            vkCmdPushConstants(cmd, this.pipelineLayout, this.pushStage, 0, loose.buffer());
        }
        //Depth bias is off and nothing is drawn as lines; define both so they hold whatever Reforged bound before
        vkCmdSetDepthBias(cmd, 0, 0, 0);
        vkCmdSetLineWidth(cmd, 1.0f);
    }

    /**
     * Builds the pipeline for renderPass now, on the calling thread, so a later bindSets on another thread finds it cached. Call it
     * from the thread that owns the program before the pacer starts.
     */
    public void prepare(long renderPass) {
        this.pipelines.get(renderPass, renderPass);
    }

    /**
     * Allocates this program's descriptor sets from pool (the caller's own, never the frame ring) and writes bindings into them;
     * valid until the pool is destroyed and never rewritten by bindSets (frame generation's pacer thread uses them).
     */
    public long[] allocateSets(long descriptorPool, VkBindings bindings) {
        bindings.validate(this.name, this.meta);
        long[] sets = VkBindings.allocate(descriptorPool, this.setLayouts);
        bindings.write(sets);
        return sets;
    }

    /**
     * Inside renderPass: binds its pipeline (built on first use, so prepare it first when this is not the owning thread) and the
     * sets from allocateSets; no ring, no push constants (the program must have none).
     */
    public void bindSets(VkCommandBuffer cmd, long renderPass, long[] sets) {
        if (this.pushStage != 0) throw new IllegalStateException(this.name + " has push constants: bindSets cannot supply them");
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelines.get(renderPass, renderPass));
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0, sets, null);
        vkCmdSetDepthBias(cmd, 0, 0, 0);
        vkCmdSetLineWidth(cmd, 1.0f);
    }

    /** The merged loose-uniform layout and bindings (a LooseUniformWriter for this program is built from it). */
    public VulkanShaderMeta meta() {
        return this.meta;
    }

    /** P7o design 4.3: after bind, the push constants again (the writer's values changed between draws). */
    public void pushConstants(VkCommandBuffer cmd, LooseUniformWriter loose) {
        if (this.pushStage == 0) throw new IllegalStateException(this.name + " has no push constants");
        vkCmdPushConstants(cmd, this.pipelineLayout, this.pushStage, 0, loose.buffer());
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        VkDevice device = VkContext.device();
        for (long pipeline : this.built) vkDestroyPipeline(device, pipeline, null);
        livePipelines -= this.built.size();
        vkDestroyShaderModule(device, this.vertModule, null);
        vkDestroyShaderModule(device, this.fragModule, null);
        vkDestroyPipelineLayout(device, this.pipelineLayout, null);
        for (long layout : this.setLayouts) vkDestroyDescriptorSetLayout(device, layout, null);
    }

    private void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan " + this.name + " " + what + " failed: " + result);
    }
}
