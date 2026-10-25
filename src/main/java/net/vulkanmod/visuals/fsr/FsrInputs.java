package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.framegen.FrameGenTargets;
import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/**
 * FSR design 4.5 step 2: the pass that writes FSR's depth (R32_SFLOAT, near / view distance, inverted with an infinite far plane) and
 * camera motion vectors (R16G16_SFLOAT, previous UV minus current UV) at render size, from the main depth and the LOD depth
 * (fsr_inputs.comp; the maths is FsrInputMath). Only FSR's capture uses it (Task 7, when FSR is the active frame generation), so
 * nothing here runs while FSR is off.
 *
 * <p>Lifetime: {@link #ensure} makes the two images at the render size (the world size FrameGenTargets uses) and retires the old ones
 * through the caller's DeferredFree, which the caller ticks each frame. The loose uniforms do not fit in push constants, so they go
 * to a host-visible buffer per render frame in flight, written only after beginFrame waited for that frame index's previous frame
 * (as FrameGenTargets' capture entries). Render thread only.
 *
 * <p>Layouts: both images are rewritten whole every {@link #record} (UNDEFINED to GENERAL) and left in SHADER_READ_ONLY_OPTIMAL,
 * visible to compute shaders, where FSR's prepare dispatch reads them.
 */
public final class FsrInputs {
    /** The catalog name of fsr_inputs.comp (VisualsPrograms). */
    static final String PROGRAM = "fsrInputs";
    static final int DEPTH_FORMAT = VK_FORMAT_R32_SFLOAT, MOTION_FORMAT = VK_FORMAT_R16G16_SFLOAT;
    /** fsr_inputs.comp's bindings: the two outputs (images), the main and LOD depths (samplers), the loose-uniform block. */
    static final int DEPTH_OUT = 0, MOTION_OUT = 1, MAIN_DEPTH = 2, LOD_DEPTH = 3, LOOSE_UNIFORMS = 0;
    /** fsr_inputs.comp's loose uniform locations. */
    static final int INV_VANILLA_PROJ = 0, INV_LOD_PROJ = 1, PREV_FROM_VIEW = 2, DEPTH_PARAMS = 3, FSR_PARAMS = 4;
    /** Matches the shader's local_size (8, 8). */
    private static final int GROUP = 8;
    /** Written by the pass and read by FSR; TRANSFER_SRC as well, in case FSR copies an input as it copies the HUD-less colour. */
    static final int USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    private static final int FORMAT_FEATURES = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;

    private final DeferredFree deferred;
    private final VkComputeProgram program;
    private final LooseUniformWriter uniforms;
    /** One per render frame in flight (Renderer.getCurrentFrame), or null when the uniforms are push constants. */
    private final VkGpuBuffer[] uniformBuffers;
    private final long nearest;
    private VkImage2D depth, motion;

