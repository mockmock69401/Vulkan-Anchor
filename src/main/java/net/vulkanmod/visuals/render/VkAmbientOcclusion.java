package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * W10: the ambient occlusion's three half-resolution passes (ao.glsl): the view distances (postAoDepth), the horizon search
 * (postAo) and the blur (postAoBlur) into the image the composite reads at its binding 13. They run after the lighting pass, whose
 * normals and translucent kinds they read, and only when AmbientOcclusion.runs; otherwise the composite's image is only moved to the
 * read layout (the composite does not read it then).
 */
public final class VkAmbientOcclusion {
    static final int DEPTH_FORMAT = VK_FORMAT_R32_SFLOAT;
    static final int RAW_FORMAT = VK_FORMAT_R32_SFLOAT;
    static final int OUT_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    //ao_depth.comp
    static final int DST = 0, DEPTH_MAIN_COLOUR = 1, DEPTH_MAIN_DEPTH = 2, DEPTH_LOD_DEPTH = 3, DEPTH_LIGHT = 4;
    //ao.comp
    static final int OCCLUSION_DEPTH = 1, OCCLUSION_SUN = 2, OCCLUSION_LIGHT = 3;
    //ao_blur.comp
    static final int BLUR_RAW = 1, BLUR_DEPTH = 2, BLUR_SUN = 3;
    static final int LOOSE_UNIFORMS = 0;
    private static final int COMPUTE_AND_FRAGMENT = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private final VkComputeProgram depth, occlusion, blur;
    private final LooseUniformWriter depthUniforms, occlusionUniforms;
    private final VkGpuBuffer depthUniformBuffer, occlusionUniformBuffer;
    private VkImage2D depthImage, rawImage, outImage;
    private int width, height;
    private boolean on;

