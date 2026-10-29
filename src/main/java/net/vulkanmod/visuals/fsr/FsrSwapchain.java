package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.dev.VisualsPilot;
import net.vulkanmod.visuals.fsr.FsrLifecycle.Action;
import net.vulkanmod.visuals.fsr.FsrLifecycle.State;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.framebuffer.SwapchainFunctions;
import net.vulkanmod.vulkan.queue.QueueLock;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.Pointer.POINTER_SIZE;
import static org.lwjgl.vulkan.VK10.*;

/**
 * FSR design 4.3-4.4: FSR's frame-generation swapchain in place of VulkanMod's. Turning FSR on creates the swapchain context
 * (FSR replaces VulkanMod's VkSwapchainKHR with its own) and the frame-generation context; while live, VulkanMod's acquire,
 * present, create, destroy and get-images go through FSR's replacement functions ({@link SwapchainFunctions#current()}).
 * Turning it off waits for FSR's presents, destroys both and VulkanMod makes its own swapchain again. Everything happens in
 * Renderer's swapchain recreation (device idle), so FSR starts and stops in step with Optical Flow's pacer. Render thread.
 */
public final class FsrSwapchain {
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    private static final int VK_FORMAT_B8G8R8A8_UNORM = 44;

    private static State state = State.OFF;
    private static FfxApi api;
    /** Off-heap slots holding each ffxContext (the ffxContext* the ffx API takes); 0 when not made. */
    private static long swapchainContext;
    private static long frameGenerationContext;
    private static long submitCallback;
    private static Functions functions;
    /** The display size the frame-generation context was made for. */
    private static int contextWidth, contextHeight;
    /** Counts the frame-generation contexts made this session (a new context has no history: FSR resets). */
    private static long contextsMade;
    /** Finds a stale real swapchain, which FSR's functions hide from VulkanMod (FsrPresentWatch). */
    private static final FsrPresentWatch WATCH = new FsrPresentWatch();
    /** The longest {@link #quiescePresents} waits for FSR's presenter thread. */
    private static final long QUIESCE_TIMEOUT_NS = 200_000_000L;
    private static final boolean PILOT = System.getProperty(VisualsPilot.PROPERTY) != null;
    private static long nextPilotLogNs;
    /** With the pilot: the ffxApiMessage closure FSR's debug checks report through (made once, kept for the session). */
    private static long debugMessages;

    private FsrSwapchain() {
    }

    // ---- interface for the per-frame dispatch (Task 9) ----

    public static boolean live() {
        return state == State.LIVE;
    }

    public static FfxApi api() {
        return api;
    }

    /** FSR's VkSwapchainKHR (what ffxConfigureDescFrameGeneration.swapChain takes); 0 while not live or minimised. */
    public static long swapchain() {
        return live() ? Vulkan.getSwapChain().getId() : 0L;
    }

    /** The address holding the swapchain ffxContext (the ffxContext* for ffxQuery/ffxDispatch on it); 0 while not live. */
    public static long swapchainContext() {
        return live() ? swapchainContext : 0L;
    }

    /** The address holding the frame-generation ffxContext; 0 while not live. */
    public static long frameGenerationContext() {
        return live() ? frameGenerationContext : 0L;
    }

    /** Which frame-generation context is live: grows by one with every context made, so a remade one is told apart. */
    public static long frameGenerationContextSerial() {
        return contextsMade;
    }

    /** Frames FSR's swapchain presented since it was made (real and generated), or -1 without a live swapchain. */
    public static long lastPresentCount() {
        long sc = swapchain();
        return sc == 0L ? -1L : FfxCalls.lastPresentCount(functions.lastPresentCount, sc);
    }

    // ---- Renderer hooks ----

    /**
     * 2026-10-28 (DeceasedCraft: in fullscreen with FSR live, switching to another window froze the game): whether FSR's swapchain is
     * live and the window is minimized. Renderer.beginFrame then draws nothing and leaves a pending recreation for later: FSR's
     * presenter thread cannot present to the minimized fullscreen window, so a recreation's wait for FSR's presents (Vulkan.waitIdle)
     * would never return. Render thread.
     */
    public static boolean pausedMinimized() {
        if (state != State.LIVE) return false;
        return org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(net.minecraft.client.Minecraft.getInstance().getWindow().getWindow(),
                org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) == org.lwjgl.glfw.GLFW.GLFW_TRUE;
    }

