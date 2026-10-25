package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.dev.VisualsPilot;
import net.vulkanmod.visuals.render.TaaMath;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memPutByte;

/**
 * FSR design 4.5 steps 4 and 5: at the end of every frame presented through FSR's swapchain (DefaultMainPass.end, before the
 * submit), FSR is told this frame. In order, as the SDK sample does:
 * <ol>
 * <li>configure: generation on when the frame was captured with no pause reason, no async workloads (ruling R7), the HUD-less
 * colour, the whole display as the generation rectangle, the frame id; no callbacks.</li>
 * <li>prepare on the game's command buffer, chained with the camera: depth and motion vectors at the render size (motion vector
 * scale = render size, ruling R6), no jitter, the frame time, the near plane and vertical FOV of the frame's projection, one block a
 * metre. Every captured frame prepares, paused or not, so FSR's history is always the previous frame.</li>
 * <li>with generation on: FSR's interpolation command list and output texture are queried from the swapchain context and generation
 * is dispatched into that command list with this frame's swapchain image (UI included) as the present colour. FSR's swapchain runs
 * it at present, after the game queue's work. (The design named the frame-generation callback path; its callback takes
 * (params, userCtx) and would need an upcall per frame, so we dispatch ourselves as the SDK sample does by default.)</li>
 * </ol>
 * The frame id grows by one every frame, never skipped. Generation resets on a camera cut (FsrCapture), after a frame without
 * generation (FSR's previous frame is then older than the last one) and when the frame-generation context was remade.
 *
 * <p>Every change of configure's frameGenerationEnabled makes FSR wait for its presents and stop or start its presenter thread, so
 * generation turns off at once on a frame that does not want it but back on only after {@link #GENERATION_ON_AFTER} frames in a row
 * want it (a pause reason that flickers costs no reconfiguration), and FSR's presents are quiesced (FsrSwapchain.quiescePresents)
 * before the configure that turns it off. A frame never dispatches generation while it is configured off, so paused frames generate nothing.
 *
 * <p>The swapchain image is handed over in SHADER_READ_ONLY_OPTIMAL (DefaultMainPass), the layout FSR's swapchain expects of the
 * frames it is given (FrameInterpolationSwapchainVK's replacement buffers): its passthrough copy and the generation read it in that
 * layout, and FSR's backend restores it there after its own transitions. Render thread.
 */
public final class FsrFrame {
    /** The frame time of the first frame, which has no previous one. */
    static final float FIRST_FRAME_MS = 1000f / 60f;
    /** Real frames in a row that must want generation before FSR is told to turn it on again. */
    static final int GENERATION_ON_AFTER = 8;
    /** ffxDispatchDescFrameGenerationPrepare.cameraNear with inverted infinite depth (the SDK sample's FLT_MAX). */
    private static final float INFINITE_NEAR = Float.MAX_VALUE;

    private static final Sequencer SEQUENCER = new Sequencer();

    static {
        SEQUENCER.beforeReconfigure = FsrSwapchain::quiescePresents;
    }
    private static final boolean PILOT = System.getProperty(VisualsPilot.PROPERTY) != null;
    private static long nextPilotLogNs;
    /** Set by endFrame once FSR was told this frame and generation was dispatched; see {@link #takeGeneratedThisFrame}. */
    private static boolean generatedThisFrame;

    private FsrFrame() {
    }

    /**
     * One frame as FSR is told it. The handles are raw: FSR's swapchain, the slots holding the frame-generation and swapchain
     * contexts and which frame-generation context it is (FsrSwapchain's serial), this frame's swapchain image and its
     * ffx format, the HUD-less image, depth and motion images and their sizes; the camera is null when the frame was not captured.
     */
    record Frame(long swapchain, long frameGenerationContext, long swapchainContext, long contextSerial,
                 int displayWidth, int displayHeight, long presentImage, int presentFormat,
                 boolean captured, String pauseReason, boolean cut,
                 long hudLess, int hudLessWidth, int hudLessHeight,
                 long depth, long motion, int renderWidth, int renderHeight,
                 TaaMath.Camera camera, long nowNs) {
    }

    /** The id of the last frame FSR was told, -1 before the first. */
    public static long frameId() {
        return SEQUENCER.frameId();
    }

