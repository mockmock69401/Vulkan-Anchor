package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * W9: the TAA pass (post_taa.comp, TaaMath) on the final colour. Two full-resolution rgba16f images take turns: one holds the
 * history (the last result: colour and linear depth), the other receives this frame's result, which SMAA and the blit read and
 * the next frame takes as its history. TaaHistory decides whether the history may be blended; when not, the pass writes the
 * current image unchanged (and its depths, so the next frame has a history).
 */
public final class VkTaa {
    /** This frame's camera (boundary 2): the inverse projections as drawn (jittered), the inverse model view, the position. */
    public record Frame(Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f invModelView, double cameraX, double cameraY,
                        double cameraZ, boolean lodValid, float vanillaDistanceBlocks) {
    }

    static final int FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    //boundary.md 3.5
    static final int OUT = 0, CURRENT = 1, HISTORY = 2, MAIN_DEPTH = 3, LOD_DEPTH = 4, OPAQUE_DEPTH = 5, LOOSE_UNIFORMS = 0;
    private static final int COMPUTE_AND_FRAGMENT = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private final VkComputeProgram program;
    private final LooseUniformWriter uniforms;
    private final VkGpuBuffer uniformBuffer;
    private final TaaHistory history = new TaaHistory();
    private final VkImage2D[] images = new VkImage2D[2];
    private int historyIndex;//images[historyIndex] holds the history, the other one is written
    private int width, height;
    private TaaMath.Camera pendingCamera;//prepare()'s camera, the history's once compute() has written it

    public VkTaa() {
        VkComputeProgram program = VkComputeProgram.of("postTaa");
        VkGpuBuffer buffer = null;
        try {
            this.uniforms = new LooseUniformWriter(program.meta());
            if (this.uniforms.inUniformBuffer()) {
                buffer = VkGpuBuffer.device(this.uniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
        } catch (RuntimeException | LinkageError e) {
            program.free();
            throw e;
        }
        this.program = program;
        this.uniformBuffer = buffer;
    }

    /** The screen size; a new one discards the history. The images follow on the next frame that runs TAA. */
    public void ensureSize(int width, int height, DeferredFree deferred) {
        this.history.resize(width, height);
        if (this.width == width && this.height == height) return;
        this.width = width;
        this.height = height;
        if (this.images[0] == null) return;
        VkImage2D[] old = this.images.clone();
        this.images[0] = this.images[1] = null;
        deferred.retire(() -> {
            for (VkImage2D i : old) if (i != null) i.free();
        }, VkFrames.serial());
    }

    /** Before the frame's upload commit: this frame's uniforms, from its camera and the previous TAA frame's. */
    public void prepare(VkUploadStream upload, Frame frame) {
        boolean valid = this.images[0] != null && this.history.usable(VkFrames.serial(), VkFrames::isDropped);
        float[] jitter = TaaJitter.appliedNdc();
        TaaMath.compute(frame, jitter, this.history.camera(), valid).write(this.uniforms);
        if (this.uniformBuffer != null) upload.upload(this.uniformBuffer, 0, this.uniforms.buffer());
        this.pendingCamera = TaaMath.camera(frame, jitter);
    }

    private void allocate() {
        if (this.images[0] != null) return;
        if (this.width <= 0 || this.height <= 0) throw new IllegalStateException("TAA: no screen size");
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        VkImage2D a = null, b = null;
        try {
            a = VkImage2D.create(FORMAT, this.width, this.height, 1, usage);
            b = VkImage2D.create(FORMAT, this.width, this.height, 1, usage);
        } catch (RuntimeException e) {
            if (a != null) a.free();//never used: freed at once
            throw e;
        }
        this.images[0] = a;
        this.images[1] = b;
    }

    /**
     * Outside a render pass, after prepare() and the upload commit: current (readable) blended with the history into the other
     * image, returned readable. The depth views are in SHADER_READ_ONLY_OPTIMAL (boundary 2).
     */
    public VkImage2D compute(VkCommandBuffer cmd, VkImage2D current, long mainDepthView, long lodDepthView, long opaqueDepthView, long nearestSampler,
                             long linearSampler) {
        if (this.pendingCamera == null) throw new IllegalStateException("TAA: compute without prepare");
        //New images come only after a resize or on the first frame, whose history TaaHistory already refused in prepare()
        this.allocate();
        VkImage2D historyImage = this.images[this.historyIndex], out = this.images[1 - this.historyIndex];
        VkGpuTimer.mark(cmd, "taa");
        if (historyImage.layout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            //Never written (or written by a dropped frame): bound, not read (the uniforms say no history)
            historyImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT);
        }
        out.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        VkBindings bindings = new VkBindings().storageImage(OUT, out.view()).sampled(CURRENT, current, nearestSampler)
                .sampled(HISTORY, historyImage, linearSampler).sampledView(MAIN_DEPTH, mainDepthView, nearestSampler)
                .sampledView(LOD_DEPTH, lodDepthView, nearestSampler).sampledView(OPAQUE_DEPTH, opaqueDepthView, nearestSampler);
        if (this.uniformBuffer != null) bindings.uniform(LOOSE_UNIFORMS, this.uniformBuffer);
        this.program.dispatchWithPush(cmd, bindings, this.uniformBuffer == null ? this.uniforms.buffer() : null, groups(this.width), groups(this.height), 1);
        out.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                COMPUTE_AND_FRAGMENT, VK_ACCESS_SHADER_READ_BIT);
        this.historyIndex = 1 - this.historyIndex;
        this.history.written(VkFrames.serial(), this.pendingCamera);
        this.pendingCamera = null;
        return out;
    }

    private static int groups(int pixels) {
        return (pixels + 7) / 8;
    }

    /** A frame without TAA: the history is no longer valid. */
    public void skipped() {
        this.history.discard();
        this.pendingCamera = null;
    }

    /** Call when a frame starts recording: transitions of a dropped frame are undone. */
    public void tick() {
        for (VkImage2D i : this.images) if (i != null) i.tick();
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        this.program.free();
        if (this.uniformBuffer != null) this.uniformBuffer.free();
        for (VkImage2D i : this.images) if (i != null) i.free();
        this.images[0] = this.images[1] = null;
    }
}