    public VkAmbientOcclusion() {
        VkComputeProgram depth = null, occlusion = null, blur = null;
        VkGpuBuffer depthBuffer = null, occlusionBuffer = null;
        try {
            depth = VkComputeProgram.of("postAoDepth");
            occlusion = VkComputeProgram.of("postAo");
            blur = VkComputeProgram.of("postAoBlur");
            if (blur.meta().pushSize() != 0) throw new IllegalStateException("postAoBlur: expected no loose uniforms");
            this.depthUniforms = new LooseUniformWriter(depth.meta());
            if (this.depthUniforms.inUniformBuffer()) {
                depthBuffer = VkGpuBuffer.device(this.depthUniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
            this.occlusionUniforms = new LooseUniformWriter(occlusion.meta());
            if (this.occlusionUniforms.inUniformBuffer()) {
                occlusionBuffer = VkGpuBuffer.device(this.occlusionUniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
        } catch (RuntimeException | LinkageError e) {
            for (VkComputeProgram p : new VkComputeProgram[]{depth, occlusion, blur}) if (p != null) p.free();
            if (depthBuffer != null) depthBuffer.free();
            if (occlusionBuffer != null) occlusionBuffer.free();
            throw e;
        }
        this.depth = depth;
        this.occlusion = occlusion;
        this.blur = blur;
        this.depthUniformBuffer = depthBuffer;
        this.occlusionUniformBuffer = occlusionBuffer;
    }

    /** The full-resolution size; the half-resolution images (AmbientOcclusion.halfSize) are made again when it changes. */
    public void ensureSize(int width, int height, DeferredFree deferred) {
        if (this.outImage != null && this.width == width && this.height == height) return;
        if (this.outImage != null) {
            VkImage2D[] old = {this.depthImage, this.rawImage, this.outImage};
            deferred.retire(() -> {
                for (VkImage2D i : old) if (i != null) i.free();
            }, VkFrames.serial());
            this.depthImage = this.rawImage = this.outImage = null;
        }
        int w = AmbientOcclusion.halfSize(width), h = AmbientOcclusion.halfSize(height);
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        VkImage2D depthImage = null, rawImage = null, outImage = null;
        try {
            depthImage = VkImage2D.create(DEPTH_FORMAT, w, h, 1, usage);
            rawImage = VkImage2D.create(RAW_FORMAT, w, h, 1, usage);
            outImage = VkImage2D.create(OUT_FORMAT, w, h, 1, usage);
        } catch (RuntimeException e) {
            for (VkImage2D i : new VkImage2D[]{depthImage, rawImage, outImage}) if (i != null) i.free();//never used: freed at once
            throw e;
        }
        this.depthImage = depthImage;
        this.rawImage = rawImage;
        this.outImage = outImage;
        this.width = width;
        this.height = height;
    }

    /** Before the frame's upload commit: whether the passes run this frame, and their uniforms. */
    public void prepare(VkUploadStream upload, LightingUniforms.Values lighting) {
        this.on = AmbientOcclusion.runs(lighting);
        if (!this.on) return;
        AmbientOcclusion.writeDepth(this.depthUniforms, lighting, this.width, this.height);
        if (this.depthUniformBuffer != null) upload.upload(this.depthUniformBuffer, 0, this.depthUniforms.buffer());
        AmbientOcclusion.writeOcclusion(this.occlusionUniforms, lighting, this.width, this.height);
        if (this.occlusionUniformBuffer != null) upload.upload(this.occlusionUniformBuffer, 0, this.occlusionUniforms.buffer());
    }

    /**
     * Outside a render pass, after the lighting pass (its light and sun images readable) and before the composite. Leaves output()
     * readable whether the passes ran or not.
     */
    public void compute(VkCommandBuffer cmd, long mainColourView, long mainDepthView, long lodDepthView, VkImage2D lightImage, VkImage2D sunImage,
                        long nearest) {
        if (!this.on) {
            this.outImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT);
            return;
        }
        int gx = AmbientOcclusion.groups(this.outImage.width), gy = AmbientOcclusion.groups(this.outImage.height);
        toWrite(cmd, this.depthImage);
        VkBindings depthBindings = new VkBindings().storageImage(DST, this.depthImage.view()).sampledView(DEPTH_MAIN_COLOUR, mainColourView, nearest)
                .sampledView(DEPTH_MAIN_DEPTH, mainDepthView, nearest).sampledView(DEPTH_LOD_DEPTH, lodDepthView, nearest)
                .sampled(DEPTH_LIGHT, lightImage, nearest);
        if (this.depthUniformBuffer != null) depthBindings.uniform(LOOSE_UNIFORMS, this.depthUniformBuffer);
        this.depth.dispatchWithPush(cmd, depthBindings, this.depthUniformBuffer == null ? this.depthUniforms.buffer() : null, gx, gy, 1);
        toRead(cmd, this.depthImage);
        toWrite(cmd, this.rawImage);
        VkBindings occlusionBindings = new VkBindings().storageImage(DST, this.rawImage.view()).sampled(OCCLUSION_DEPTH, this.depthImage, nearest)
                .sampled(OCCLUSION_SUN, sunImage, nearest).sampled(OCCLUSION_LIGHT, lightImage, nearest);
        if (this.occlusionUniformBuffer != null) occlusionBindings.uniform(LOOSE_UNIFORMS, this.occlusionUniformBuffer);
        this.occlusion.dispatchWithPush(cmd, occlusionBindings, this.occlusionUniformBuffer == null ? this.occlusionUniforms.buffer() : null, gx, gy, 1);
        toRead(cmd, this.rawImage);
        toWrite(cmd, this.outImage);
        this.blur.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.outImage.view()).sampled(BLUR_RAW, this.rawImage, nearest)
                .sampled(BLUR_DEPTH, this.depthImage, nearest).sampled(BLUR_SUN, sunImage, nearest), null, gx, gy, 1);
        toRead(cmd, this.outImage);
    }

    /** The image the composite binds (binding 13): r visibility, g view distance. */
    public VkImage2D output() {
        return this.outImage;
    }

    private static void toWrite(VkCommandBuffer cmd, VkImage2D image) {
        image.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void toRead(VkCommandBuffer cmd, VkImage2D image) {
        image.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                COMPUTE_AND_FRAGMENT, VK_ACCESS_SHADER_READ_BIT);
    }

    /** Call when a frame starts recording: transitions of a dropped frame are undone. */
    public void tick() {
        for (VkImage2D i : new VkImage2D[]{this.depthImage, this.rawImage, this.outImage}) if (i != null) i.tick();
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        this.depth.free();
        this.occlusion.free();
        this.blur.free();
        if (this.depthUniformBuffer != null) this.depthUniformBuffer.free();
        if (this.occlusionUniformBuffer != null) this.occlusionUniformBuffer.free();
        for (VkImage2D i : new VkImage2D[]{this.depthImage, this.rawImage, this.outImage}) if (i != null) i.free();
        this.depthImage = this.rawImage = this.outImage = null;
    }
}