    /**
     * Whether this frame dispatched generation (FSR submits its interpolation in this frame's present), and forgets it: true only
     * when endFrame told FSR this frame and generation was dispatched; false after any early return, a failure, or a second call.
     * FsrSwapchain.beforePresent, render thread.
     */
    public static boolean takeGeneratedThisFrame() {
        boolean g = generatedThisFrame;
        generatedThisFrame = false;
        return g;
    }

    /** How long the last real frame FSR was told took, in nanoseconds; -1 when not known (no frame yet, a new context). */
    public static long frameIntervalNs() {
        return SEQUENCER.frameIntervalNs();
    }

    /**
     * Pilot only (VisualsPilot's "fsr debug"): FSR's debug drawings (FfxStructs.FG_DRAW_DEBUG_*) in configure's and prepare's flags
     * from the next frame on, and whether FSR's swapchain presents only the generated frames. 0 and false are the game's own values.
     */
    public static void pilotDebug(int flags, boolean onlyPresentGenerated) {
        SEQUENCER.debugFlags = flags;
        SEQUENCER.onlyPresentGenerated = onlyPresentGenerated;
    }

    /**
     * DefaultMainPass.end while FSR's swapchain is live, after the swapchain image went to SHADER_READ_ONLY_OPTIMAL and before the
     * command buffer ends. A failure turns FSR off (the recreation it schedules stops it); the game continues.
     */
    public static void endFrame(VkCommandBuffer cmd) {
        generatedThisFrame = false;
        FfxApi api = FsrSwapchain.api();
        long swapchain = FsrSwapchain.swapchain();
        long fgContext = FsrSwapchain.frameGenerationContext();
        long scContext = FsrSwapchain.swapchainContext();
        if (api == null || swapchain == 0L || fgContext == 0L || scContext == 0L || memGetAddress(fgContext) == 0L) return;
        SwapChain sc = Vulkan.getSwapChain();
        if (sc.getWidth() <= 0 || sc.getHeight() <= 0) return;
        try {
            FsrInputs inputs = FsrCapture.inputs();
            TaaMath.Camera camera = FsrCapture.camera();
            long hudLess = FsrCapture.hudLessImage();
            boolean captured = FsrCapture.capturedThisFrame() && inputs != null && camera != null && hudLess != 0L;
            Frame f = new Frame(swapchain, fgContext, scContext, FsrSwapchain.frameGenerationContextSerial(), sc.getWidth(), sc.getHeight(),
                    sc.getColorAttachment().getId(), FsrSwapchain.ffxFormat(sc.getFormat()),
                    captured, FsrCapture.pauseReason(), FsrCapture.resetThisFrame(),
                    hudLess, FsrCapture.hudLessWidth(), FsrCapture.hudLessHeight(),
                    captured ? inputs.depthImage().handle() : 0L, captured ? inputs.motionImage().handle() : 0L,
                    captured ? inputs.depthImage().width : 0, captured ? inputs.depthImage().height : 0,
                    captured ? camera : null, System.nanoTime());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SEQUENCER.run(stack, api, cmd.address(), f);
            }
            generatedThisFrame = SEQUENCER.generated();
            if (PILOT && f.nowNs() >= nextPilotLogNs) {
                nextPilotLogNs = f.nowNs() + 2_000_000_000L;
                VisualsLog.info("Visuals FSR: frame " + SEQUENCER.frameId() + ", " + SEQUENCER.tally());
            }
        } catch (RuntimeException | LinkageError e) {
            FsrSupport.markFailed("telling FSR this frame", e);
            Renderer.scheduleSwapChainUpdate();
        }
    }

    /**
     * The camera's up, right and forward in world space from a view matrix (world to view, camera looking down -z): its first
     * three rows, made orthonormal (Gram-Schmidt from forward). FSR's camera info.
     */
    static float[][] cameraVectors(Matrix4fc view) {
        Vector3f forward = new Vector3f(-view.m02(), -view.m12(), -view.m22()).normalize();
        Vector3f up = new Vector3f(view.m01(), view.m11(), view.m21());
        up.sub(new Vector3f(forward).mul(up.dot(forward))).normalize();
        Vector3f right = new Vector3f(forward).cross(up).normalize();
        return new float[][]{{up.x, up.y, up.z}, {right.x, right.y, right.z}, {forward.x, forward.y, forward.z}};
    }

    /** The vertical field of view in radians of a perspective projection (m11 = 1 / tan(fovY / 2)). */
    static float fovY(Matrix4fc projection) {
        return (float) (2.0 * Math.atan(1.0 / Math.abs(projection.m11())));
    }

    /** The per-frame sequence and the little it remembers between frames; package-private so tests drive it with a fake FfxApi. */
    static final class Sequencer {
        private long nextFrameId;
        private long lastNs = -1L;
        private long lastContext = -1L;
        private boolean generatedLast;
        private long lastIntervalNs = -1L;
        private final int onAfter;
        //what FSR's swapchain was last told (generation on, flags) and the frames in a row that wanted generation while it was off
        private boolean configuredOn;
        private int lastConfiguredFlags;
        private int wantedRun;
        /**
         * Called before a configure that turns generation off or changes the flags while it is on, when FSR's presenter thread runs
         * (FsrSwapchain.quiescePresents in the game); with generation off FSR presents on the calling thread and has no such thread.
         */
        Runnable beforeReconfigure = () -> {
        };
        //the pilot's debug drawings and generated-only presents (pilotDebug); 0 and false otherwise
        volatile int debugFlags;
        volatile boolean onlyPresentGenerated;
        //what the pilot log reports, since its last line
        private int frames, generated, resets;

        Sequencer() {
            this(GENERATION_ON_AFTER);
        }

        /** onAfter: the frames in a row that must want generation before it is turned on (1: at once). */
        Sequencer(int onAfter) {
            this.onAfter = onAfter;
        }

        long frameId() {
            return nextFrameId - 1;
        }

        long frameIntervalNs() {
            return lastIntervalNs;
        }

        /** Whether the last frame run dispatched generation. */
        boolean generated() {
            return generatedLast;
        }

        /** Generation as FSR is told it: off at once when the frame does not want it, on after onAfter wanting frames in a row. */
        private boolean debounce(boolean want) {
            if (!want) {
                wantedRun = 0;
                configuredOn = false;
            } else if (!configuredOn && ++wantedRun >= onAfter) {
                configuredOn = true;
            }
            return configuredOn;
        }

        /**
         * The previous frame's duration in milliseconds: from the last frame's end to this one's ({@link #FIRST_FRAME_MS} without a
         * last frame, lastNs &lt; 0).
         */
        static float frameTimeMs(long lastNs, long nowNs) {
            return lastNs < 0L ? FIRST_FRAME_MS : (nowNs - lastNs) / 1_000_000f;
        }

        /** Frames, generated frames and resets since the last call (the pilot's log line). */
        String tally() {
            String s = "generated " + generated + " of " + frames + " frames, " + resets + " resets";
            frames = generated = resets = 0;
            return s;
        }

        void run(MemoryStack stack, FfxApi api, long cmd, Frame f) {
            //a 0 handle in an FfxApiResource crashes inside FSR (ffx_vk.cpp): such a frame fails before FSR is told anything
            if (f.presentImage() == 0L || f.captured() && (f.hudLess() == 0L || f.depth() == 0L || f.motion() == 0L)) {
                throw new FsrException("a frame without its swapchain, HUD-less, depth or motion image");
            }
            long id = nextFrameId++;
            frames++;
            boolean contextChanged = f.contextSerial() != lastContext;
            lastContext = f.contextSerial();
            if (contextChanged) {
                //FsrSwapchain configured the swapchain off (flags 0) before the old context went; a first context starts off too
                configuredOn = false;
                lastConfiguredFlags = 0;
                wantedRun = 0;
            }
            //a new context (a resize, FSR off and on) starts the clock over: the time FSR was off is no frame time
            lastIntervalNs = contextChanged || lastNs < 0L ? -1L : f.nowNs() - lastNs;
            float frameTimeMs = frameTimeMs(contextChanged ? -1L : lastNs, f.nowNs());
            lastNs = f.nowNs();
            boolean wasOn = configuredOn;
            boolean generate = debounce(f.captured() && FsrCapture.generates(f.pauseReason()));
            boolean reset = f.cut() || contextChanged || !generatedLast;
            generatedLast = false;

            long hudLess = f.captured() ? FfxStructs.resource(stack, f.hudLess(), FfxStructs.RESOURCE_TYPE_TEXTURE2D, FfxStructs.FORMAT_R8G8B8A8_UNORM,
                    f.hudLessWidth(), f.hudLessHeight(), 1, 1, 0, FfxStructs.RESOURCE_USAGE_READ_ONLY, FfxStructs.RESOURCE_STATE_COMPUTE_READ) : 0L;
            int flags = debugFlags;
            //FSR waits for its presents on these changes; while generation is on its presenter thread runs, and goes quiet first
            if (wasOn && (!generate || flags != lastConfiguredFlags)) beforeReconfigure.run();
            lastConfiguredFlags = flags;
            long configure = FfxStructs.configureFrameGeneration(stack, f.swapchain(), generate, false, hudLess, flags,
                    f.displayWidth(), f.displayHeight(), id);
            if (onlyPresentGenerated) memPutByte(configure + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_ONLY_PRESENT_GENERATED, (byte) 1);
            FfxApi.check(api.configure(f.frameGenerationContext(), configure), "ffxConfigure (frame generation)");

            if (f.captured()) prepare(stack, api, cmd, f, id, flags, frameTimeMs);
            if (!generate) return;

            long pCommandList = stack.ncalloc(8, 1, 8);
            FfxApi.check(api.query(f.swapchainContext(), FfxStructs.queryInterpolationCommandList(stack, pCommandList)),
                    "ffxQuery (interpolation command list)");
            long commandList = memGetAddress(pCommandList);
            //FSR hands out no command list while its swapchain has generation off; nothing to record into this frame
            if (commandList == 0L) return;
            long output = stack.ncalloc(8, 1, FfxStructs.SIZE_FFX_API_RESOURCE);
            FfxApi.check(api.query(f.swapchainContext(), FfxStructs.queryInterpolationTexture(stack, output)),
                    "ffxQuery (interpolation texture)");
            if (memGetAddress(output + FfxStructs.OFF_FFX_API_RESOURCE_RESOURCE) == 0L) throw new FsrException("FSR's interpolation texture has no image");
            long present = FfxStructs.resource(stack, f.presentImage(), FfxStructs.RESOURCE_TYPE_TEXTURE2D, f.presentFormat(),
                    f.displayWidth(), f.displayHeight(), 1, 1, 0, FfxStructs.RESOURCE_USAGE_READ_ONLY, FfxStructs.RESOURCE_STATE_PIXEL_COMPUTE_READ);
            long dispatch = FfxStructs.dispatchFrameGeneration(stack, commandList, present, output, reset, f.displayWidth(), f.displayHeight(), id);
            FfxApi.check(api.dispatch(f.frameGenerationContext(), dispatch), "ffxDispatch (frame generation)");
            generatedLast = true;
            generated++;
            if (reset) resets++;
        }

        private static void prepare(MemoryStack stack, FfxApi api, long cmd, Frame f, long id, int flags, float frameTimeMs) {
            TaaMath.Camera camera = f.camera();
            float[][] axes = cameraVectors(camera.modelView());
            float[] position = {(float) camera.x(), (float) camera.y(), (float) camera.z()};
            long cameraInfo = FfxStructs.cameraInfo(stack, position, axes[0], axes[1], axes[2]);
            long depth = FfxStructs.resource(stack, f.depth(), FfxStructs.RESOURCE_TYPE_TEXTURE2D, FfxStructs.FORMAT_R32_FLOAT,
                    f.renderWidth(), f.renderHeight(), 1, 1, 0, FfxStructs.RESOURCE_USAGE_READ_ONLY, FfxStructs.RESOURCE_STATE_PIXEL_COMPUTE_READ);
            long motion = FfxStructs.resource(stack, f.motion(), FfxStructs.RESOURCE_TYPE_TEXTURE2D, FfxStructs.FORMAT_R16G16_FLOAT,
                    f.renderWidth(), f.renderHeight(), 1, 1, 0, FfxStructs.RESOURCE_USAGE_READ_ONLY, FfxStructs.RESOURCE_STATE_PIXEL_COMPUTE_READ);
            //inverted infinite depth (FG_DEPTH_INVERTED | FG_DEPTH_INFINITE): as the SDK sample, cameraNear = FLT_MAX and cameraFar = the
            // near plane; FSR takes the smaller of the two as the near plane either way
            float near = FsrCapture.nearPlane(camera.projection());
            long prepare = FfxStructs.prepare(stack, cameraInfo, id, flags, cmd, f.renderWidth(), f.renderHeight(),
                    f.renderWidth(), f.renderHeight(), frameTimeMs, INFINITE_NEAR, near, fovY(camera.projection()), 1f, depth, motion);
            FfxApi.check(api.dispatch(f.frameGenerationContext(), prepare), "ffxDispatch (frame generation prepare)");
        }
    }
}