    /** Renderer.beginFrame, when the window is restored after {@link #pausedMinimized}: the present count starts over. */
    public static void resumed() {
        WATCH.reset();
        //2026-10-30 (audit): no interpolation across the minimised stretch
        FsrFrame.forgetHistory();
    }

    /** Renderer.beginFrame, next to FrameGen.beginFrame: a toggled setting or a failure schedules the recreation that applies it. */
    public static void beginFrame() {
        if (state == State.OFF && !VisualsConfig.get().fsrFrameGeneration) return;
        if (pausedMinimized()) return;
        SwapChain sc = Vulkan.getSwapChain();
        Action a = FsrLifecycle.next(state, wanted(), sc.getWidth(), sc.getHeight(), false);
        if (a == Action.START || a == Action.STOP) {
            Renderer.scheduleSwapChainUpdate();
            return;
        }
        if (state == State.LIVE && sc.getId() != 0L && sc.getWidth() > 0) watchPresents(sc);
    }

    /**
     * Every FsrPresentWatch.WINDOW frames: a surface of another size or presents that stopped moving schedule a recreation;
     * presents stuck over several recreations turn FSR off. With the pilot, the present count is logged every two seconds.
     */
    private static void watchPresents(SwapChain sc) {
        boolean due = WATCH.due();
        boolean log = PILOT && System.nanoTime() >= nextPilotLogNs;
        if (!due && !log) return;
        try {
            long presents = FfxCalls.lastPresentCount(functions.lastPresentCount, sc.getId());
            if (log) {
                nextPilotLogNs = System.nanoTime() + 2_000_000_000L;
                VisualsLog.info("Visuals FSR: presents " + presents + " in " + WATCH.frames() + " frames of the "
                        + sc.getWidth() + "x" + sc.getHeight() + " swapchain");
            }
            if (!due) return;
            int surfaceW;
            int surfaceH;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
                KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(DeviceManager.physicalDevice, Vulkan.getSurface(), caps);
                surfaceW = caps.currentExtent().width();
                surfaceH = caps.currentExtent().height();
            }
            switch (WATCH.check(presents, surfaceW, surfaceH, sc.getWidth(), sc.getHeight())) {
                case RECREATE -> {
                    VisualsLog.info("Visuals FSR: real swapchain stale (surface " + surfaceW + "x" + surfaceH + ", swapchain "
                            + sc.getWidth() + "x" + sc.getHeight() + ", presents " + presents + "), recreating");
                    Renderer.scheduleSwapChainUpdate();
                }
                case GIVE_UP -> {
                    FsrSupport.markFailed("FSR's swapchain stopped presenting after " + FsrPresentWatch.GIVE_UP + " recreations", null);
                    Renderer.scheduleSwapChainUpdate();
                }
                default -> {
                }
            }
        } catch (RuntimeException | LinkageError e) {
            FsrSupport.markFailed("watching FSR's presents", e);
            Renderer.scheduleSwapChainUpdate();
        }
    }

    /** FSR's swapchain object and module as FsrInterpolationGate reads them. */
    private static final FsrInterpolationGate.Memory FSR_MEMORY = new FsrInterpolationGate.Memory() {
        @Override
        public long getLong(long address) {
            return memGetLong(address);
        }

        @Override
        public int getInt(long address) {
            return memGetInt(address);
        }

        @Override
        public byte getByte(long address) {
            return memGetByte(address);
        }
    };
    /** The longest {@link #beforePresent} waits; past it FSR is turned off (its presenter thread is not keeping up at all). */
    private static final long INTERPOLATION_WAIT_TIMEOUT_NS = 1_000_000_000L;
    /** Whether {@link #beforePresent} may read FSR's memory: set when FSR goes live and the module checks pass. */
    private static boolean gateOn;
    /** Once a check failed the gate stays off for the session (FSR then runs as it did before the gate). */
    private static boolean gateRefused;
    private static final FsrInterpolationGate.Checker GATE = new FsrInterpolationGate.Checker();
    /**
     * 2026-10-30 (audit): the interpolation wait that timed out in {@link #beforePresent} (null: none). That frame was not presented,
     * and the next wait for FSR's presents first gives FSR's presenter thread {@link #STALL_RECOVERY_NS} to free it, then skips FSR's
     * own wait (which would wait for that thread for good) and leaves the teardown to the device wait.
     */
    private static FsrInterpolationGate.Wait stalled;
    private static final long STALL_RECOVERY_NS = 3_000_000_000L;
    /** 2026-10-30 (audit): set by {@link #shutdown} when the window stays minimized: FSR's presenter cannot present, so nothing waits for it. */
    private static boolean presenterUnreachable;
    //with the pilot: frames that had to wait in beforePresent since the last log line, and how long they waited
    private static int gatedFrames;
    private static long gatedNs, gatedMaxNs, nextGateLogNs;

    /**
     * Renderer.submitFrame, right before FSR's present of a frame that dispatched generation (hang debugging 2026-10-25): waits
     * until the timeline semaphore value that this frame's interpolation will wait for on the game queue is signalled, so FSR never
     * submits that wait before its signal (FsrInterpolationGate: with such a wait pending, the presenter thread's next present
     * blocks for good). Usually the value is reached already; after a frame-time drop the render thread can be one present ahead
     * of FSR's presenter thread and waits for it here. Render thread; outside QueueLock (FSR's presenter thread needs no lock).
     * Never throws (the present and the frame fence come after it): a failed or implausible read turns the gate off for the
     * session, and a wait that times out turns FSR off (the recreation it schedules stops FSR; the game continues).
     * Returns whether to present the frame: false after a timed-out wait (2026-10-30 audit: presenting it would submit the very
     * wait-before-signal the gate prevents, and the recreation's wait for FSR's presents would then never return).
     */
    public static boolean beforePresent() {
        if (state != State.LIVE || !FsrFrame.takeGeneratedThisFrame() || !gateOn) return true;
        try {
            FsrInterpolationGate.Wait w = GATE.next(FSR_MEMORY, Vulkan.getSwapChain().getId());
            if (w == null) {
                if (GATE.failure() != null) refuseGate("FSR's swapchain state is implausible (" + GATE.failure() + ")");
                return true;
            }
            VkDevice device = Vulkan.getVkDevice();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer value = stack.mallocLong(1);
                if (VK12.vkGetSemaphoreCounterValue(device, w.semaphore(), value) == VK_SUCCESS && value.get(0) >= w.value()) return true;
                long start = System.nanoTime();
                VkSemaphoreWaitInfo info = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                        .pSemaphores(stack.longs(w.semaphore())).pValues(stack.longs(w.value()));
                int r = VK12.vkWaitSemaphores(device, info, INTERPOLATION_WAIT_TIMEOUT_NS);
                long waited = System.nanoTime() - start;
                if (r != VK_SUCCESS) {
                    //the frame is not presented (no wait-before-signal); FSR stops at the recreation (logged once)
                    gateOn = false;
                    stalled = w;
                    FsrSupport.markFailed("FSR's presenter did not free the interpolation output (" + waited / 1_000_000 + " ms, VkResult " + r + ")", null);
                    Renderer.scheduleSwapChainUpdate();
                    return false;
                }
                if (PILOT) tallyGate(start, waited);
            }
        } catch (RuntimeException | LinkageError e) {
            refuseGate("reading FSR's interpolation wait failed: " + e);
        }
        return true;
    }

    /**
     * Whether FSR's own wait for presents can return: false while the window stays minimized at shutdown, or when FSR's presenter
     * thread did not free the stalled interpolation output within {@link #STALL_RECOVERY_NS} (logged). Render thread.
     */
    private static boolean presenterReachable() {
        if (presenterUnreachable) return false;
        FsrInterpolationGate.Wait w = stalled;
        if (w == null) return true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo info = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                    .pSemaphores(stack.longs(w.semaphore())).pValues(stack.longs(w.value()));
            if (VK12.vkWaitSemaphores(Vulkan.getVkDevice(), info, STALL_RECOVERY_NS) == VK_SUCCESS) {
                stalled = null;
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals FSR: waiting for the stalled presenter failed: " + e);
        }
        VisualsLog.warn("Visuals FSR: the presenter thread is stuck; not waiting for FSR's presents");
        return false;
    }

    private static void tallyGate(long start, long waited) {
        gatedFrames++;
        gatedNs += waited;
        gatedMaxNs = Math.max(gatedMaxNs, waited);
        if (start >= nextGateLogNs) {
            nextGateLogNs = start + 2_000_000_000L;
            VisualsLog.info("Visuals FSR: " + gatedFrames + " frames waited for the presenter before presenting, "
                    + gatedNs / 100_000 / 10.0 + " ms in all, at most " + gatedMaxNs / 100_000 / 10.0 + " ms");
            gatedFrames = 0;
            gatedNs = gatedMaxNs = 0L;
        }
    }

    /** Turns the gate off for the session and says so once; FSR runs on as it did before the gate. */
    private static void refuseGate(String why) {
        gateOn = false;
        if (!gateRefused) VisualsLog.warn("Visuals FSR: interpolation gate off, " + why + "; uncapped FSR may freeze");
        gateRefused = true;
    }

    /**
     * When FSR's swapchain goes live: the gate is used only when the loaded DLL holds, at the RVAs its offsets were read from,
     * exactly those instructions (FsrInterpolationGate.SIGNATURES), and FSR's replacement functions lie inside that module (so the
     * swapchain handle they return is that module's object). Never throws.
     */
    private static void armGate(long imageBase) {
        GATE.reset();
        gateOn = false;
        if (gateRefused) return;
        try {
            long size = FsrInterpolationGate.imageSize(FSR_MEMORY, imageBase);
            if (size == 0L) {
                refuseGate("the FidelityFX module has no PE image at 0x" + Long.toHexString(imageBase));
                return;
            }
            Functions f = functions;
            if (f == null || !FsrInterpolationGate.inImage(imageBase, size, f.create, f.destroy, f.images, f.acquire, f.present, f.lastPresentCount)) {
                refuseGate("FSR's swapchain functions are not in the FidelityFX module");
                return;
            }
            String mismatch = FsrInterpolationGate.codeMismatch(FSR_MEMORY, imageBase);
            if (mismatch != null) {
                refuseGate("the loaded FidelityFX module differs from the one the gate was made for (" + mismatch + ")");
                return;
            }
            gateOn = true;
            if (PILOT) VisualsLog.info("Visuals FSR: interpolation gate on (module at 0x" + Long.toHexString(imageBase) + ", " + size + " bytes)");
        } catch (RuntimeException | LinkageError e) {
            refuseGate("checking the FidelityFX module failed: " + e);
        }
    }

    /**
     * Vulkan.waitIdle (also inside the recreation and FSR's stop), before the device wait: FSR's presenter thread goes quiet
     * ({@link #quiescePresents}), then FSR waits for its presents, so neither FSR's queue waits nor the device wait-idle race that
     * thread on FSR's present queue. FSR's wait runs under QueueLock (VulkanMod's other submitters stay out while it waits on the
     * game queue and the device); its game-queue submits run on this thread through the submit callback, which takes the reentrant
     * lock itself. A failure turns FSR off (the recreation that follows stops it).
     */
    public static void waitForPresents() {
        if (state != State.LIVE || !presenterReachable()) return;
        try {
            quiescePresents();
            QueueLock.locked(FsrSwapchain::dispatchWaitForPresents);
        } catch (RuntimeException | LinkageError e) {
            FsrSupport.markFailed("waiting for FSR's presents", e);
        }
    }

    /**
     * Waits until FSR's present count has stayed put for {@link FsrPresentQuiesce#stableNs} of the last frame interval (at most
     * {@link #QUIESCE_TIMEOUT_NS}): FSR's presenter thread signals its present semaphore before vkQueuePresentKHR, so FSR's own wait
     * for presents can otherwise reach vkQueueWaitIdle on the present queue while that thread is still presenting on it. Called
     * before every wait for presents and before a configure that turns generation on or off. Render thread; no-op while not live.
     */
    public static void quiescePresents() {
        Functions f = functions;
        long swapchain = Vulkan.getSwapChain().getId();
        if (state != State.LIVE || f == null || swapchain == 0L) return;
        long start = System.nanoTime();
        long stable = FsrPresentQuiesce.stableNs(FsrFrame.frameIntervalNs());
        FsrPresentQuiesce.Result r = FsrPresentQuiesce.await(() -> FfxCalls.lastPresentCount(f.lastPresentCount, swapchain), System::nanoTime,
                Thread::yield, stable, QUIESCE_TIMEOUT_NS);
        if (r == FsrPresentQuiesce.Result.TIMED_OUT) {
            VisualsLog.warn("Visuals FSR: presents still moving after " + QUIESCE_TIMEOUT_NS / 1_000_000 + " ms; waiting for them anyway");
        } else if (PILOT) {
            VisualsLog.info("Visuals FSR: presents quiet after " + (System.nanoTime() - start) / 100_000 / 10.0 + " ms (stable "
                    + stable / 1_000_000 + " ms)");
        }
    }

    /**
     * Renderer.recreateSwapChain, in place of SwapChain.recreate (device idle): recreates the swapchain (through FSR's functions
     * while live), then starts, stops or resizes FSR as {@link FsrLifecycle#next} decides. When FSR fails here it is turned off
     * for the session and VulkanMod's own swapchain is made again; the game continues.
     */
    public static void recreate(SwapChain sc) {
        Bridge bridge = new Bridge(sc, true);
        WATCH.reset();
        //2026-10-30 (audit): a recreation that keeps the context still starts FSR's history over (the frame before may not have run)
        FsrFrame.forgetHistory();
        //the device is idle: whatever stalled is over or abandoned (FSR is off after it)
        stalled = null;
        //turning FSR off: no FSR swapchain is made first only to be destroyed (stop makes VulkanMod's own)
        if (state == State.LIVE && !wanted()) {
            state = FsrLifecycle.run(state, Action.STOP, bridge);
            return;
        }
        try {
            sc.recreate();
        } catch (RuntimeException | LinkageError e) {
            if (state != State.LIVE) throw e;
            state = State.FAILED;
            bridge.failed("swapchain recreation", e);
            try {
                bridge.stop();
            } catch (RuntimeException | LinkageError stopFailed) {
                VisualsLog.error("Visuals FSR: tearing down after the failed recreation failed", stopFailed);
            }
            return;
        }
        boolean sizeChanged = sc.getWidth() != contextWidth || sc.getHeight() != contextHeight;
        Action a = FsrLifecycle.next(state, wanted(), sc.getWidth(), sc.getHeight(), sizeChanged);
        state = FsrLifecycle.run(state, a, bridge);
        //a swapchain FSR remade starts its counts over (start armed the gate already)
        if (state == State.LIVE && a != Action.START) GATE.reset();
    }

    /**
     * Minecraft.close, next to FrameGen.shutdown: FSR's threads stop presenting and both contexts go before the device does.
     * 2026-10-30 (audit: closing the minimized fullscreen game froze it, as the recreation did on 10-28): a minimized window is
     * restored first so FSR's presenter thread can present; if it stays minimized, FSR's wait for presents is skipped.
     */
    public static void shutdown() {
        if (state != State.LIVE) return;
        if (pausedMinimized() && !restoreForShutdown()) {
            presenterUnreachable = true;
            VisualsLog.warn("Visuals FSR: the window stays minimized at shutdown; not waiting for FSR's presents");
        }
        state = FsrLifecycle.run(state, Action.STOP, new Bridge(Vulkan.getSwapChain(), false));
    }

    /** Restores the minimized window and gives it up to a second to come back; whether it did. */
    private static boolean restoreForShutdown() {
        long window = net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
        org.lwjgl.glfw.GLFW.glfwRestoreWindow(window);
        long end = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < end) {
            org.lwjgl.glfw.GLFW.glfwPollEvents();
            if (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != org.lwjgl.glfw.GLFW.GLFW_TRUE) return true;
            Thread.onSpinWait();
        }
        return false;
    }

    private static boolean wanted() {
        VisualsConfig c = VisualsConfig.get();
        return FsrSettings.active(c.frameGeneration, c.fsrFrameGeneration, FsrSupport.current()) == FsrSettings.Active.FSR;
    }

    private static void dispatchWaitForPresents() {
        if (swapchainContext == 0L || memGetAddress(swapchainContext) == 0L || Vulkan.getSwapChain().getId() == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FfxApi.check(api.dispatch(swapchainContext, FfxStructs.waitForPresents(stack)), "ffxDispatch (wait for presents)");
        }
    }

    /** FSR's surface format for the swapchain format VulkanMod picks (SwapChain.getFormat prefers these two). */
    static int ffxFormat(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_R8G8B8A8_UNORM -> FfxStructs.FORMAT_R8G8B8A8_UNORM;
            case VK_FORMAT_B8G8R8A8_UNORM -> FfxStructs.FORMAT_B8G8R8A8_UNORM;
            default -> throw new FsrException("swapchain format " + vkFormat + " is not supported");
        };
    }

    /** {@link FsrLifecycle.Bridge} on the real swapchain; stop makes VulkanMod's own swapchain again when recreate is set. */
    private record Bridge(SwapChain sc, boolean recreate) implements FsrLifecycle.Bridge {
        /** Vulkan.waitIdle waits for FSR's presents first (a failed wait turns FSR off; the device wait still makes the teardown safe). */
        @Override
        public void waitIdle() {
            Vulkan.waitIdle();
        }

        @Override
        public void start() {
            FsrNative dll = FsrNative.instance().orElseThrow(() -> new FsrException("the FidelityFX DLL is not loaded"));
            DeviceManager.FsrQueues q = DeviceManager.getFsrQueues().orElseThrow(() -> new FsrException("no queues are reserved for FSR"));
            VkQueue game = q.game();
            VkDevice device = Vulkan.getVkDevice();

            api = new FfxApi(dll);
            submitCallback = FfxCalls.submitCallback((count, pSubmits, fence) -> QueueLock.submit(game, count, pSubmits, fence));
            swapchainContext = nmemCallocChecked(1, POINTER_SIZE);
            frameGenerationContext = nmemCallocChecked(1, POINTER_SIZE);

            FfxStructs.QueueInfo gameQueue = new FfxStructs.QueueInfo(game.address(), q.gameFamily(), submitCallback);
            //phase 1 runs without async workloads (ruling R7), but FSR still gets the compute queue when there is one
            FfxStructs.QueueInfo computeQueue = q.compute() == null ? new FfxStructs.QueueInfo(0L, 0, 0L)
                    : new FfxStructs.QueueInfo(q.compute().address(), q.computeFamily(), 0L);
            FfxStructs.QueueInfo presentQueue = new FfxStructs.QueueInfo(q.present().address(), q.presentFamily(), 0L);
            FfxStructs.QueueInfo acquireQueue = new FfxStructs.QueueInfo(q.acquire().address(), q.acquireFamily(), 0L);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                //FSR destroys the swapchain it is given: its image views go first, and SwapChain holds none until FSR's arrives
                long original = sc.releaseSwapchain();
                LongBuffer pSwapchain = stack.longs(original);
                //composition on the game queue (FSR's copy-and-present mode): with a graphics present family FSR would compose on
                // its present queue, whose presenter thread re-signals one binary semaphore for every present (the uncapped hang)
                long mode = FfxStructs.swapchainMode(stack, 0L, false);
                long desc = FfxStructs.createSwapchainVk(stack, mode, device.getPhysicalDevice(), device, memAddress(pSwapchain),
                        sc.getCreateInfo(), gameQueue, computeQueue, presentQueue, acquireQueue);
                int code = api.createContext(swapchainContext, desc);
                long replaced = pSwapchain.get(0);
                if (code != FfxApi.RETURN_OK) {
                    //FSR writes back 0 once it has destroyed the swapchain; left alone, it is still VulkanMod's
                    if (replaced == original) sc.adopt(original);
                    FfxApi.check(code, "ffxCreateContext (frame generation swapchain)");
                }

                long query = FfxStructs.queryReplacementFunctions(stack);
                try {
                    FfxApi.check(api.query(swapchainContext, query), "ffxQuery (swapchain replacement functions)");
                    functions = new Functions(query, memGetAddress(swapchainContext));
                } catch (RuntimeException | LinkageError e) {
                    //2026-10-30 (audit): without its functions FSR's swapchain cannot be destroyed through them; its context goes now
                    // (taking that swapchain with it), so the stop that follows makes VulkanMod's own on a free surface
                    try {
                        api.destroyContext(swapchainContext);
                    } catch (RuntimeException | LinkageError ignored) {
                    }
                    memPutAddress(swapchainContext, 0L);
                    throw e;
                }
                SwapchainFunctions.use(functions);
                sc.adopt(replaced);
            }
            armGate(dll.imageBase());

            createFrameGenerationContext();
            FsrCapture.setLive(true);
            VisualsLog.info("Visuals FSR: frame generation swapchain live, " + sc.getWidth() + "x" + sc.getHeight() + ", "
                    + sc.getImagesNum() + " images, swapchain format " + sc.getFormat() + ", composition on the game queue, queues game " + q.gameFamily()
                    + " present " + q.presentFamily() + " acquire " + q.acquireFamily()
                    + " compute " + (q.compute() == null ? "none" : q.computeFamily()));
        }

        @Override
        public void recreateFrameGenerationContext() {
            destroyFrameGenerationContext();
            createFrameGenerationContext();
            VisualsLog.info("Visuals FSR: frame generation context remade at " + contextWidth + "x" + contextHeight);
        }

        private void createFrameGenerationContext() {
            int width = sc.getWidth();
            int height = sc.getHeight();
            int backBuffer = ffxFormat(sc.getFormat());
            int flags = FfxStructs.FG_DEPTH_INVERTED | FfxStructs.FG_DEPTH_INFINITE;
            if (System.getProperty(VisualsPilot.PROPERTY) != null) flags |= FfxStructs.FG_DEBUG_CHECKING;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDevice device = Vulkan.getVkDevice();
                long chain = FfxStructs.backendVk(stack, device, device.getPhysicalDevice());
                //the HUD-less copy is RGBA8 (FsrCapture); FSR is told when the swapchain is not
                if (backBuffer != FfxStructs.FORMAT_R8G8B8A8_UNORM) {
                    chain = FfxStructs.createHudless(stack, chain, FfxStructs.FORMAT_R8G8B8A8_UNORM);
                }
                //no upscaling: the render size FSR sees is at most the display size
                long desc = FfxStructs.createFrameGeneration(stack, chain, flags, width, height, width, height, backBuffer);
                FfxApi.check(api.createContext(frameGenerationContext, desc), "ffxCreateContext (frame generation)");
            }
            contextWidth = width;
            contextHeight = height;
            contextsMade++;
            if (PILOT) reportDebugMessages();
        }

        /** FG_DEBUG_CHECKING's findings go to the log through an ffxApiMessage function (ffxConfigureDescGlobalDebug1). */
        private void reportDebugMessages() {
            if (debugMessages == 0L) {
                debugMessages = FfxCalls.messageCallback((type, text) -> {
                    String line = "Visuals FSR debug " + (type == FfxStructs.MESSAGE_TYPE_ERROR ? "error" : "warning") + ": " + text;
                    if (type == FfxStructs.MESSAGE_TYPE_ERROR) VisualsLog.error(line, null);
                    else VisualsLog.warn(line);
                });
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int code = api.configure(frameGenerationContext, FfxStructs.globalDebug(stack, debugMessages, FfxStructs.DEBUG_LEVEL_WARNINGS));
                if (code != FfxApi.RETURN_OK) VisualsLog.warn("Visuals FSR: the frame-generation context takes no debug message function (" + code + ")");
            }
        }

        /** Before the context goes, the swapchain is told to stop generating and to forget the HUD-less image (as the SDK sample). */
        private void destroyFrameGenerationContext() {
            if (frameGenerationContext == 0L || memGetAddress(frameGenerationContext) == 0L) return;
            long swapchain = sc.getId();
            if (swapchain != 0L) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    long off = FfxStructs.configureFrameGeneration(stack, swapchain, false, false, 0L, 0, contextWidth, contextHeight, 0L);
                    FfxApi.check(api.configure(frameGenerationContext, off), "ffxConfigure (frame generation off)");
                } catch (RuntimeException | LinkageError e) {
                    VisualsLog.warn("Visuals FSR: turning generation off before the context goes failed", e);
                }
            }
            FfxApi.check(api.destroyContext(frameGenerationContext), "ffxDestroyContext (frame generation)");
            contextWidth = contextHeight = 0;
        }

        /**
         * Every step runs whatever happened in the one before it (each catches RuntimeException and LinkageError); the first
         * failure is rethrown at the end, after VulkanMod's own swapchain is made again.
         */
        @Override
        public void stop() {
            Throwable first = null;
            FsrCapture.setLive(false);
            long presents = -1L;
            try {
                if (functions != null && sc.getId() != 0L) presents = FfxCalls.lastPresentCount(functions.lastPresentCount, sc.getId());
            } catch (RuntimeException | LinkageError e) {
                first = e;
            }
            try {
                destroyFrameGenerationContext();
            } catch (RuntimeException | LinkageError e) {
                if (first == null) first = e;
            }
            try {
                //FSR's destroy, while its context still lives, also ends its present threads and the real swapchain
                sc.destroySwapchain();
            } catch (RuntimeException | LinkageError e) {
                if (first == null) first = e;
                //what FSR could not destroy stays with it; VulkanMod forgets the handle and its views
                sc.releaseSwapchain();
            }
            SwapchainFunctions.use(SwapchainFunctions.NATIVE);
            functions = null;
            boolean contextGone = true;
            try {
                if (swapchainContext != 0L && memGetAddress(swapchainContext) != 0L) {
                    FfxApi.check(api.destroyContext(swapchainContext), "ffxDestroyContext (frame generation swapchain)");
                }
            } catch (RuntimeException | LinkageError e) {
                if (first == null) first = e;
                contextGone = false;
            }
            if (contextGone) {
                //FSR's threads call the submit callback until the swapchain context is gone
                FfxCalls.freeCallback(submitCallback);
            } else if (submitCallback != 0L) {
                VisualsLog.warn("Visuals FSR: the swapchain context may still live, so its queue submit callback is kept (leaked)");
            }
            submitCallback = 0L;
            nmemFree(swapchainContext);
            nmemFree(frameGenerationContext);
            swapchainContext = frameGenerationContext = 0L;
            contextWidth = contextHeight = 0;
            api = null;
            try {
                FsrCapture.free();
            } catch (RuntimeException | LinkageError e) {
                if (first == null) first = e;
            }
            VisualsLog.info("Visuals FSR: frame generation swapchain stopped" + (presents >= 0 ? " after " + presents + " presents" : ""));
            if (recreate) sc.recreate();
            if (first instanceof RuntimeException r) throw r;
            if (first instanceof Error e) throw e;
        }

        @Override
        public void failed(String what, Throwable t) {
            FsrSupport.markFailed(what, t);
        }
    }

    /** FSR's replacement swapchain functions (ffxQueryDescSwapchainReplacementFunctionsVK); create and destroy take the context. */
    private static final class Functions implements SwapchainFunctions {
        final long create;
        final long destroy;
        final long images;
        final long acquire;
        final long present;
        final long lastPresentCount;
        /** The swapchain ffxContext itself (pContext of create and destroy), not the slot holding it. */
        final long context;

        Functions(long query, long context) {
            this.create = FfxStructs.createSwapchain(query);
            this.destroy = FfxStructs.destroySwapchain(query);
            this.images = FfxStructs.getSwapchainImages(query);
            this.acquire = FfxStructs.acquireNextImage(query);
            this.present = FfxStructs.queuePresent(query);
            this.lastPresentCount = FfxStructs.lastPresentCount(query);
            this.context = context;
        }

        @Override
        public int acquire(long device, long swapchain, long timeout, long semaphore, long fence, IntBuffer index) {
            return FfxCalls.acquireNextImage(acquire, device, swapchain, timeout, semaphore, fence, memAddress(index));
        }

        @Override
        public int present(VkQueue q, VkPresentInfoKHR info) {
            return FfxCalls.queuePresent(present, q.address(), info.address());
        }

        @Override
        public long create(VkDevice d, VkSwapchainCreateInfoKHR info) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pSwapchain = stack.longs(0L);
                int result = FfxCalls.createSwapchain(create, d.address(), info.address(), NULL, memAddress(pSwapchain), context);
                if (result != VK_SUCCESS) throw new FsrException("FSR's swapchain creation failed: VkResult " + result);
                return pSwapchain.get(0);
            }
        }

        @Override
        public void destroy(VkDevice d, long swapchain) {
            FfxCalls.destroySwapchain(destroy, d.address(), swapchain, NULL, context);
        }

        @Override
        public long[] images(VkDevice d, long swapchain) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.ints(0);
                FfxCalls.getSwapchainImages(images, d.address(), swapchain, memAddress(count), NULL);
                LongBuffer handles = stack.mallocLong(count.get(0));
                int result = FfxCalls.getSwapchainImages(images, d.address(), swapchain, memAddress(count), memAddress(handles));
                if (result != VK_SUCCESS) throw new FsrException("FSR's swapchain images: VkResult " + result);
                long[] out = new long[count.get(0)];
                handles.get(out);
                return out;
            }
        }

        @Override
        public boolean retiresOldSwapchain() {
            return false;
        }
    }
}
