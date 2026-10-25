package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * P7i: SMAA (iryoku/smaa through turol/smaaDemo's compute passes, MIT) after the tone mapping: the final colour into an image
 * (post_ldr.comp), luma edges, blending weights, neighbourhood blending, then a blit into Reforged's resumed main pass in place
 * of post_final's draw. Reforged's main colour cannot be a storage image, hence the images of our own.
 */
final class VkSmaa {
    /**
     * Off, then SMAA's LOW, MEDIUM, HIGH and ULTRA presets: edge threshold, search steps, diagonal steps (0: none), corner rounding.
     * Corner detection is always compiled in: LOW and MEDIUM, which have none upstream, round 100 (the weights are left as they are).
     */
    static final float[][] PRESETS = {null, {0.15f, 4, 0, 100}, {0.10f, 8, 0, 100}, {0.10f, 16, 8, 25}, {0.05f, 32, 16, 25}};
    static final int AREA_WIDTH = 160, AREA_HEIGHT = 560, SEARCH_WIDTH = 64, SEARCH_HEIGHT = 16;
    static final int METRICS = 0, PARAMS = 1;
    // post_ldr.comp: post_final's samplers at 0..2, the output image at 3; the SMAA passes: output image at 0, inputs from 1
    private static final int LDR_OUT = 3, OUT = 0, IN0 = 1, IN1 = 2, IN2 = 3;
    private static final int COMPUTE_AND_FRAGMENT = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private final VkComputeProgram ldr, edges, weights, blend;
    private final VkGraphicsProgram blit;
    private final LooseUniformWriter ldrUniforms, edgesUniforms, weightsUniforms, blendUniforms, blitUniforms;
    private final VkImage2D area, search;
    private final byte[] areaBytes, searchBytes;
    private long lookupSerial = -1;
    private VkImage2D ldrImage, edgesImage, weightsImage, outImage;
    private int width, height;
    private net.vulkanmod.visuals.vk.DeferredFree deferred;

    VkSmaa() {
        VkComputeProgram ldr = null, edges = null, weights = null, blend = null;
        VkGraphicsProgram blit = null;
        VkImage2D area = null, search = null;
        try {
            this.areaBytes = rgba(read("assets/vulkanmod/visuals/textures/smaa/area.bin", AREA_WIDTH * AREA_HEIGHT * 2), 2);
            this.searchBytes = rgba(read("assets/vulkanmod/visuals/textures/smaa/search.bin", SEARCH_WIDTH * SEARCH_HEIGHT), 1);
            ldr = VkComputeProgram.of("postLdr");
            edges = VkComputeProgram.of("smaaEdges");
            weights = VkComputeProgram.of("smaaWeights");
            blend = VkComputeProgram.of("smaaBlend");
            blit = VkGraphicsProgram.of("postBlit", VkPostProcess.STATE);
            int lookup = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            area = VkImage2D.create(VK_FORMAT_R8G8B8A8_UNORM, AREA_WIDTH, AREA_HEIGHT, 1, lookup);
            search = VkImage2D.create(VK_FORMAT_R8G8B8A8_UNORM, SEARCH_WIDTH, SEARCH_HEIGHT, 1, lookup);
        } catch (IOException | RuntimeException | LinkageError e) {
            for (VkComputeProgram p : new VkComputeProgram[]{ldr, edges, weights, blend}) if (p != null) p.free();
            if (blit != null) blit.free();
            if (area != null) area.free();
            if (search != null) search.free();
            throw e instanceof IOException io ? new IllegalStateException(io) : e instanceof RuntimeException r ? r : new IllegalStateException(e);
        }
        this.ldr = ldr;
        this.edges = edges;
        this.weights = weights;
        this.blend = blend;
        this.blit = blit;
        this.area = area;
        this.search = search;
        this.ldrUniforms = pushOnly(ldr);
        this.edgesUniforms = pushOnly(edges);
        this.weightsUniforms = pushOnly(weights);
        this.blendUniforms = pushOnly(blend);
        this.blitUniforms = blit.looseUniforms();
        if (this.blitUniforms.inUniformBuffer()) throw new IllegalStateException("postBlit: loose uniforms too large for push constants");
    }

    private static LooseUniformWriter pushOnly(VkComputeProgram program) {
        LooseUniformWriter w = new LooseUniformWriter(program.meta());
        if (w.inUniformBuffer()) throw new IllegalStateException("SMAA: loose uniforms too large for push constants");
        return w;
    }

    private static byte[] read(String path, int expected) throws IOException {
        try (InputStream in = VkSmaa.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException(path + " missing");
            byte[] b = in.readAllBytes();
            if (b.length != expected) throw new IOException(path + ": " + b.length + " bytes, expected " + expected);
            return b;
        }
    }

    /** The lookup textures as RGBA8 (the upload stream copies four bytes a pixel); SMAA reads .rg of the area and .r of the search. */
    static byte[] rgba(byte[] packed, int channels) {
        int pixels = packed.length / channels;
        byte[] out = new byte[pixels * 4];
        for (int i = 0; i < pixels; i++) {
            for (int c = 0; c < channels; c++) out[i * 4 + c] = packed[i * channels + c];
        }
        return out;
    }

    /** Before the frame's upload commit: the lookup textures, once (again if that frame was dropped). */
    void prepare(VkUploadStream upload) {
        if (this.lookupSerial >= 0 && !VkFrames.isDropped(this.lookupSerial)) return;
        upload(upload, this.area, this.areaBytes, AREA_WIDTH, AREA_HEIGHT);
        upload(upload, this.search, this.searchBytes, SEARCH_WIDTH, SEARCH_HEIGHT);
        this.lookupSerial = VkFrames.serial();
    }

    private static void upload(VkUploadStream upload, VkImage2D image, byte[] bytes, int width, int height) {
        ByteBuffer pixels = MemoryUtil.memAlloc(bytes.length);
        try {
            pixels.put(bytes).flip();
            upload.uploadImage(image, 0, 0, 0, width, height, MemoryUtil.memAddress(pixels));
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** The lookup textures are uploaded by this frame's commit or were by a frame that ran. */
    boolean usable() {
        return this.lookupSerial >= 0 && !VkFrames.isDropped(this.lookupSerial);
    }

    /** The screen size. The images follow on the first frame that anti-aliases (none are kept while it is off since a resize). */
    void ensureSize(int width, int height, net.vulkanmod.visuals.vk.DeferredFree deferred) {
        this.deferred = deferred;
        if (this.width == width && this.height == height) return;
        this.width = width;
        this.height = height;
        if (this.ldrImage == null) return;
        VkImage2D[] old = {this.ldrImage, this.edgesImage, this.weightsImage, this.outImage};
        this.ldrImage = this.edgesImage = this.weightsImage = this.outImage = null;
        deferred.retire(() -> {
            for (VkImage2D i : old) i.free();
        }, VkFrames.serial());
    }

    private void allocate() {
        if (this.ldrImage != null) return;
        if (this.width <= 0 || this.height <= 0) throw new IllegalStateException("SMAA: no screen size");
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        VkImage2D[] made = new VkImage2D[4];
        try {
            for (int i = 0; i < made.length; i++) made[i] = VkImage2D.create(VK_FORMAT_R8G8B8A8_UNORM, this.width, this.height, 1, usage);
        } catch (RuntimeException e) {
            for (VkImage2D i : made) if (i != null) i.free();//never used: freed at once
            throw e;
        }
        this.ldrImage = made[0];
        this.edgesImage = made[1];
        this.weightsImage = made[2];
        this.outImage = made[3];
    }

    /**
     * Outside a render pass, after the post-process compute (scene, bloom and shafts readable): the final colour into an image
     * (readable), which TAA (TAA pass, 2026-10-15) and the SMAA passes take.
     */
    VkImage2D finalColour(VkCommandBuffer cmd, PostUniforms.Values values, VkImage2D scene, VkImage2D bloom, VkImage2D shafts, long nearest, long linear) {
        this.allocate();
        VkGpuTimer.mark(cmd, "smaa.final");
        values.write(this.ldrUniforms);
        toWrite(cmd, this.ldrImage);
        this.ldr.dispatchWithPush(cmd, new VkBindings().sampled(0, scene, nearest).sampled(1, bloom, linear).sampled(2, shafts, linear)
                .storageImage(LDR_OUT, this.ldrImage.view()), this.ldrUniforms.buffer(), groups(this.width), groups(this.height), 1);
        toRead(cmd, this.ldrImage);
        return this.ldrImage;
    }

    /** After finalColour(): the three SMAA passes on input (readable, the final colour or TAA's), into an image it returns (readable). */
    VkImage2D antiAlias(VkCommandBuffer cmd, VkImage2D input, long nearest, long linear, int preset) {
        float[] p = PRESETS[preset];
        for (LooseUniformWriter w : new LooseUniformWriter[]{this.edgesUniforms, this.weightsUniforms, this.blendUniforms}) {
            w.putVec4(METRICS, 1f / this.width, 1f / this.height, this.width, this.height).putVec4(PARAMS, p[0], p[1], p[2], p[3]);
        }
        VkGpuTimer.mark(cmd, "smaa");
        toWrite(cmd, this.edgesImage);
        this.edges.dispatchWithPush(cmd, new VkBindings().storageImage(OUT, this.edgesImage.view()).sampled(IN0, input, linear),
                this.edgesUniforms.buffer(), groups(this.width), groups(this.height), 1);
        toRead(cmd, this.edgesImage);
        toWrite(cmd, this.weightsImage);
        this.weights.dispatchWithPush(cmd, new VkBindings().storageImage(OUT, this.weightsImage.view()).sampled(IN0, this.edgesImage, linear)
                        .sampled(IN1, this.area, linear).sampled(IN2, this.search, nearest),
                this.weightsUniforms.buffer(), groups(this.width), groups(this.height), 1);
        toRead(cmd, this.weightsImage);
        toWrite(cmd, this.outImage);
        this.blend.dispatchWithPush(cmd, new VkBindings().storageImage(OUT, this.outImage.view()).sampled(IN0, input, linear)
                .sampled(IN1, this.weightsImage, linear), this.blendUniforms.buffer(), groups(this.width), groups(this.height), 1);
        toRead(cmd, this.outImage);
        return this.outImage;
    }

    /**
     * Inside the resumed main pass, in place of post_final's draw: image (the anti-aliased colour) sharpened by strength
     * (PostMath.sharpenStrength), one full-screen strip.
     */
    void draw(VkCommandBuffer cmd, long nearest, VkImage2D image, float strength) {
        this.blitUniforms.putVec4(0, strength, 0, 0, 0);
        this.blit.bind(cmd, VkContext.boundRenderPassHandle(), VkContext.boundRenderPassKey(), new VkBindings().sampled(0, image, nearest), this.blitUniforms);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    private static void toWrite(VkCommandBuffer cmd, VkImage2D image) {
        image.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void toRead(VkCommandBuffer cmd, VkImage2D image) {
        image.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                COMPUTE_AND_FRAGMENT, VK_ACCESS_SHADER_READ_BIT);
    }

    private static int groups(int pixels) {
        return (pixels + 7) / 8;
    }

    /** Call when a frame starts recording: transitions of a dropped frame are undone. */
    void tick() {
        for (VkImage2D i : new VkImage2D[]{this.ldrImage, this.edgesImage, this.weightsImage, this.outImage, this.area, this.search}) {
            if (i != null) i.tick();
        }
    }

    /** Only once no submitted frame can still use it. */
    void free() {
        for (VkComputeProgram p : new VkComputeProgram[]{this.ldr, this.edges, this.weights, this.blend}) p.free();
        this.blit.free();
        for (VkImage2D i : new VkImage2D[]{this.ldrImage, this.edgesImage, this.weightsImage, this.outImage, this.area, this.search}) {
            if (i != null) i.free();
        }
    }
}
