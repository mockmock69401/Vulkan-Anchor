package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.framegen.CameraPrediction.CameraSample;
import net.vulkanmod.visuals.render.TaaMath;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.queue.QueueLock;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSignalInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The frame generation pacer (FG design 3.1, 3.2): with frame generation on, its thread ("VulkanMod Frame Pacer") owns
 * vkAcquireNextImageKHR and vkQueuePresentKHR. It composes each real frame the render thread hands over at once, then presents the
 * generated frames FramePacing schedules between it and the next one. The scheduling decisions are FramePacing's, the camera
 * CameraPrediction's, the slot hand-over SlotLedger's; this class is the Vulkan around them.
 *
 * Synchronisation:
 * - Two timeline semaphores. The render timeline is signalled by every paced render submit (RealFrame.renderValue); each pacer submit
 *   waits on the value of the frame it reads. The pacer timeline is signalled by every pacer submit with a value reserved in SlotLedger
 *   for the slot it reads; the render submit that overwrites a slot waits on SlotLedger.readValue(slot).
 * - The ledger's monitor is the hand-over lock: hand() publishes the newest frame under it, and the pacer takes the frame, records,
 *   reserves and submits under it. So a reserved value is always submitted before the render thread can read it (no wait-before-signal
 *   on the one queue), and the pacer never reserves a slot after a newer frame was handed: the render thread reuses a slot only for the
 *   frame after next, so a frame taken before that hand-over is safe to read until the render submit waits for the reservation.
 * - Every pacer submit goes to the graphics queue, in order (the filled image is shared by consecutive generated frames), through
 *   QueueLock; the lock is never held while taking the ledger's monitor.
 * - Pacer frames in flight ({@link #FRAMES}): a command buffer, an acquire semaphore, a fence and parameter buffer each, reused after
 *   the fence. The present semaphores are per swapchain image (reused when that image is acquired again).
 * - Measurement (Task 10): each pacer frame writes two timestamps (the GPU time of its warp and compose); its log record is held until the
 *   frame's fence has passed and then written with that time, so the log's order can differ from the presents' by up to FRAMES frames. The
 *   debug views (VisualsConfig.fgView): 1 composes, at each real present, the previous real frame warped to this one's camera; 2 shows
 *   generated frames only (a real frame is taken and logged, not presented). A FrameBurst armed before the run composes the next N presents
 *   a second time into an image for its worker to write.
 * - Image layouts: the swapchain images and the swapchain depth are the pacer's alone in paced mode and get raw barriers from UNDEFINED
 *   every frame; a slot's world and UI images are only read (the render thread leaves them in SHADER_READ_ONLY_OPTIMAL).
 */
public final class FramePacer {
    /** Pacer frames in flight; frame i writes parameter buffer i. */
    static final int FRAMES = FrameGenTargets.PACER_PARAMS;
    /** vkAcquireNextImageKHR returns after this at the latest, so a stop request is seen while the swapchain has no free image. */
    private static final long ACQUIRE_TIMEOUT_NS = 100_000_000L;
    /** Where a pacer submit waits for the render timeline: the warp passes (compute) and compose (fragment) read the slot. */
    private static final int READ_STAGES = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    /**
     * Where a render submit waits for the pacer timeline: the capture (compute, after a barrier from compute and fragment), the UI
     * layer's transition (fragment) and its render pass (colour attachment output) write the slot.
     */
    public static final int RENDER_WAIT_STAGES = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    private static final long FOREVER = -1L;
    /** Timestamps per pacer frame: start, after the warp, end. */
    private static final int QUERIES = 3;

    private enum Result { REAL, GENERATED, NONE, OUT_OF_DATE, STOPPED }

    /** What one run (start to stop) presents into; fixed for the run, published to the thread by Thread.start. */
    private record Run(FrameGenTargets targets, VkWarp warp, long renderPass, SwapChain swapChain, long swapchain, long[] images,
                       long depthImage, int depthAspect, int width, int height) {
    }

    private final VkDevice device;
    private final PacerState state = new PacerState();
    private final SlotLedger ledger = new SlotLedger(FrameGenTargets.SLOTS);
    private final PresentLog log = new PresentLog();
    private final long renderTimeline, pacerTimeline;
    private final long commandPool;
    private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES];
    private final long[] acquired = new long[FRAMES];
    private final long[] fences = new long[FRAMES];
    /** Three timestamp queries per pacer frame (start, after the warp, end); 0 unless the recorder is on and the graphics queue has timestamps; ticks are masked to validMask and scaled by period. */
    private final long queryPool;
    private final float period;
    private final long validMask;
    /** Per pacer frame: its log record, held until the fence has passed and the GPU time can be read. Pacer thread; the render thread after the join. */
    private final long[][] unlogged = new long[FRAMES][];
    /** Per pacer frame: whether it warped (its gpuNs is the warp span; else the compose span). */
    private final boolean[] unloggedWarped = new boolean[FRAMES];
    /** Per swapchain image; rebuilt by start when the image count changed (only after a swapchain recreation, device idle). */
    private long[] presentReady = new long[0];

    /** Render thread. */
    private long renderValue;
    private Thread thread;

    /** Guarded by the ledger's monitor; volatile so the pacer's waits can poll it without the lock. */
    private volatile RealFrame pending;
    /** Guarded by the ledger's monitor. */
    private long dropped;
    private volatile boolean failed;

    /** Pacer thread (set up by start before the thread starts). */
    private Run run;
    private FramePacing pacing;
    private CameraPrediction prediction;
    private RealFrame shown;
    /** The real frame shown before {@link #shown} and its parameter block (debug view 1 warps it to the shown frame's camera); merged is view 1's block. */
    private RealFrame previous;
    private long shownPresentNs;
    private final ByteBuffer captured = MemoryUtil.memAlloc(FrameGenParams.SIZE).order(ByteOrder.nativeOrder());
    private final ByteBuffer previousCaptured = MemoryUtil.memAlloc(FrameGenParams.SIZE).order(ByteOrder.nativeOrder());
    private final ByteBuffer merged = MemoryUtil.memAlloc(FrameGenParams.SIZE).order(ByteOrder.nativeOrder());
    /** The burst the run captures into (null: none); armed while stopped, dropped by the pacer once its N presents are claimed. */
    private volatile FrameBurst burst;
    //The captures recorded into the current frame: host buffers, their kind, the file index
    private final int[] captureBuffers = new int[2];
    private final boolean[] captureReal = new boolean[2];
    private int captureCount, captureIndex;
    private int frame;
    private long submittedValue;

    /** Render thread, with the timeline semaphore feature enabled (VkFeaturePlan.frameGenerationSupported). */
    public FramePacer() {
        this.device = VkContext.device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.renderTimeline = createTimeline(stack);
            this.pacerTimeline = createTimeline(stack);

            LongBuffer p = stack.mallocLong(1);
            check(vkCreateCommandPool(this.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(Queue.getQueueFamilies().graphicsFamily), null, p), "vkCreateCommandPool");
            this.commandPool = p.get(0);

            int bits = VkContext.graphicsTimestampBits();
            long pool = 0;
            //the timestamps cost a reset, three writes and a read per present and a record allocation: only while the recorder is on
            if (FrameRecorder.ON && bits > 0 && VkContext.timestampPeriod() > 0) {
                int r = vkCreateQueryPool(this.device, VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP)
                        .queryCount(QUERIES * FRAMES), null, p);
                if (r == VK_SUCCESS) pool = p.get(0);
                else VisualsLog.warn("Frame generation GPU times off: vkCreateQueryPool " + r);
            }
            this.queryPool = pool;
            this.period = VkContext.timestampPeriod();
            this.validMask = bits >= 64 || bits <= 0 ? -1L : (1L << bits) - 1;

            PointerBuffer buffers = stack.mallocPointer(FRAMES);
            check(vkAllocateCommandBuffers(this.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES), buffers), "vkAllocateCommandBuffers");
            for (int i = 0; i < FRAMES; i++) {
                this.commands[i] = new VkCommandBuffer(buffers.get(i), this.device);
                this.acquired[i] = createBinarySemaphore(stack);
                check(vkCreateFence(this.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, p), "vkCreateFence");
                this.fences[i] = p.get(0);
            }
        }
    }

    // ---- render thread ----

    /**
     * Starts a run presenting into the current swapchain with the targets' slots: builds the compose pipeline for presentRenderPass
     * and the swapchain framebuffers here, so the pacer never builds either. False when it is already running or there is nothing
     * to present into (no targets, no compose sets, no swapchain area). Render thread.
     */
    public boolean start(FrameGenTargets t, VkWarp w, long presentRenderPass, int factor) {
        SwapChain sc = Vulkan.getSwapChain();
        if (this.failed || !t.ready() || !w.composeReady() || sc.getWidth() == 0 || sc.getHeight() == 0) return false;
        if (!this.state.start()) return false;
        try {
            w.prepareCompose(presentRenderPass);
            int count = sc.getImagesNum();
            long[] images = new long[count];
            for (int i = 0; i < count; i++) {
                images[i] = sc.getColorAttachment(i).getId();
                sc.getFramebufferId(presentRenderPass, i);
            }
            this.ensurePresentSemaphores(count);
            VulkanImage depth = sc.getDepthAttachment();
            int aspect = VulkanImage.hasStencilComponent(depth.format) ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
            this.run = new Run(t, w, presentRenderPass, sc, sc.getId(), images, depth.getId(), aspect, sc.getWidth(), sc.getHeight());
            this.pacing = new FramePacing(factor);
            this.prediction = new CameraPrediction();
            this.shown = null;
            this.previous = null;
            Thread th = new Thread(this::loop, "VulkanMod Frame Pacer");
            th.setDaemon(true);
            this.thread = th;
            th.start();
            return true;
        } catch (RuntimeException | Error e) {
            this.thread = null;
            this.state.requestStop();
            this.state.stopped();
            throw e;
        }
    }

    /** Hands the newest real frame to the pacer; an older one not taken yet is dropped. Render thread, after the frame's submit; never blocks long. */
    public void hand(RealFrame f) {
        synchronized (this.ledger) {
            if (this.pending != null) this.dropped++;
            this.pending = f;
        }
        Thread t = this.thread;
        if (t != null) LockSupport.unpark(t);
    }

    /**
     * Stops the run: the thread finishes the present it is in, then exits; this joins it and waits until the GPU has finished every
     * pacer submit (the pacer timeline at its last reserved value). Frames handed but not shown are dropped. A no-op when not running.
     * Render thread; afterwards the targets may be rebuilt or freed.
     */
    public void stop() {
        if (!this.state.requestStop()) return;
        Thread t = this.thread;
        if (t != null) {
            LockSupport.unpark(t);
            boolean interrupted = false;
            while (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        this.thread = null;
        synchronized (this.ledger) {
            this.pending = null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            //a failed run may have left a fence reset and never submitted; its reservations were released on the timeline
            if (!this.failed) vkWaitForFences(this.device, stack.longs(this.fences), true, FOREVER);
            this.waitPacerTimeline(stack, this.ledger.lastValue());
        }
        //every pacer frame is done: the records held for their GPU times
        for (int i = 0; i < FRAMES; i++) this.flushUnlogged(i);
        this.state.stopped();
    }

    /** The value the next paced render submit signals on the render timeline. Render thread. */
    public long nextRenderValue() {
        return ++this.renderValue;
    }

    /** The last value a paced render submit signalled. Render thread. */
    public long renderValue() {
        return this.renderValue;
    }

    public long renderTimeline() {
        return this.renderTimeline;
    }

    public long pacerTimeline() {
        return this.pacerTimeline;
    }

    public SlotLedger ledger() {
        return this.ledger;
    }

    /** Arms burst for the next run (or clears it with null). Render thread, stopped. */
    void arm(FrameBurst b) {
        this.burst = b;
    }

    /** The present log (FrameGen drains it on the render thread). */
    public PresentLog log() {
        return this.log;
    }

    /** Real frames handed and never shown, because a newer one was handed first. */
    public long dropped() {
        synchronized (this.ledger) {
            return this.dropped;
        }
    }

    /** The pacer thread died of an exception; it is not started again. Any thread. */
    public boolean failed() {
        return this.failed;
    }

    public boolean running() {
        return this.state.running();
    }

    /** Destroys the pacer's Vulkan objects. Render thread, stopped, with the device idle. */
    public void free() {
        this.stop();
        for (int i = 0; i < FRAMES; i++) {
            vkDestroySemaphore(this.device, this.acquired[i], null);
            vkDestroyFence(this.device, this.fences[i], null);
        }
        for (long s : this.presentReady) vkDestroySemaphore(this.device, s, null);
        this.presentReady = new long[0];
        if (this.queryPool != 0) vkDestroyQueryPool(this.device, this.queryPool, null);
        vkDestroyCommandPool(this.device, this.commandPool, null);
        vkDestroySemaphore(this.device, this.renderTimeline, null);
        vkDestroySemaphore(this.device, this.pacerTimeline, null);
        MemoryUtil.memFree(this.captured);
        MemoryUtil.memFree(this.previousCaptured);
        MemoryUtil.memFree(this.merged);
    }

    private void ensurePresentSemaphores(int count) {
        if (this.presentReady.length == count) return;
        for (long s : this.presentReady) vkDestroySemaphore(this.device, s, null);
        long[] created = new long[count];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < count; i++) created[i] = createBinarySemaphore(stack);
        }
        this.presentReady = created;
    }

    // ---- pacer thread ----

    private void loop() {
        Run r = this.run;
        try {
            while (this.state.running()) {
                if (!this.awaitPending()) break;
                Result res = VisualsConfig.fgView == 2 && this.skipReal() ? Result.REAL : this.present(r, -1, VisualsConfig.fgView);
                while (res == Result.REAL) res = this.generated(r);
                if (res == Result.OUT_OF_DATE) {
                    //the render thread recreates the swapchain and stops this run; nothing is presented until then
                    while (this.state.running()) LockSupport.park(this);
                }
            }
        } catch (Throwable e) {
            this.failed = true;
            VisualsLog.error("Frame generation pacer failed; frame generation stays off until restart", e);
            try {
                this.releaseReservations();
            } catch (Throwable inner) {
                VisualsLog.error("Frame generation pacer could not release its timeline", inner);
            }
            Renderer.scheduleSwapChainUpdate();
        }
    }

    /** Parks until a real frame was handed (true) or the run is stopping (false). */
    private boolean awaitPending() {
        while (this.state.running()) {
            if (this.pending != null) return true;
            LockSupport.park(this);
        }
        return false;
    }

    /** The generated frames after the real frame just shown, at FramePacing's targets. */
    private Result generated(Run r) {
        long realAt = this.shownPresentNs;
        for (long target : this.pacing.schedule(realAt)) {
            this.waitUntil(target);
            if (!this.state.running()) return Result.STOPPED;
            RealFrame f = this.shown;
            boolean newer = this.pending != null;
            FramePacing.Decision d = this.pacing.decide(target, realAt, newer, !canGenerate(f));
            if (d != FramePacing.Decision.PRESENT) {
                this.log.record(PresentLog.KIND_GENERATED, f.renderValue(), target, 0, 0, d.ordinal(), f.inputNanos(), 0);
                if (newer) return Result.NONE;
                continue;
            }
            Result res = this.present(r, target, VisualsConfig.fgView);
            if (res != Result.GENERATED) return res;
        }
        return Result.NONE;
    }

    private static boolean canGenerate(RealFrame f) {
        return f.generate() && f.camera() != null && f.sample() != null;
    }

    /** LockSupport.parkNanos until SPIN_NS before target, then spins; returns early when a newer frame is handed or the run stops. */
    private void waitUntil(long target) {
        while (this.state.running() && this.pending == null) {
            long left = target - System.nanoTime();
            if (left <= 0) return;
            if (left > FramePacing.SPIN_NS) LockSupport.parkNanos(this, left - FramePacing.SPIN_NS);
            else Thread.onSpinWait();
        }
    }

    /**
     * Debug view 2 (generated frames only): takes the pending real frame as a real present would, without presenting it. False when
     * nothing is pending or the frame cannot be generated from (it is then presented as usual). The log gets its real record, so the
     * pacing and the F3 rates go on; the frame is "composed into nothing".
     */
    private boolean skipReal() {
        RealFrame f;
        synchronized (this.ledger) {
            RealFrame newer = this.pending;
            if (newer == null || !canGenerate(newer)) return false;
            this.pending = null;
            this.take(this.run, newer);
            f = newer;
        }
        long now = System.nanoTime();
        this.realShown(f, now);
        this.log.record(PresentLog.KIND_REAL, f.renderValue(), f.handNanos(), now, 0, FramePacing.Decision.PRESENT.ordinal(), f.inputNanos(), 0);
        return true;
    }

    /** A real frame was shown (or, in view 2, taken) at now: the pacing and the prediction follow it. */
    private void realShown(RealFrame f, long now) {
        this.shownPresentNs = now;
        this.pacing.realPresented(now);
        if (f.sample() != null) this.prediction.realFrame(f.sample());
    }

    /**
     * One present: target < 0 shows the pending real frame, otherwise a generated frame for target. If a newer real frame was handed by
     * the time the image is acquired, that real frame is shown instead (REAL). Never holds an acquired image without presenting it.
     * view is the debug view (VisualsConfig.fgView) this present follows: 1 shows the previous real frame warped to a new real frame's
     * camera in place of that frame; 2 shows a generated frame in place of a new real frame (which is taken and logged as usual).
     */
    private Result present(Run r, long target, int view) {
        int i = this.frame;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            //frame i's command buffer, semaphore and parameter buffer are free once its previous submit is done
            check(vkWaitForFences(this.device, stack.longs(this.fences[i]), true, FOREVER), "vkWaitForFences");
            this.flushUnlogged(i);

            IntBuffer pImage = stack.mallocInt(1);
            long acquireStart = System.nanoTime();
            int acquire;
            while (true) {
                acquire = vkAcquireNextImageKHR(this.device, r.swapchain, ACQUIRE_TIMEOUT_NS, this.acquired[i], VK_NULL_HANDLE, pImage);
                if (acquire != VK_TIMEOUT && acquire != VK_NOT_READY) break;
                if (!this.state.running()) return Result.STOPPED;
            }
            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
                Renderer.scheduleSwapChainUpdate();
                return Result.OUT_OF_DATE;
            }
            if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) throw new IllegalStateException("vkAcquireNextImageKHR failed: " + acquire);
            long acquireWait = System.nanoTime() - acquireStart;
            int image = pImage.get(0);
            this.frame = (i + 1) % FRAMES;

            //a pending real frame was taken here
            boolean tookReal;
            //the content is the real frame itself (else a generated frame, or in view 1 the warp of the previous real frame)
            boolean real;
            RealFrame warpFrom = null;
            RealFrame f;
            long value;
            long genTarget = target;
            synchronized (this.ledger) {
                RealFrame newer = this.pending;
                if (newer != null) {
                    this.pending = null;
                    this.take(r, newer);
                    tookReal = true;
                    real = view != 2 || !canGenerate(newer);
                    if (real && view == 1) warpFrom = this.warpSource(newer);
                } else if (target < 0 || this.shown == null) {
                    throw new IllegalStateException("no frame to present");
                } else {
                    tookReal = false;
                    real = false;
                }
                f = this.shown;
                if (!real && target < 0) genTarget = System.nanoTime();
                if (tookReal && !real) this.shownPresentNs = System.nanoTime();
                this.record(r, i, image, f, warpFrom, real, genTarget, view, stack);
                //a warp of the previous frame reads its slot as well: its last read is reserved first, the submit signals the later value
                if (warpFrom != null) this.ledger.reserveRead(warpFrom.slot());
                value = this.ledger.reserveRead(f.slot());
                this.submit(r, i, image, f.renderValue(), value, stack);
            }
            this.finishCaptures(value);

            long call = System.nanoTime();
            int presented = QueueLock.present(DeviceManager.getPresentQueue().queue(), VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(this.presentReady[image])).swapchainCount(1).pSwapchains(stack.longs(r.swapchain))
                    .pImageIndices(stack.ints(image)));
            long now = System.nanoTime();
            if (tookReal) {
                this.realShown(f, now);
                //view 2: the real frame is logged as taken; the present below is its generated frame
                if (!real) {
                    this.log.record(PresentLog.KIND_REAL, f.renderValue(), f.handNanos(), call, acquireWait, FramePacing.Decision.PRESENT.ordinal(),
                            f.inputNanos(), 0);
                }
            }
            this.logPresent(i, !real || warpFrom != null, real ? PresentLog.KIND_REAL : PresentLog.KIND_GENERATED, f.renderValue(),
                    real ? f.handNanos() : genTarget, call, acquireWait, f.inputNanos());

            if (acquire == VK_SUBOPTIMAL_KHR || presented == VK_SUBOPTIMAL_KHR || presented == VK_ERROR_OUT_OF_DATE_KHR) {
                Renderer.scheduleSwapChainUpdate();
                return Result.OUT_OF_DATE;
            }
            if (presented != VK_SUCCESS) throw new IllegalStateException("vkQueuePresentKHR failed: " + presented);
            return tookReal ? Result.REAL : Result.GENERATED;
        }
    }

    /**
     * Debug view 1, under the ledger's monitor, f just taken: the real frame before it when its world is still intact and usable: the
     * previous render submit (no frame between them was lost, so no later frame wrote its slot yet), the other slot, both generatable.
     */
    private RealFrame warpSource(RealFrame f) {
        RealFrame p = this.previous;
        if (p == null || !canGenerate(p) || !canGenerate(f) || p.slot() == f.slot() || p.renderValue() + 1 != f.renderValue()) return null;
        return p;
    }

    /** Under the ledger's monitor: f becomes the shown frame, with a copy of the render thread's parameter block for its slot. */
    private void take(Run r, RealFrame f) {
        this.previous = this.shown;
        MemoryUtil.memCopy(MemoryUtil.memAddress0(this.captured), MemoryUtil.memAddress0(this.previousCaptured), FrameGenParams.SIZE);
        ByteBuffer src = r.targets.paramsMapped(FrameGenTargets.captureParams(f.slot()));
        MemoryUtil.memCopy(MemoryUtil.memAddress0(src), MemoryUtil.memAddress0(this.captured), FrameGenParams.SIZE);
        this.shown = f;
    }

    // ---- the log and the GPU times ----

    /** Keeps a present's record until frame i's GPU time can be read (its fence passed); with no timestamps it is logged at once. */
    private void logPresent(int i, boolean warped, long kind, long renderValue, long scheduled, long call, long acquireWait, long input) {
        int present = FramePacing.Decision.PRESENT.ordinal();
        if (this.queryPool == 0) {
            this.log.record(kind, renderValue, scheduled, call, acquireWait, present, input, 0);
            return;
        }
        this.unlogged[i] = new long[]{kind, renderValue, scheduled, call, acquireWait, present, input, 0};
        this.unloggedWarped[i] = warped;
    }

    /** Logs frame i's held record with its GPU time (0 when it cannot be read). */
    private void flushUnlogged(int i) {
        long[] r = this.unlogged[i];
        if (r == null) return;
        this.unlogged[i] = null;
        r[7] = this.readGpu(i, this.unloggedWarped[i]);
        this.log.record(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]);
    }

    /**
     * The GPU nanoseconds of frame i: the warp span (generate only, FG gate G1.4) of a frame that warped, the compose span of one that did
     * not; 0 when the timestamps are not available.
     */
    private long readGpu(int i, boolean warped) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer results = stack.mallocLong(QUERIES);
            if (vkGetQueryPoolResults(this.device, this.queryPool, QUERIES * i, QUERIES, results, Long.BYTES, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) return 0;
            long ticks = warped ? results.get(1) - results.get(0) : results.get(2) - results.get(1);
            return (long) ((ticks & this.validMask) * (double) this.period);
        }
    }

    // ---- recording ----

    /**
     * Records pacer frame i: for a generated frame (or view 1's warp, from warpFrom's slot) the warp, then compose into swapchain image
     * image and its transition to present, between two timestamps; then the burst's captures, if any.
     */
    private void record(Run r, int i, int image, RealFrame f, RealFrame warpFrom, boolean real, long target, int view, MemoryStack stack) {
        this.writeParams(r, i, f, warpFrom, real, target);
        boolean warped = !real || warpFrom != null;

        VkCommandBuffer cmd = this.commands[i];
        check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
        check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
        if (this.queryPool != 0) {
            vkCmdResetQueryPool(cmd, this.queryPool, QUERIES * i, QUERIES);
            //at the compute stage: the wait for the render timeline (compute and fragment stages) is over by then
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, this.queryPool, QUERIES * i);
        }
        if (warped) r.warp.generate(cmd, warpFrom != null ? warpFrom.slot() : f.slot(), i);
        //after the warp (bottom of pipe: all of it done): the warp's GPU time is the span from the first stamp
        if (this.queryPool != 0) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, this.queryPool, QUERIES * i + 1);

        //the swapchain image and depth are rewritten whole: discard (UNDEFINED); the colour barrier chains to the acquire wait
        VkImageMemoryBarrier.Buffer in = VkImageMemoryBarrier.calloc(1, stack);
        barrier(in.get(0), r.images[image], VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                0, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, in);
        VkImageMemoryBarrier.Buffer depth = VkImageMemoryBarrier.calloc(1, stack);
        barrier(depth.get(0), r.depthImage, r.depthAspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        int tests = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        vkCmdPipelineBarrier(cmd, tests, tests, 0, null, null, depth);

        r.swapChain.beginRenderPass(cmd, r.renderPass, image, stack);
        r.warp.compose(cmd, r.renderPass, r.width, r.height, f.slot(), warped, i);
        vkCmdEndRenderPass(cmd);

        VkImageMemoryBarrier.Buffer out = VkImageMemoryBarrier.calloc(1, stack);
        barrier(out.get(0), r.images[image], VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, out);
        if (this.queryPool != 0) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, this.queryPool, QUERIES * i + 2);

        this.recordCaptures(r, cmd, stack, i, f, warpFrom, real, view);
        check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
    }

    /**
     * The burst's captures of this present (FrameBurst): the present itself as real or gen; in view 1 only a real present that warped, as the
     * pair (the warp as gen, the real frame as real). Presents view 1 does not warp are not part of the burst.
     */
    private void recordCaptures(Run r, VkCommandBuffer cmd, MemoryStack stack, int i, RealFrame f, RealFrame warpFrom, boolean real, int view) {
        this.captureCount = 0;
        FrameBurst b = this.burst;
        if (b == null) return;
        if (!b.wanted()) {
            this.burst = null;
            return;
        }
        boolean pair = view == 1;
        if (pair && warpFrom == null) return;
        int[] got = b.claim(pair ? 2 : 1);
        if (got == null) return;
        int index = b.nextIndex();
        for (int k = 0; k < got.length; k++) {
            //a pair is the warp first, then the real frame; a single capture is what this present shows
            boolean generated = pair ? k == 0 : !real;
            b.record(cmd, stack, r.renderPass, r.depthImage, r.depthAspect, got[k],
                    () -> r.warp.compose(cmd, r.renderPass, r.width, r.height, f.slot(), generated, i));
            this.captureBuffers[k] = got[k];
            this.captureReal[k] = pair ? k == 1 : real;
        }
        this.captureCount = got.length;
        this.captureIndex = index;
    }

    /** After the submit: the captures recorded into it are handed to the burst's worker, which waits for value; a spent burst is let go. */
    private void finishCaptures(long value) {
        FrameBurst b = this.burst;
        if (b == null) return;
        for (int k = 0; k < this.captureCount; k++) b.submitted(this.captureBuffers[k], this.captureIndex, this.captureReal[k], value);
        this.captureCount = 0;
        b.endIfSpent();
        if (!b.wanted()) this.burst = null;
    }

    /**
     * Parameter buffer i (free: frame i's fence was waited) for a real frame (the generated camera is the real one), a generated one, or
     * view 1's warp of warpFrom's world to f's camera (f's block, with the LOD matrix and depth flags of warpFrom's).
     */
    private void writeParams(Run r, int i, RealFrame f, RealFrame warpFrom, boolean real, long target) {
        RealFrame source = warpFrom != null ? warpFrom : f;
        TaaMath.Camera realCam = source.camera();
        Matrix4f invRealProj, genFromReal, realFromGen, invGenProj;
        if (realCam == null) {
            invRealProj = new Matrix4f();
            genFromReal = new Matrix4f();
            realFromGen = new Matrix4f();
            invGenProj = new Matrix4f();
        } else {
            TaaMath.Camera gen = warpFrom != null ? f.camera() : real ? realCam : this.generatedCamera(realCam, f.sample(), target);
            invRealProj = new Matrix4f(realCam.projection()).invert();
            genFromReal = WarpMath.genFromReal(gen, realCam);
            realFromGen = WarpMath.genFromReal(realCam, gen);
            invGenProj = new Matrix4f(gen.projection()).invert();
        }
        ByteBuffer block = this.captured;
        if (warpFrom != null) {
            //the world is warpFrom's (its LOD depth and validity); the vignette, UI and crosshair are f's
            MemoryUtil.memCopy(MemoryUtil.memAddress0(this.captured), MemoryUtil.memAddress0(this.merged), FrameGenParams.SIZE);
            MemoryUtil.memCopy(MemoryUtil.memAddress0(this.previousCaptured) + 64, MemoryUtil.memAddress0(this.merged) + 64, 64);
            this.merged.putFloat(FrameGenParams.FLAGS_AT, this.previousCaptured.getFloat(FrameGenParams.FLAGS_AT));
            this.merged.putFloat(FrameGenParams.FLAGS_AT + 4, this.previousCaptured.getFloat(FrameGenParams.FLAGS_AT + 4));
            block = this.merged;
        }
        FrameGenTargets t = r.targets;
        ByteBuffer dst = t.paramsMapped(i);
        dst.position(0);
        FrameGenParams.writePacer(dst, block, invRealProj, genFromReal, realFromGen, invGenProj, t.worldWidth(), t.worldHeight(),
                t.swapWidth(), t.swapHeight(), !real || warpFrom != null);
        dst.position(0);
    }

    /**
     * The camera of a generated frame (FG design 3.2, 3.3): the pose CameraPrediction gives for the content time (the real sample advanced
     * by how far the display moved past the real present, here the later of the target and now, capped by FramePacing.capShownAt), the real projection (bob included),
     * and the predicted rotation applied on top of the real model view, so a real view the two angles do not describe stays as it was.
     */
    private TaaMath.Camera generatedCamera(TaaMath.Camera realCam, CameraSample s, long target) {
        long shownAt = FramePacing.capShownAt(Math.max(target, System.nanoTime()), this.shownPresentNs, this.pacing.intervalEstimate());
        CameraPrediction.Pose pose = this.prediction.predict(FramePacing.contentTime(s.nanos(), this.shownPresentNs, shownAt));
        if (pose == null) return realCam;
        Matrix4f delta = CameraPrediction.modelView(pose.yaw(), pose.pitch()).mul(CameraPrediction.modelView(s.yaw(), s.pitch()).invert());
        return new TaaMath.Camera(realCam.projection(), delta.mul(realCam.modelView()), pose.x(), pose.y(), pose.z());
    }

    /**
     * Under the ledger's monitor: submits pacer frame i, waiting for the acquired image and the frame's render value, signalling the
     * image's present semaphore and value on the pacer timeline.
     */
    private void submit(Run r, int i, int image, long renderValue, long value, MemoryStack stack) {
        VkTimelineSemaphoreSubmitInfo timeline = VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                .waitSemaphoreValueCount(2).pWaitSemaphoreValues(stack.longs(0, renderValue))
                .signalSemaphoreValueCount(2).pSignalSemaphoreValues(stack.longs(0, value));
        VkSubmitInfo info = VkSubmitInfo.calloc(stack).sType$Default().pNext(timeline)
                .waitSemaphoreCount(2).pWaitSemaphores(stack.longs(this.acquired[i], this.renderTimeline))
                .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, READ_STAGES))
                .pCommandBuffers(stack.pointers(this.commands[i]))
                .pSignalSemaphores(stack.longs(this.presentReady[image], this.pacerTimeline));
        check(vkResetFences(this.device, stack.longs(this.fences[i])), "vkResetFences");
        int result = QueueLock.submit(DeviceManager.getGraphicsQueue().queue(), info, this.fences[i]);
        if (result != VK_SUCCESS) throw new IllegalStateException("pacer vkQueueSubmit failed: " + result);
        this.submittedValue = value;
    }

    /**
     * After a failure: every reserved value must be reached, or a render submit waiting on one never completes. Waits for the values
     * that were submitted (a host signal may not overtake a pending one), then signals the last reserved value from the host.
     */
    private void releaseReservations() {
        //only this thread reserves, and it is past its last reservation
        long last = this.ledger.lastValue();
        if (last <= this.submittedValue) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.waitPacerTimeline(stack, this.submittedValue);
            check(vkSignalSemaphore(this.device, VkSemaphoreSignalInfo.calloc(stack).sType$Default()
                    .semaphore(this.pacerTimeline).value(last)), "vkSignalSemaphore");
        }
    }

    private void waitPacerTimeline(MemoryStack stack, long value) {
        if (value <= 0) return;
        check(vkWaitSemaphores(this.device, VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                .pSemaphores(stack.longs(this.pacerTimeline)).pValues(stack.longs(value)), FOREVER), "vkWaitSemaphores");
    }

    static void barrier(VkImageMemoryBarrier b, long image, int aspect, int oldLayout, int newLayout, int srcAccess, int dstAccess) {
        b.sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image)
                .subresourceRange().set(aspect, 0, 1, 0, 1);
    }

    private long createTimeline(MemoryStack stack) {
        VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateSemaphore(this.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type), null, p), "vkCreateSemaphore (timeline)");
        return p.get(0);
    }

    private long createBinarySemaphore(MemoryStack stack) {
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateSemaphore(this.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, p), "vkCreateSemaphore");
        return p.get(0);
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: " + result);
    }
}
