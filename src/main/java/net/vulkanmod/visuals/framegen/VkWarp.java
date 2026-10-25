package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkImage2D;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import static net.vulkanmod.visuals.framegen.FrameGenBindings.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Records the frame generation passes (FG design 5.1): capture (the render thread, into a slot's world image), then on the pacer thread
 * splat, resolve and fill into the filled image, and the compose pass into the swapchain image. The pacer's passes use descriptor sets
 * allocated up front from FrameGenTargets' pools (one set group per slot x parameter buffer), never the render thread's
 * VkDescriptorRing; the capture pass, on the render thread, uses the ring like every other visuals pass.
 *
 * Layouts: a slot's world image is left in SHADER_READ_ONLY_OPTIMAL by capture and generate/compose never change it; splat, resolved
 * and filled are transitioned only here, in generate, from UNDEFINED (each is rewritten whole every generated frame).
 */
public final class VkWarp {
    private static final VkGraphicsProgram.State COMPOSE_STATE = new VkGraphicsProgram.State(false, false, VK_COMPARE_OP_ALWAYS,
            VkGraphicsProgram.RGBA, false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);
    /** Matches the shaders' local_size (8, 8). */
    private static final int GROUP = 8;
    private static final int PACER = FrameGenTargets.PACER_PARAMS, SLOTS = FrameGenTargets.SLOTS;

    private final FrameGenTargets targets;
    private final VkComputeProgram capture, splat, resolve, fill;
    private final VkGraphicsProgram compose;

    /** The pacer's pre-allocated sets, replaced by rebuildSets/rebuildCompose on the render thread while the pacer is stopped. */
    private volatile long[][][] splatSets, resolveSets;//[slot][params]
    private volatile long[][] fillSets;//[params]
    private volatile long[][][] composeRealSets;//[slot][params]
    private volatile long[][][] composeGeneratedSets;//[slot][params]

    /** Render thread. Builds the five programs (pipelines for the compute ones; compose's per render pass, see {@link #prepareCompose}). */
    public VkWarp(FrameGenTargets targets) {
        this.targets = targets;
        this.capture = VkComputeProgram.of("fgCapture");
        this.splat = VkComputeProgram.of("fgSplat");
        this.resolve = VkComputeProgram.of("fgResolve");
        this.fill = VkComputeProgram.of("fgFill");
        this.compose = VkGraphicsProgram.of("fgCompose", COMPOSE_STATE);
        targets.attach(this);
    }

    /** Builds compose's pipeline for renderPass (the swapchain pass the pacer begins) now. Render thread, before the pacer draws. */
    public void prepareCompose(long renderPass) {
        this.compose.prepare(renderPass);
    }

    private static int groups(int pixels) {
        return (pixels + GROUP - 1) / GROUP;
    }

    /**
     * Render thread, called by FrameGenTargets.ensure: allocates the splat, resolve and fill sets for every slot and pacer parameter
     * buffer from the targets' compute pool.
     */
    void rebuildSets(FrameGenTargets t) {
        long pool = t.computePool();
        long[][][] splatS = new long[SLOTS][PACER][], resolveS = new long[SLOTS][PACER][];
        long[][] fillS = new long[PACER][];
        VkImage2D splatImage = t.splat(), resolvedImage = t.resolved(), filledImage = t.filled();
        for (int p = 0; p < PACER; p++) {
            for (int s = 0; s < SLOTS; s++) {
                VkImage2D world = t.world(s);
                splatS[s][p] = this.splat.allocateSets(pool, new VkBindings().storageImage(SPLAT_OUT, splatImage.view())
                        .sampled(SPLAT_WORLD, world, t.nearest()).uniform(PARAMS, t.paramsBuffer(p)));
                resolveS[s][p] = this.resolve.allocateSets(pool, new VkBindings().storageImage(RESOLVE_OUT, resolvedImage.view())
                        .storageImage(RESOLVE_SPLAT, splatImage.view()).sampled(RESOLVE_WORLD_LINEAR, world, t.linear())
                        .sampled(RESOLVE_WORLD_NEAREST, world, t.nearest()).uniform(PARAMS, t.paramsBuffer(p)));
            }
            fillS[p] = this.fill.allocateSets(pool, new VkBindings().storageImage(FILL_OUT, filledImage.view())
                    .storageImage(FILL_RESOLVED, resolvedImage.view()).storageImage(FILL_SPLAT, splatImage.view())
                    .uniform(PARAMS, t.paramsBuffer(p)));
        }
        this.splatSets = splatS;
        this.resolveSets = resolveS;
        this.fillSets = fillS;
    }

    /**
     * Render thread, called by FrameGenTargets: allocates the compose sets (a real frame reads a slot's world image, a generated one the
     * filled image; the UI layer of the slot, the vignette and the icons either way) from pool.
     */
    void rebuildCompose(FrameGenTargets t, long pool, long vignetteView, long iconsView) {
        long[][][] real = new long[SLOTS][PACER][], generated = new long[SLOTS][PACER][];
        for (int s = 0; s < SLOTS; s++) {
            for (int p = 0; p < PACER; p++) {
                real[s][p] = this.compose.allocateSets(pool, composeBindings(t, t.world(s), s, p, vignetteView, iconsView));
                generated[s][p] = this.compose.allocateSets(pool, composeBindings(t, t.filled(), s, p, vignetteView, iconsView));
            }
        }
        this.composeRealSets = real;
        this.composeGeneratedSets = generated;
    }

    /** Whether compose can record (its sets are built: targets and the vignette and icons textures are there). Any thread. */
    public boolean composeReady() {
        return this.composeRealSets != null;
    }

    /** Render thread: the compose sets are gone (no targets or no vignette/icons textures yet). */
    void dropCompose() {
        this.composeRealSets = null;
        this.composeGeneratedSets = null;
    }

    private static VkBindings composeBindings(FrameGenTargets t, VkImage2D world, int slot, int params, long vignetteView, long iconsView) {
        return new VkBindings().sampled(COMPOSE_WORLD, world, t.linear())
                .sampledView(COMPOSE_UI, t.ui(slot).getColorAttachment().getImageView(), t.nearest())
                .sampledView(COMPOSE_VIGNETTE, vignetteView, t.linear()).sampledView(COMPOSE_ICONS, iconsView, t.linear())
                .uniform(PARAMS, t.paramsBuffer(params));
    }

    /**
     * Render thread, inside the frame's command buffer after the main passes: writes the slot's world image (colour, and the view depth in
     * alpha from the main depth and, where the LOD depth is nearer, the LOD depth) and leaves it in SHADER_READ_ONLY_OPTIMAL for the
     * pacer's compute and fragment stages. paramsIndex is this render frame's capture entry (FrameGenTargets.captureFrameParams), already
     * written with the capture-time fields; the slot's own block is the pacer's CPU-side copy and is not read here. The views
     * must be sampleable (SHADER_READ_ONLY_OPTIMAL); a missing LOD depth (0) falls back to the main depth and, when depthValid is false
     * (menus), the depth views are ignored by the shader, so the colour view stands in for them. The previous contents are discarded, so
     * the caller guarantees the pacer is done with this slot's last frame (Task 8's slot hand-over).
     */
    public void capture(VkCommandBuffer cmd, int slot, int paramsIndex, long colourView, long mainDepthView, long lodDepthView, boolean depthValid) {
        VkImage2D world = this.targets.world(slot);
        long mainDepth = depthValid && mainDepthView != 0 ? mainDepthView : colourView;
        long lodDepth = depthValid && lodDepthView != 0 ? lodDepthView : mainDepth;
        world.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        VkBindings bindings = new VkBindings().storageImage(CAPTURE_OUT, world.view())
                .sampledView(CAPTURE_COLOUR, colourView, this.targets.nearest())
                .sampledView(CAPTURE_MAIN_DEPTH, mainDepth, this.targets.nearest())
                .sampledView(CAPTURE_LOD_DEPTH, lodDepth, this.targets.nearest())
                .uniform(PARAMS, this.targets.paramsBuffer(paramsIndex));
        this.capture.dispatch(cmd, bindings, null, groups(world.width), groups(world.height), 1);
        world.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /**
     * Pacer thread, in the pacer's command buffer: warps the slot's real frame into the filled image with the parameter buffer
     * paramsIndex (0 to PACER_PARAMS - 1, already written): clears the splat depth image, splats, resolves and fills, with a barrier
     * between each. Leaves the filled image in SHADER_READ_ONLY_OPTIMAL for compose. The slot's world image is read in the layout capture
     * left it in and not changed; it must be available to this command buffer (Task 8's semaphore).
     */
    public void generate(VkCommandBuffer cmd, int slot, int paramsIndex) {
        checkParams(paramsIndex);
        FrameGenTargets t = this.targets;
        int w = t.worldWidth(), h = t.worldHeight();
        VkImage2D splatImage = t.splat(), resolvedImage = t.resolved(), filledImage = t.filled();

        //The three images are rewritten whole: discard them (UNDEFINED -> GENERAL). The source scope covers the previous generate's reads
        // and writes and the previous compose's read of filled (same queue, earlier submissions).
        int anyUse = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
        int anyAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        imageBarrier(cmd, new long[]{splatImage.handle(), resolvedImage.handle(), filledImage.handle()}, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL, anyUse, anyAccess, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

        //EMPTY (all ones) in every cell of the splat depth image
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue empty = VkClearColorValue.calloc(stack);
            empty.uint32(0, 0xFFFFFFFF).uint32(1, 0xFFFFFFFF).uint32(2, 0xFFFFFFFF).uint32(3, 0xFFFFFFFF);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdClearColorImage(cmd, splatImage.handle(), VK_IMAGE_LAYOUT_GENERAL, empty, range);
        }
        memoryBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

        this.splat.dispatchSets(cmd, this.splatSets[slot][paramsIndex], null, groups(w), groups(h), 1);
        memoryBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

        this.resolve.dispatchSets(cmd, this.resolveSets[slot][paramsIndex], null, groups(w), groups(h), 1);
        memoryBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

        this.fill.dispatchSets(cmd, this.fillSets[paramsIndex], null, groups(w), groups(h), 1);
        imageBarrier(cmd, new long[]{filledImage.handle()}, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /**
     * Pacer thread, inside renderPass already begun on the swapchain image (this neither begins nor ends it): composes the frame, a real
     * one (generated false: the slot's world image) or a generated one (the filled image), with the slot's UI layer, the vignette, the
     * crosshair and the parameter buffer paramsIndex (0 to PACER_PARAMS - 1, already written). Sets the viewport and scissor to
     * width x height and draws the full-screen quad. Changes no image layout. The slot's UI colour image must be in
     * SHADER_READ_ONLY_OPTIMAL (FrameGenTargets.uiToShaderRead) and the vignette and icons views set (refreshTextures).
     */
    public void compose(VkCommandBuffer cmd, long renderPass, int width, int height, int slot, boolean generated, int paramsIndex) {
        checkParams(paramsIndex);
        long[][][] sets = generated ? this.composeGeneratedSets : this.composeRealSets;
        if (sets == null) throw new IllegalStateException("compose sets are not built: refreshTextures with a vignette and an icons view first");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1));
            vkCmdSetScissor(cmd, 0, VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
        }
        this.compose.bindSets(cmd, renderPass, sets[slot][paramsIndex]);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    private static void checkParams(int paramsIndex) {
        if (paramsIndex < 0 || paramsIndex >= PACER) throw new IllegalArgumentException("pacer parameter index " + paramsIndex);
    }

    private static void memoryBarrier(VkCommandBuffer cmd, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStages, dstStages, 0, b, null, null);
        }
    }

    /** A layout transition of whole single-mip colour images with raw barriers: the pacer's images are not tracked by VkImage2D's render-thread tracker. */
    private static void imageBarrier(VkCommandBuffer cmd, long[] images, int oldLayout, int newLayout, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(images.length, stack);
            for (int i = 0; i < images.length; i++) {
                b.get(i).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(images[i])
                        .subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            }
            vkCmdPipelineBarrier(cmd, srcStages, dstStages, 0, null, null, b);
        }
    }

    /** Only once no submitted frame can still use the programs (pacer stopped, device idle or the frames done). */
    public void free() {
        this.capture.free();
        this.splat.free();
        this.resolve.free();
        this.fill.free();
        this.compose.free();
    }
}
