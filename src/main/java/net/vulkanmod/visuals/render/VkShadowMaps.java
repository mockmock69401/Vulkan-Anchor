package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.api.ShadowCascadeContext;
import net.vulkanmod.visuals.api.VisualsExtension;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkDownloadStream;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkViewport;

import java.nio.LongBuffer;
import java.util.List;
import java.util.function.LongPredicate;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The sun shadow cascades (close, near, far): each a pair of depth maps for the sun at the start of its step and at the next one,
 * which the lighting mixes by the sun's progress (ShadowSteps). One map per cascade is drawn a frame at most (the missing one,
 * else every few frames the older one again); all are read rebased to the camera, and a map whose camera moved too far or that
 * is from before a pause is drawn again. The casters are VulkanMod's vanilla terrain (every cascade, out to
 * ShadowMath.vanillaRadius) and whatever the registered extensions draw (VisualsExtension's shadow hooks: an LOD renderer
 * puts its own traversal and draws there). The maps are left readable by the lighting pass (compute).
 * Performance (2026-10-21): a cascade with slices (the far one, ShadowMath.FAR_SLICES) draws its refresh into a third, spare
 * image a slice of the casters a frame (the extensions pick their share by ShadowCascadeContext.slice) while both its maps stay
 * readable, and swaps it in for the refreshed map once the last slice is drawn; a missing map is still drawn whole at once.
 */
