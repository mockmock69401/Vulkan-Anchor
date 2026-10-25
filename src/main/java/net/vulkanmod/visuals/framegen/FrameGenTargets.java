package net.vulkanmod.visuals.framegen;

import net.vulkanmod.config.RenderScale;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The GPU resources of frame generation (FG design 3.2, 5.1): per capture slot the world image (RGBA16F, the scaled world size, the
 * depth in alpha) and the UI layer framebuffer (swapchain size, own depth); for the pacer the splat (R32_UINT), resolved and filled
 * images; the 640-byte parameter buffers; the nearest and linear samplers; and two descriptor pools of its own, because the pacer must
 * never touch the render thread's VkDescriptorRing.
 *
 * Parameter buffers, by who reads them where:
 * - pacer entries (0 to PACER_PARAMS - 1): written by the pacer thread, read by its passes on the GPU;
 * - slot entries ({@link #captureParams}): the render thread's complete block of a capture slot, a CPU-side source only: the pacer copies
 *   it when it takes the frame (FramePacer.take) and no GPU work reads it;
 * - capture entries ({@link #captureFrameParams}), one per render frame in flight: the block the capture dispatch reads on the GPU. Frame
 *   N writes the entry of Renderer's current frame index only after beginFrame waited for that index's previous frame (N - framesNum),
 *   so a queued capture never sees a later frame's block, whatever the frame queue size. The capture's sets come from the render
 *   thread's VkDescriptorRing, so these entries add nothing to the pools below.
 *
 * Lifetimes (final review I3): the UI layer framebuffers depend on the swapchain size only and are kept when only the world size
 * changes (a screen toggled under render scale, a pacer restart), so DefaultMainPass keeps their render passes and the GUI pipelines
 * compiled for them; everything else is the world-size Generation, replaced whole.
 *
 * Threading: everything here that allocates or frees runs on the render thread with the pacer stopped (ensure, refreshTextures, free).
 * The accessors are read by both threads once ensure has returned. Layout ownership: a slot's world and UI images are transitioned only
 * by the render thread (VkWarp.capture, uiToShaderRead); splat, resolved and filled are transitioned only by the pacer inside
 * VkWarp.generate.
 */
public final class FrameGenTargets {
    /** Capture slots: the pacer warps one while the render thread captures into the other. */
    public static final int SLOTS = 2;
    /** Parameter buffers the pacer writes: one per generated frame in flight, indices 0 to PACER_PARAMS - 1. */
    public static final int PACER_PARAMS = 3;
    /** Render frames in flight at most: Reforged's frame queue option goes up to 5. */
    public static final int MAX_FRAMES_IN_FLIGHT = 5;
    /** The pacer entries, one per capture slot ({@link #captureParams}), then one per render frame in flight ({@link #captureFrameParams}). */
    public static final int PARAMS_COUNT = PACER_PARAMS + SLOTS + MAX_FRAMES_IN_FLIGHT;

    static final int WORLD_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int SPLAT_FORMAT = VK_FORMAT_R32_UINT;

    /** The parameter buffer index of capture slot slot (written by the render thread, copied by the pacer on the CPU only). */
    public static int captureParams(int slot) {
        return PACER_PARAMS + slot;
    }

    /** The parameter buffer index the capture dispatch of render frame index frame (Renderer.getCurrentFrame) reads on the GPU. */
    public static int captureFrameParams(int frame) {
        if (frame < 0 || frame >= MAX_FRAMES_IN_FLIGHT) {
            throw new IllegalArgumentException("render frame index " + frame + " (frame generation supports " + MAX_FRAMES_IN_FLIGHT + " frames in flight)");
        }
        return PACER_PARAMS + SLOTS + frame;
    }

    /**
     * The size of the world images: the render scale's size in the world with no screen open (where DefaultMainPass renders scaled),
     * else the swapchain size; {0, 0} while the swapchain has no area (minimised). Any thread.
     */
    public static int[] worldSize(int swapW, int swapH, int renderScale, boolean inWorldNoScreen) {
        if (swapW <= 0 || swapH <= 0) return new int[]{0, 0};
        if (RenderScale.isScaled(renderScale) && inWorldNoScreen) {
            return new int[]{RenderScale.scaleDimension(swapW, renderScale), RenderScale.scaleDimension(swapH, renderScale)};
        }
        return new int[]{swapW, swapH};
    }

    /** What {@link #ensure} replaces: nothing, the world-size images only (the UI layer kept), or everything. */
    public enum Rebuild { NONE, WORLD, ALL }

    /**
     * What ensure must replace, from the current sizes old ({swapW, swapH, worldW, worldH}, null when nothing is allocated) and the
     * wanted ones. The UI layer is swapchain-sized, so only a new swapchain size (or nothing yet) replaces it. Pure.
     */
    static Rebuild rebuild(int[] old, int swapW, int swapH, int worldW, int worldH) {
        if (old == null || old[0] != swapW || old[1] != swapH) return Rebuild.ALL;
        return old[2] == worldW && old[3] == worldH ? Rebuild.NONE : Rebuild.WORLD;
    }

    /**
     * Whether the compose sets must be rebuilt for these vignette and icons views: a view changed, or an image object was replaced (a
     * resource reload can free a view and hand its handle value to the new one, so equal values prove nothing then). Pure.
     */
    static boolean composeStale(long boundVignette, long boundIcons, long vignette, long icons, boolean imagesReplaced) {
        return imagesReplaced || vignette != boundVignette || icons != boundIcons;
    }

    /** The UI layer framebuffers (swapchain size, own depth), one per capture slot; kept while the swapchain size stays. */
    private static final class UiLayer {
        final int width, height;
        final Framebuffer[] framebuffers = new Framebuffer[SLOTS];

        UiLayer(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /** Builds both framebuffers; on a failure frees what it built (nothing has used it yet) and rethrows. */
        static UiLayer create(int width, int height) {
            UiLayer l = new UiLayer(width, height);
            try {
                for (int s = 0; s < SLOTS; s++) l.framebuffers[s] = Framebuffer.builder(width, height, 1, true).setLinearFiltering(false).build();
            } catch (RuntimeException | Error e) {
                l.free();
                throw e;
            }
            return l;
        }

        void free() {
            for (Framebuffer f : this.framebuffers) if (f != null) f.cleanUp();
        }
    }

    /** What a world size change replaces as one unit. */
    private static final class Generation {
        final int swapW, swapH, worldW, worldH;
        final VkImage2D[] world = new VkImage2D[SLOTS];
        VkImage2D splat, resolved, filled;
        long computePool;

        Generation(int swapW, int swapH, int worldW, int worldH) {
            this.swapW = swapW;
            this.swapH = swapH;
            this.worldW = worldW;
            this.worldH = worldH;
        }

        int[] sizes() {
            return new int[]{this.swapW, this.swapH, this.worldW, this.worldH};
        }

        void free() {
            for (VkImage2D i : this.world) if (i != null) i.free();
            if (this.splat != null) this.splat.free();
            if (this.resolved != null) this.resolved.free();
            if (this.filled != null) this.filled.free();
            if (this.computePool != 0) vkDestroyDescriptorPool(VkContext.device(), this.computePool, null);
        }
    }

    private final DeferredFree deferred;
    private final VkGpuBuffer[] params = new VkGpuBuffer[PARAMS_COUNT];
    private final long nearest, linear;
    private volatile Generation current;
    private volatile UiLayer uiLayer;
    private long composePool;
    private long vignetteView, iconsView;
    private VkWarp warp;

    /** Render thread. deferred is ticked by the caller each frame (like the other visuals' DeferredFree). */
    public FrameGenTargets(DeferredFree deferred) {
        this.deferred = deferred;
        for (int i = 0; i < PARAMS_COUNT; i++) {
            this.params[i] = VkGpuBuffer.host(FrameGenParams.SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            this.params[i].mapped().order(ByteOrder.nativeOrder());
        }
        this.nearest = VkSamplers.nearestClamp();
        this.linear = VkSamplers.linearClamp();
    }

    /** VkWarp's constructor: the programs whose descriptor sets this allocates. */
    void attach(VkWarp warp) {
        this.warp = warp;
    }

    /**
     * Makes the targets match the swapchain and the world size and returns what it replaced (the old resources are retired through
     * DeferredFree; the descriptor sets are rebuilt). A world size change alone keeps the UI layer (see the class comment). A zero
     * swapchain frees everything and allocates nothing (ALL when something was there). On a failure (an allocation, a descriptor pool)
     * nothing half-built is kept: what this call made is freed or retired, a failure after the swap leaves the targets without a
     * generation (not ready), and the exception is rethrown for FrameGen to turn frame generation off. Render thread, with the pacer
     * stopped (it holds the old images and sets).
     */
    public Rebuild ensure(int swapW, int swapH, int renderScale, boolean inWorldNoScreen) {
        if (this.warp == null) throw new IllegalStateException("frame generation targets have no VkWarp attached (VkWarp's constructor attaches it)");
        int[] world = worldSize(swapW, swapH, renderScale, inWorldNoScreen);
        Generation old = this.current;
        UiLayer oldUi = this.uiLayer;
        if (world[0] == 0) {
            if (old == null && oldUi == null) return Rebuild.NONE;
            this.current = null;
            this.uiLayer = null;
            if (old != null) this.retire(old);
            if (oldUi != null) this.retire(oldUi);
            this.rebuildCompose();
            return Rebuild.ALL;
        }
        Rebuild rebuild = rebuild(old == null ? null : old.sizes(), swapW, swapH, world[0], world[1]);
        if (oldUi == null || oldUi.width != swapW || oldUi.height != swapH) rebuild = Rebuild.ALL;
        if (rebuild == Rebuild.NONE) return Rebuild.NONE;

        //built first: a failure here leaves the current targets untouched and frees the partial ones (no GPU work has used them)
        UiLayer ui = null;
        Generation g = new Generation(swapW, swapH, world[0], world[1]);
        try {
            if (rebuild == Rebuild.ALL) ui = UiLayer.create(swapW, swapH);
            int storageSampled = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            for (int s = 0; s < SLOTS; s++) g.world[s] = VkImage2D.create(WORLD_FORMAT, world[0], world[1], 1, storageSampled);
            g.splat = VkImage2D.create(SPLAT_FORMAT, world[0], world[1], 1, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            g.resolved = VkImage2D.create(WORLD_FORMAT, world[0], world[1], 1, VK_IMAGE_USAGE_STORAGE_BIT);
            g.filled = VkImage2D.create(WORLD_FORMAT, world[0], world[1], 1, storageSampled);
            //splat and resolve groups per slot x pacer params, fill per pacer params: 15 groups of 4 sets, 27 storage images, 18 samplers,
            // 15 uniform blocks; the pool has margin
            g.computePool = createPool(80, 48, 32, 24);
        } catch (RuntimeException | Error e) {
            g.free();
            if (ui != null) ui.free();
            throw e;
        }

        this.current = g;
        if (old != null) this.retire(old);
        if (ui != null) {
            this.uiLayer = ui;
            if (oldUi != null) this.retire(oldUi);
        }
        try {
            this.warp.rebuildSets(this);
            this.rebuildCompose();
        } catch (RuntimeException | Error e) {
            //the sets may be partial: drop this generation and the UI layer so nothing reads them
            this.current = null;
            this.retire(g);
            UiLayer l = this.uiLayer;
            this.uiLayer = null;
            if (l != null) this.retire(l);
            this.warp.dropCompose();
            throw e;
        }
        return rebuild;
    }

    /**
     * Binds the vignette and crosshair-icons textures the compose pass samples (views the caller owns, in SHADER_READ_ONLY_OPTIMAL when
     * composing) and rebuilds the compose descriptor sets when they are stale ({@link #composeStale}: a view changed, or imagesReplaced).
     * Render thread, with the pacer stopped.
     */
    public void refreshTextures(long vignetteView, long iconsView, boolean imagesReplaced) {
        if (!composeStale(this.vignetteView, this.iconsView, vignetteView, iconsView, imagesReplaced)) return;
        this.vignetteView = vignetteView;
        this.iconsView = iconsView;
        this.rebuildCompose();
    }

    /** The compose sets live in a pool of their own so a texture change replaces them whole (a set in use is never rewritten). */
    private void rebuildCompose() {
        long old = this.composePool;
        this.composePool = 0;
        if (old != 0) this.deferred.retire(() -> vkDestroyDescriptorPool(VkContext.device(), old, null), VkFrames.serial());
        if (this.current == null || this.vignetteView == 0 || this.iconsView == 0) {
            this.warp.dropCompose();
            return;
        }
        //real and generated x 2 slots x 3 params = 12 groups of 4 sets, 4 samplers and a uniform block each
        this.composePool = createPool(64, 0, 64, 16);
        this.warp.rebuildCompose(this, this.composePool, this.vignetteView, this.iconsView);
    }

    private void retire(Generation g) {
        this.deferred.retire(g::free, VkFrames.serial());
    }

    private void retire(UiLayer l) {
        this.deferred.retire(l::free, VkFrames.serial());
    }

    /**
     * Records the UI layer's colour image of slot into SHADER_READ_ONLY_OPTIMAL, after the UI render pass has ended (the compose pass
     * reads it). Render thread; the layout of the UI images is theirs alone.
     */
    public void uiToShaderRead(VkCommandBuffer cmd, int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.ui(slot).getColorAttachment().readOnlyLayout(stack, cmd);
        }
    }

    /** True once ensure has made targets (false while the swapchain has no area). Any thread. */
    public boolean ready() {
        return this.current != null;
    }

    private Generation current() {
        Generation g = this.current;
        if (g == null) throw new IllegalStateException("frame generation targets are not allocated");
        return g;
    }

    /** The slot's world image: written by capture (render thread), read by the pacer. */
    public VkImage2D world(int slot) {
        return this.current().world[slot];
    }

    /** The slot's UI layer framebuffer (swapchain size, own depth); the render thread draws the UI into it. */
    public Framebuffer ui(int slot) {
        UiLayer l = this.uiLayer;
        if (l == null) throw new IllegalStateException("frame generation UI layer is not allocated");
        return l.framebuffers[slot];
    }

    /** The pacer's splat depth image (R32_UINT, world size). Pacer thread. */
    public VkImage2D splat() {
        return this.current().splat;
    }

    /** The pacer's resolved image (RGBA16F, world size). Pacer thread. */
    public VkImage2D resolved() {
        return this.current().resolved;
    }

    /** The pacer's filled image, the generated frame the compose pass reads (RGBA16F, world size). Pacer thread. */
    public VkImage2D filled() {
        return this.current().filled;
    }

    public int worldWidth() {
        return this.current().worldW;
    }

    public int worldHeight() {
        return this.current().worldH;
    }

    public int swapWidth() {
        return this.current().swapW;
    }

    public int swapHeight() {
        return this.current().swapH;
    }

    /** The parameter buffer index: 0 to PACER_PARAMS - 1 for the pacer, {@link #captureParams} for a slot. */
    public VkGpuBuffer paramsBuffer(int index) {
        return this.params[index];
    }

    /** The persistently mapped, host-coherent 640-byte block of parameter buffer index (native order, position 0). */
    public ByteBuffer paramsMapped(int index) {
        return this.params[index].mapped();
    }

    /** The nearest (clamped) sampler. */
    public long nearest() {
        return this.nearest;
    }

    /** The linear (clamped) sampler. */
    public long linear() {
        return this.linear;
    }

    /** VkWarp: the pool its splat, resolve and fill sets come from. */
    long computePool() {
        return this.current().computePool;
    }

    /** Retires everything through DeferredFree (render thread, pacer stopped). The object is unusable afterwards. */
    public void free() {
        Generation g = this.current;
        this.current = null;
        if (g != null) this.retire(g);
        UiLayer ui = this.uiLayer;
        this.uiLayer = null;
        if (ui != null) this.retire(ui);
        long pool = this.composePool;
        this.composePool = 0;
        VkGpuBuffer[] buffers = this.params.clone();
        long n = this.nearest, l = this.linear;
        this.deferred.retire(() -> {
            if (pool != 0) vkDestroyDescriptorPool(VkContext.device(), pool, null);
            for (VkGpuBuffer b : buffers) b.free();
            VkSamplers.free(n);
            VkSamplers.free(l);
        }, VkFrames.serial());
        if (this.warp != null) this.warp.dropCompose();
    }

    private static long createPool(int maxSets, int storageImages, int samplers, int uniforms) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int kinds = (storageImages > 0 ? 1 : 0) + (samplers > 0 ? 1 : 0) + (uniforms > 0 ? 1 : 0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(kinds, stack);
            int i = 0;
            if (storageImages > 0) sizes.get(i++).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(storageImages);
            if (samplers > 0) sizes.get(i++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(samplers);
            if (uniforms > 0) sizes.get(i).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(uniforms);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(VkContext.device(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(maxSets).pPoolSizes(sizes), null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateDescriptorPool failed: " + result);
            return p.get(0);
        }
    }
}