    /** Render thread. Throws FsrException when the device cannot write the two formats from a shader. */
    public FsrInputs(DeferredFree deferred) {
        requireStorage(DEPTH_FORMAT, "R32_SFLOAT");
        requireStorage(MOTION_FORMAT, "R16G16_SFLOAT");
        this.deferred = deferred;
        VkComputeProgram program = VkComputeProgram.of(PROGRAM);
        VkGpuBuffer[] buffers = null;
        try {
            this.uniforms = new LooseUniformWriter(program.meta());
            if (this.uniforms.inUniformBuffer()) {
                buffers = new VkGpuBuffer[FrameGenTargets.MAX_FRAMES_IN_FLIGHT];
                for (int i = 0; i < buffers.length; i++) {
                    buffers[i] = VkGpuBuffer.host(this.uniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            if (buffers != null) for (VkGpuBuffer b : buffers) if (b != null) b.free();
            program.free();
            throw e;
        }
        this.program = program;
        this.uniformBuffers = buffers;
        this.nearest = VkSamplers.nearestClamp();
    }

    private static void requireStorage(int format, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, props);
            if ((props.optimalTilingFeatures() & FORMAT_FEATURES) != FORMAT_FEATURES) {
                throw new FsrException("the device cannot use " + name + " as a storage image");
            }
        }
    }

    /**
     * Makes the depth and motion images width x height (the render size) and returns true when it replaced them; the old ones are
     * retired through DeferredFree. On a failure nothing half-built is kept and the old images stay. Render thread.
     */
    public boolean ensure(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("render size " + width + "x" + height);
        if (this.depth != null && this.depth.width == width && this.depth.height == height) return false;
        VkImage2D d = null, m;
        try {
            d = VkImage2D.create(DEPTH_FORMAT, width, height, 1, USAGE);
            m = VkImage2D.create(MOTION_FORMAT, width, height, 1, USAGE);
        } catch (RuntimeException e) {
            if (d != null) d.free();//never used: freed at once
            throw e;
        }
        this.retireImages();
        this.depth = d;
        this.motion = m;
        return true;
    }

    private void retireImages() {
        VkImage2D d = this.depth, m = this.motion;
        this.depth = this.motion = null;
        if (d != null) this.deferred.retire(() -> {
            d.free();
            m.free();
        }, VkFrames.serial());
    }

    /**
     * Render thread, outside a render pass, after {@link #ensure} with the main target's size: writes the depth and motion images.
     * The depth views must be sampleable (SHADER_READ_ONLY_OPTIMAL, made visible to compute shaders) and the main depth the size of the
     * images. lodDepthView 0 or lodValid false: the main depth alone (bound in the LOD depth's place). mainDepthView 0: no depth (a
     * menu), the colour view stands in for both depth bindings and every pixel is written as sky. prevFromView takes this frame's
     * view to the previous frame's clip space (TaaMath.targetFromView); invProj and invLodProj are this frame's unjittered inverse
     * projections (0..1 depth); near is the main projection's near plane in blocks (FSR's cameraNear).
     */
    public void record(VkCommandBuffer cmd, long colourView, long mainDepthView, long lodDepthView, boolean lodValid, Matrix4fc prevFromView,
                       Matrix4fc invProj, Matrix4fc invLodProj, float near) {
        if (this.depth == null) throw new IllegalStateException("FSR inputs: record before ensure");
        if (!(near > 0f)) throw new IllegalArgumentException("near plane " + near);
        boolean depthValid = mainDepthView != 0;
        boolean lod = depthValid && lodValid && lodDepthView != 0;
        long mainDepth = depthValid ? mainDepthView : colourView;
        long lodDepth = lod ? lodDepthView : mainDepth;

        this.uniforms.putMat4(INV_VANILLA_PROJ, invProj).putMat4(INV_LOD_PROJ, lod ? invLodProj : invProj).putMat4(PREV_FROM_VIEW, prevFromView)
                .putVec4(DEPTH_PARAMS, LodClamp.CLAMP_THRESHOLD, lod ? 1 : 0, 0, 0).putVec4(FSR_PARAMS, near, depthValid ? 1 : 0, 0, 0);
        VkBindings bindings = new VkBindings().storageImage(DEPTH_OUT, this.depth.view()).storageImage(MOTION_OUT, this.motion.view())
                .sampledView(MAIN_DEPTH, mainDepth, this.nearest).sampledView(LOD_DEPTH, lodDepth, this.nearest);
        ByteBuffer push = this.uniforms.buffer();
        if (this.uniformBuffers != null) {
            VkGpuBuffer buffer = this.uniformBuffers[frameIndex()];
            ByteBuffer dst = buffer.mapped().duplicate().order(ByteOrder.nativeOrder());
            dst.clear();
            dst.put(push);
            bindings.uniform(LOOSE_UNIFORMS, buffer);
            push = null;
        }

        //rewritten whole: discard (the previous frame's FSR prepare read them in compute shaders, same queue)
        for (VkImage2D i : new VkImage2D[]{this.depth, this.motion}) {
            i.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT);
        }
        this.program.dispatchWithPush(cmd, bindings, push, groups(this.depth.width), groups(this.depth.height), 1);
        for (VkImage2D i : new VkImage2D[]{this.depth, this.motion}) {
            i.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
    }

    private static int frameIndex() {
        int frame = Renderer.getCurrentFrame();
        if (frame < 0 || frame >= FrameGenTargets.MAX_FRAMES_IN_FLIGHT) {
            throw new IllegalStateException("render frame index " + frame + " (FSR inputs support " + FrameGenTargets.MAX_FRAMES_IN_FLIGHT + " frames in flight)");
        }
        return frame;
    }

    private static int groups(int pixels) {
        return (pixels + GROUP - 1) / GROUP;
    }

    /** FSR's depth: R32_SFLOAT, render size, near / view distance (0 for the sky); null before {@link #ensure}. */
    public VkImage2D depthImage() {
        return this.depth;
    }

    /** FSR's motion vectors: R16G16_SFLOAT, render size, previous UV minus current UV (motionVectorScale = render size); null before {@link #ensure}. */
    public VkImage2D motionImage() {
        return this.motion;
    }

    /**
     * Retires everything through DeferredFree (freed once this frame has completed, or by the owner's freeAll after a wait for the
     * device). Render thread; the object is unusable afterwards.
     */
    public void free() {
        this.retireImages();
        VkComputeProgram p = this.program;
        long sampler = this.nearest;
        VkGpuBuffer[] buffers = this.uniformBuffers;
        this.deferred.retire(() -> {
            p.free();
            VkSamplers.free(sampler);
            if (buffers != null) for (VkGpuBuffer b : buffers) b.free();
        }, VkFrames.serial());
    }
}