public final class VkShadowMaps {
    private static final int TEST_STAGES = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    /** The pipeline state of every sun shadow draw: depth only, no colour. */
    static final VkGraphicsProgram.State SHADOW_STATE = VkGraphicsProgram.State.depthOnly(VK_COMPARE_OP_LESS_OR_EQUAL, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
    /** Diagnostics (-Dvulkanmod.visuals.shadowTrace=true): per frame the shadows' time, sun step and each cascade's maps, to the log. */
    static final boolean TRACE = Boolean.getBoolean("vulkanmod.visuals.shadowTrace");

    private final long renderPass;
    /** The same pass loading the depth a refresh's earlier slices drew (compatible with renderPass: the framebuffers serve both). */
    private final long loadPass;
    private final Cascade close, near, far;//texel crawl pass (2026-10-16): the close cascade, read before the near one
    private final Cascade[] cascades;//the order the cascades are prepared and drawn in
    private final VkVanillaShadows vanilla;//null when it could not be built: the maps keep the extensions' casters only
    private long closeRedraws, nearRedraws, farRedraws;
    private boolean vanillaOn;
    private boolean open;
    private long lastPrepared = Long.MIN_VALUE;
    private int preparedFrameId;
    private long sunStep;//this frame's sun step and the progress through it (ShadowSteps)
    private float sunProgress;

    /** Built step by step: a failure (VMA out of memory on a cascade) frees what exists and rethrows, as the caller then runs without shadows. */
    public VkShadowMaps() {
        long renderPass = 0, loadPass = 0;
        Cascade close = null, near = null, far = null;
        try {
            int depthFormat = VkContext.depthFormat();
            renderPass = createRenderPass(depthFormat, true);
            loadPass = createRenderPass(depthFormat, false);
            near = new Cascade(ShadowMath.Which.NEAR, "shadows.near", renderPass, depthFormat, ShadowMath.NEAR_WIDTH, ShadowMath.NEAR_HEIGHT, 1);
            far = new Cascade(ShadowMath.Which.FAR, "shadows.far", renderPass, depthFormat, ShadowMath.FAR_WIDTH, ShadowMath.FAR_HEIGHT, ShadowMath.FAR_SLICES);
            close = new Cascade(ShadowMath.Which.CLOSE, "shadows.close", renderPass, depthFormat, ShadowMath.CLOSE_WIDTH, ShadowMath.CLOSE_HEIGHT, 1);
        } catch (RuntimeException | LinkageError e) {
            if (close != null) close.free();
            if (far != null) far.free();
            if (near != null) near.free();
            if (loadPass != 0) vkDestroyRenderPass(VkContext.device(), loadPass, null);
            if (renderPass != 0) vkDestroyRenderPass(VkContext.device(), renderPass, null);
            throw e;
        }
        this.renderPass = renderPass;
        this.loadPass = loadPass;
        this.close = close;
        this.near = near;
        this.far = far;
        this.cascades = new Cascade[]{near, far, close};
        //P7o design 3.7: on its own, so a failure leaves the cascades (and the extensions' casters) as they are
        VkVanillaShadows vanilla = null;
        try {
            vanilla = new VkVanillaShadows();
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals vanilla shadows not created, the shadow maps keep the extensions' casters only: " + e);
        }
        this.vanilla = vanilla;
    }

    /** A cascade's two maps (ShadowSteps): what each holds, for which sun step, drawn when. */
    private static final class Cascade {
        final ShadowMath.Which which;
        final String name, computeLabel, drawLabel, vanillaLabel;
        final VkImage2D[] depth;//two images, three with slices (the spare a refresh is drawn into)
        final int width, height;//the maps' size (wobble pass, 2026-10-16: the close one is wider across the sun's path)
        final long[] framebuffer;
        /** The image each map (ShadowSteps' slots 0 and 1) is in. */
        final int[] image = {0, 1};
        /** How many frames a refresh is spread over (1: drawn whole, into the refreshed map's own image). */
        final int slices;
        final ShadowMath.Cascade[] drawn = new ShadowMath.Cascade[2];
        final long[] drawnSerial = {-1, -1};
        final long[] step = new long[2];
        final boolean[] valid = new boolean[2];//this frame's, from prepare() and draw()
        ShadowMath.Cascade pending;
        long pendingStep;
        int drawSlot = -1;//the map this frame draws whole, -1 for none
        //The refresh being drawn a slice a frame into the spare image (slices > 1): the map it replaces (-1: none), its next slice,
        // sun step and cascade, the serials of the frames that drew its slices; and the slice this frame draws (-1: none)
        int jobSlot = -1, jobSlice;
        long jobStep;
        ShadowMath.Cascade jobCascade;
        final long[] jobSerials;
        int sliceNow = -1;
        String vanillaStats = "0 draws";//the vanilla casters of its last draw

        Cascade(ShadowMath.Which which, String name, long renderPass, int depthFormat, int width, int height, int slices) {
            this.which = which;
            this.name = name;
            this.computeLabel = name + ".compute";
            this.drawLabel = name + ".draw";
            this.vanillaLabel = name + ".vanilla";
            this.width = width;
            this.height = height;
            this.slices = slices;
            this.jobSerials = new long[slices];
            this.depth = new VkImage2D[slices > 1 ? 3 : 2];
            this.framebuffer = new long[this.depth.length];
            try {
                for (int i = 0; i < this.depth.length; i++) {
                    this.depth[i] = VkImage2D.createDepth(depthFormat, width, height, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
                    this.framebuffer[i] = createFramebuffer(renderPass, this.depth[i].view(), width, height);
                }
            } catch (RuntimeException | LinkageError e) {
                for (int i = 0; i < this.depth.length; i++) {
                    if (this.framebuffer[i] != 0) vkDestroyFramebuffer(VkContext.device(), this.framebuffer[i], null);
                    if (this.depth[i] != null) this.depth[i].free();
                }
                throw e;
            }
        }

        /** The image neither map is in (slices > 1 only). */
        int spare() {
            return 3 - this.image[0] - this.image[1];
        }

        /** Map i was drawn in a frame that was not dropped. */
        boolean ready(int i) {
            return this.drawn[i] != null && this.drawnSerial[i] >= 0 && !VkFrames.isDropped(this.drawnSerial[i]);
        }

        void tick() {
            //VkFrames forgets dropped serials after a while: a dropped map is forgotten here before it can read as drawn
            for (int i = 0; i < 2; i++) {
                if (this.drawnSerial[i] >= 0 && VkFrames.isDropped(this.drawnSerial[i])) {
                    this.drawn[i] = null;
                    this.drawnSerial[i] = -1;
                    this.valid[i] = false;
                }
            }
            for (VkImage2D d : this.depth) {
                d.tick();
            }
        }

        void free() {
            for (int i = 0; i < this.depth.length; i++) {
                vkDestroyFramebuffer(VkContext.device(), this.framebuffer[i], null);
                this.depth[i].free();
            }
        }
    }

    /**
     * Whether a drawn map can be read this frame: drawn in a frame that ran, the frame before prepared maps too (one from
     * before a pause is stale) and the camera within maxMove blocks of where it was drawn (the map's reach). The sun's turn is
     * the steps' (ShadowSteps); a camera nearby is rebased.
     */
    static boolean mapValid(boolean fresh, boolean ready, ShadowMath.Cascade drawn, ShadowMath.Cascade now, float maxMove) {
        return fresh && ready && drawn != null && !ShadowMath.moved(drawn, now, maxMove);
    }

    /**
     * Performance (2026-10-21): a cascade with slices draws a missing map whole at once (the crossfade needs it), and its refresh
     * (the older map again at its own step, when due) into the spare image a slice a frame: started when due and no refresh runs,
     * carried on while refreshAlive, dropped when a map goes missing (the whole draw replaces it).
     */
    private void plan(Cascade c, boolean fresh, ShadowMath.Cascade now, float maxMove, boolean refreshDue, float radius) {
        for (int i = 0; i < 2; i++) {
            c.valid[i] = mapValid(fresh, c.ready(i), c.drawn[i], now, maxMove);
        }
        c.sliceNow = -1;
        ShadowSteps.Draw d = ShadowSteps.plan(this.sunStep, c.step, c.valid, c.drawnSerial, refreshDue && c.slices == 1);
        c.drawSlot = d == null ? -1 : d.slot();
        if (d != null) {
            c.jobSlot = -1;
            c.pendingStep = d.step();
            c.pending = ShadowMath.cascade(SunMath.sunDirection(ShadowSteps.timeOf(d.step())), now.cameraX(), now.cameraY(), now.cameraZ(), radius,
                    c.width, c.height);
            return;
        }
        if (c.slices == 1) return;
        if (c.jobSlot >= 0 && !refreshAlive(c.jobSerials, c.jobSlice, VkFrames.serial(), VkFrames::isDropped, c.jobCascade, now, maxMove)) {
            c.jobSlot = -1;
        }
        if (c.jobSlot < 0) {
            ShadowSteps.Draw r = refreshDue ? ShadowSteps.plan(this.sunStep, c.step, c.valid, c.drawnSerial, true) : null;
            if (r == null) return;
            c.jobSlot = r.slot();
            c.jobStep = r.step();
            c.jobSlice = 0;
            c.jobCascade = ShadowMath.cascade(SunMath.sunDirection(ShadowSteps.timeOf(r.step())), now.cameraX(), now.cameraY(), now.cameraZ(), radius,
                    c.width, c.height);
        }
        c.sliceNow = c.jobSlice;
        c.pendingStep = c.jobStep;
        c.pending = c.jobCascade;
    }

    /**
     * Whether a sliced refresh carries on this frame (serial): its done slices were drawn in the frames right before, none of them
     * dropped (the image would miss their casters, and its layout would not be the attachment's), and the camera stayed within the
     * map's reach of where the refresh started (else the finished map could not be read).
     */
    static boolean refreshAlive(long[] serials, int done, long serial, LongPredicate dropped, ShadowMath.Cascade started, ShadowMath.Cascade now,
                                float maxMove) {
        if (done > 0 && serials[done - 1] != serial - 1) return false;
        for (int i = 0; i < done; i++) {
            if (dropped.test(serials[i])) return false;
        }
        return !ShadowMath.moved(started, now, maxMove);
    }

    /**
     * Before the first upload commit, for a frame with sun views (frame not null): this frame's sun step and per cascade the map
     * to draw (ShadowSteps.plan; the near and close cascades refresh between the far one's), then each extension's
     * prepareShadow for every cascade drawn this frame. Ruling R12: every context carries both of this frame's streams.
     */
    public void prepare(VkUploadStream upload, VkDownloadStream download, ShadowFrame frame, int frameId, List<VisualsExtension> ext) {
        boolean fresh = this.lastPrepared == frame.serial() - 1;
        this.lastPrepared = frame.serial();
        this.preparedFrameId = frameId;
        this.sunStep = ShadowSteps.step(frame.timeOfDay());
        this.sunProgress = ShadowSteps.progress(frame.timeOfDay());
        this.plan(this.near, fresh, frame.near(), ShadowMath.NEAR_MAX_MOVE, Math.floorMod(frameId, ShadowMath.NEAR_INTERVAL) == ShadowMath.NEAR_PHASE,
                ShadowMath.NEAR_RADIUS);
        this.plan(this.far, fresh, frame.far(), ShadowMath.FAR_MAX_MOVE, frame.farDue(), ShadowMath.FAR_RADIUS);
        this.plan(this.close, fresh, frame.close(), ShadowMath.CLOSE_MAX_MOVE, Math.floorMod(frameId, ShadowMath.NEAR_INTERVAL) == ShadowMath.CLOSE_PHASE,
                ShadowMath.CLOSE_RADIUS);
        if (TRACE) {
            VisualsLog.info("shadowTrace maps frame " + frame.serial() + " fresh " + fresh + " tod " + frame.timeOfDay() + " step " + this.sunStep + " progress "
                    + this.sunProgress + " near " + trace(this.near) + " far " + trace(this.far) + " close " + trace(this.close));
        }
        if (this.near.drawSlot >= 0 || this.near.sliceNow >= 0) this.nearRedraws++;
        if (this.far.drawSlot >= 0 || this.far.sliceNow >= 0) this.farRedraws++;
        if (this.close.drawSlot >= 0 || this.close.sliceNow >= 0) this.closeRedraws++;
        if (FrameRecorder.ON) {
            for (Cascade c : this.cascades) {
                if (c.drawSlot >= 0) FrameRecorder.add(FrameRecorder.SHADOW_WHOLE, 1);
                if (c.sliceNow >= 0) FrameRecorder.add(FrameRecorder.SHADOW_SLICE, 1);
            }
        }
        for (Cascade c : this.cascades) {
            if (c.drawSlot < 0 && c.sliceNow < 0) continue;
            boolean whole = c.drawSlot >= 0;
            ShadowCascadeContext ctx = new ShadowCascadeContext(c.name, c.pending, c.width, c.height, frame.serial(), frameId, whole ? 0 : c.sliceNow,
                    whole ? 1 : c.slices, upload, download);
            for (VisualsExtension e : ext) {
                e.prepareShadow(ctx);
            }
        }
    }

    /**
     * After the upload commit, outside a render pass: per cascade drawn this frame the extensions' compute (outside the pass),
     * then the depth-only pass with the extensions' casters and the vanilla terrain (when vanillaOn, out to
     * ShadowMath.vanillaRadius), then the map is made readable by the lighting pass. Ruling R12: the context (one per cascade,
     * shared by the compute and the casters) carries both streams. A refresh slice (performance, 2026-10-21) draws into the spare
     * image, the first clearing it and taking the vanilla casters, the others loading it; the last makes it readable and swaps it
     * in for the refreshed map.
     */
    public void draw(VkCommandBuffer cmd, VkUploadStream upload, VkDownloadStream download, boolean vanillaOn, int renderDistanceChunks,
                     List<VisualsExtension> ext) {
        this.vanillaOn = vanillaOn;
        for (Cascade c : this.cascades) {
            if (c.drawSlot < 0 && c.sliceNow < 0) continue;
            boolean whole = c.drawSlot >= 0;
            int image = whole ? c.image[c.drawSlot] : c.spare();
            int slice = whole ? 0 : c.sliceNow, slices = whole ? 1 : c.slices;
            long pass = slice == 0 ? this.renderPass : this.loadPass;
            ShadowCascadeContext ctx = new ShadowCascadeContext(c.name, c.pending, c.width, c.height, this.lastPrepared, this.preparedFrameId, slice, slices,
                    upload, download);
            VkGpuTimer.mark(cmd, c.computeLabel);
            for (VisualsExtension e : ext) {
                e.recordShadowCompute(cmd, ctx);
            }
            VkGpuTimer.mark(cmd, c.drawLabel);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkClearValue.Buffer clears = VkClearValue.calloc(1, stack);
                clears.get(0).depthStencil().set(1.0f, 0);
                VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(pass)
                        .framebuffer(c.framebuffer[image]).pClearValues(clears);
                info.renderArea().offset().set(0, 0);
                info.renderArea().extent().set(c.width, c.height);
                vkCmdBeginRenderPass(cmd, info, VK_SUBPASS_CONTENTS_INLINE);
                this.open = true;
                //A positive viewport: row 0 is ndc y -1, as shadow.glsl reads it ((ndc + 1)/2 * size)
                vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).x(0).y(0).width(c.width).height(c.height).minDepth(0).maxDepth(1));
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.get(0).offset().set(0, 0);
                scissor.get(0).extent().set(c.width, c.height);
                vkCmdSetScissor(cmd, 0, scissor);
            }
            for (VisualsExtension e : ext) {
                e.drawShadowCasters(cmd, pass, ctx);
            }
            //Fork design 4.3: VulkanMod's vanilla terrain casts into every cascade (the far one too: without LODs it is the only caster);
            // a refresh takes it with its first slice
            if (vanillaOn && this.vanilla != null && slice == 0) {
                VkGpuTimer.mark(cmd, c.vanillaLabel);
                this.vanilla.draw(cmd, this.renderPass, c.pending, ShadowMath.vanillaRadius(c.which, renderDistanceChunks,
                        ShadowMath.nearReach(VisualsConfig.get().shadowDistance), ext.stream().anyMatch(VisualsExtension::castsFarTerrain)));
                c.vanillaStats = this.vanilla.stats();
            }
            vkCmdEndRenderPass(cmd);
            this.open = false;
            c.depth[image].assumeLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            int slot = c.drawSlot;
            if (!whole) {
                c.jobSerials[slice] = VkFrames.serial();
                c.jobSlice = slice + 1;
                if (c.jobSlice < slices) continue;//the next slice loads the depth from the attachment layout
                slot = c.jobSlot;
                c.jobSlot = -1;
                c.image[slot] = image;//the refreshed map's old image is the spare now
            }
            //The lighting pass (compute) reads it in the post-process
            c.depth[image].transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, TEST_STAGES, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            c.drawn[slot] = c.pending;
            c.drawnSerial[slot] = VkFrames.serial();
            c.step[slot] = c.pendingStep;
            c.valid[slot] = true;
        }
    }

    /**
     * After the download stream committed: this frame's cascade draws are done. Ruling R13: only the maps' own state is reset;
     * VisualsExtension.afterDownloads is the frame flow's, called once a frame (it resets the extension's shadow traversals too).
     */
    public void afterDownloads(VkCommandBuffer cmd) {
        for (Cascade c : this.cascades) {
            c.drawSlot = -1;
            c.sliceNow = -1;
        }
    }

    /** After a failure inside the pass: ends it, so Reforged's main pass is not begun inside ours. */
    public void endOpenPass(VkCommandBuffer cmd) {
        if (this.open) {
            vkCmdEndRenderPass(cmd);
            this.open = false;
        }
    }

    /**
     * The maps the lighting reads (ShadowSteps.read): the primary one and its view, the secondary one (null: none, its view is
     * then the primary's) with its weight; both drawn for a sun step, read rebased to this frame's camera.
     */
    public record Maps(ShadowMath.Cascade a, ShadowMath.Cascade b, float weightB, long viewA, long viewB) {
    }

    /** This frame's near maps after its draw, null when there are none (or this frame prepared no maps). */
    public Maps near() {
        return this.read(this.near);
    }

    public Maps far() {
        return this.read(this.far);
    }

    public Maps close() {
        return this.read(this.close);
    }

    private Maps read(Cascade c) {
        if (this.lastPrepared != VkFrames.serial()) return null;
        ShadowSteps.Read r = ShadowSteps.read(this.sunStep, this.sunProgress, c.step, c.valid);
        if (r == null) return null;
        int a = r.primary(), b = r.secondary();
        long viewA = c.depth[c.image[a]].sampledView();
        return new Maps(c.drawn[a], b < 0 ? null : c.drawn[b], b < 0 ? 0 : r.secondaryWeight(), viewA, b < 0 ? viewA : c.depth[c.image[b]].sampledView());
    }

    private static String trace(Cascade c) {
        return "[" + c.step[0] + (c.valid[0] ? "+" : "-") + " " + c.step[1] + (c.valid[1] ? "+" : "-") + " draw " + c.drawSlot
                + (c.sliceNow >= 0 ? " refresh " + c.jobSlot + " slice " + c.sliceNow : "") + "]";
    }

    /**
     * For the periodic log: the redraws per cascade since the last call (they restart from 0), and the vanilla casters of each
     * cascade's last draw (when the setting is on), or why there are none.
     */
    public String stats() {
        String s = "redrawn near " + this.nearRedraws + ", far " + this.farRedraws + ", close " + this.closeRedraws;
        this.nearRedraws = 0;
        this.farRedraws = 0;
        this.closeRedraws = 0;
        if (this.vanilla == null) return s + ", vanilla unavailable";
        if (this.vanilla.disabled()) return s + ", vanilla disabled";
        if (!this.vanillaOn) return s;
        return s + ", vanilla near " + this.near.vanillaStats + ", far " + this.far.vanillaStats + ", close " + this.close.vanillaStats;
    }

    /** Call when a frame starts recording (VkFrames listener). */
    public void tick() {
        this.near.tick();
        this.far.tick();
        this.close.tick();
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        if (this.vanilla != null) this.vanilla.free();
        this.near.free();
        this.far.free();
        this.close.free();
        vkDestroyRenderPass(VkContext.device(), this.loadPass, null);
        vkDestroyRenderPass(VkContext.device(), this.renderPass, null);
    }

    /** clear: the map is cleared (a whole draw, a refresh's first slice); else loaded from the attachment layout (its later slices). */
    static long createRenderPass(int depthFormat, boolean clear) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
            attachment.get(0).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(clear ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack).attachment(0).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .pDepthStencilAttachment(depthRef);
            //The last read of the map (the lighting pass, compute) and the last write finish before the clear
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | TEST_STAGES).srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(TEST_STAGES).dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachment).pSubpasses(subpass).pDependencies(dependency);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateRenderPass(VkContext.device(), info, null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateRenderPass failed: " + result);
            return p.get(0);
        }
    }

    static long createFramebuffer(long renderPass, long depthView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                    .pAttachments(stack.longs(depthView)).width(width).height(height).layers(1);
            LongBuffer p = stack.mallocLong(1);
            int result = vkCreateFramebuffer(VkContext.device(), info, null, p);
            if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateFramebuffer failed: " + result);
            return p.get(0);
        }
    }
}
