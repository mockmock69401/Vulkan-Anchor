package net.vulkanmod.visuals.fsr;

import net.minecraft.client.Minecraft;
import net.vulkanmod.visuals.Visuals;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.framegen.FrameGen;
import net.vulkanmod.visuals.framegen.FrameGenTargets;
import net.vulkanmod.visuals.render.ReforgedProjection;
import net.vulkanmod.visuals.render.TaaJitter;
import net.vulkanmod.visuals.render.TaaMath;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.ImageViewCache;
import net.vulkanmod.visuals.vk.VkBarriers;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Objects;

import static org.lwjgl.vulkan.VK10.*;

/**
 * FSR design 4.5 steps 1, 2 and 5 on the render thread: before the hand (GameRendererFrameGenMixin, the hook Optical Flow's capture
 * uses), the world without hand, HUD or GUI is copied as FSR's HUD-less colour, FSR's depth and motion vectors are recorded
 * (FsrInputs), and the frame's pause reason and camera-cut reset are decided. The frame then finishes as usual (hand, GUI, vignette,
 * crosshair over the world) and FSR finds the UI by comparing the two images.
 *
 * <p>Runs only while FSR is the active frame generation (FsrSettings.active) and its swapchain is live ({@link #setLive}, which the
 * FSR swapchain sets once it exists); otherwise every hook returns at once. The capture runs on every such frame with a world,
 * paused or not, so the HUD-less colour, depth and motion are always this frame's; the pause reason only decides whether FSR
 * generates from it ({@link #generates}).
 *
 * <p>Resources: one HUD-less image per render frame in flight (Renderer.getCurrentFrame), made on first use and replaced when the
 * display size or the frame count changes; FsrInputs at the render size. {@link #free} releases them. Render thread only.
 */
public final class FsrCapture {
    /** The catalog name of fsr_hudless.comp (VisualsPrograms). */
    static final String PROGRAM = "fsrHudless";
    /** fsr_hudless.comp's bindings. */
    static final int HUD_LESS_OUT = 0, MAIN_COLOUR = 1;
    /**
     * The HUD-less image's format: RGBA8 UNORM, the main colour's (Framebuffer.DEFAULT_FORMAT, where the world is drawn at the render
     * scale) and a storage format every device supports. The swapchain is RGBA8 or BGRA8 UNORM (SwapChain.getFormat): the values
     * are the same, so with a BGRA8 swapchain FSR is told this format through ffxCreateContextDescFrameGenerationHudless.
     */
    public static final int HUD_LESS_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    /** The layout the HUD-less image is left in after the copy, visible to compute shaders: FFX_API_RESOURCE_STATE_COMPUTE_READ. */
    public static final int HUD_LESS_LAYOUT = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    /** Written by the capture, sampled by FSR and copied by FSR's generation in its own command list (TRANSFER_SRC). */
    static final int HUD_LESS_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    /** {@link #pauseReason} of a frame whose world was not captured (no world drawn, FSR not running, the capture skipped). */
    public static final String NOT_CAPTURED = "not captured";
    /** Ruling R3: a camera move of more than this many blocks between captured frames is a cut (a teleport). */
    static final double CUT_DISTANCE = 16;
    /** Matches the shader's local_size (8, 8). */
    private static final int GROUP = 8;

    /** The camera as the reset compares it between captured frames: position, dimension and camera type (F5). */
    record CameraState(double x, double y, double z, Object dimension, Object cameraType) {
    }

    /** Set by the FSR swapchain (Task 8): it exists and presents. */
    private static boolean live;

    //This frame's camera (onCamera), and the last captured frame's camera and camera state
    private static TaaMath.Camera camera, previousCamera;
    private static long cameraSerial = -1;
    private static CameraState previousState;

    //The frame beforeHand ran in, and what it decided
    private static long handledSerial = -1, capturedSerial = -1;
    private static String reason = NOT_CAPTURED;
    private static boolean reset;
    /** The HUD-less image the last capture wrote (ruling R9: the frame's own image, whatever frame index is current later). */
    private static long capturedHudLess;

    //Resources, made on first use
    private static DeferredFree deferred;
    private static ImageViewCache mainDepthViews;
    private static FsrInputs inputs;
    private static VkComputeProgram copy;
    private static long nearest, linear;
    private static VkImage2D[] hudLess;

    private FsrCapture() {
    }

    // ---- interface for the FSR swapchain and dispatch (Tasks 8, 9) ----

    /**
     * The FSR swapchain exists (true) or is gone (false). Turning it off forgets the last captured camera, so the first frame after
     * it comes back resets FSR. Render thread.
     */
    public static void setLive(boolean on) {
        live = on;
        if (!on) {
            previousCamera = null;
            previousState = null;
        }
    }

    public static boolean live() {
        return live;
    }

    /** beforeHand captured this frame: the HUD-less image, depth and motion hold this frame. */
    public static boolean capturedThisFrame() {
        return capturedSerial == VkFrames.serial();
    }

    /**
     * This frame's pause reason: one of PauseRules' (Optical Flow's rules, FrameGen.pauseReason), null when FSR may generate, or
     * {@link #NOT_CAPTURED} when beforeHand did not capture this frame.
     */
    public static String pauseReason() {
        return capturedThisFrame() ? reason : NOT_CAPTURED;
    }

    /** Whether FSR generates on a frame with this pause reason: only with none. */
    public static boolean generates(String reason) {
        return reason == null;
    }

    /** This frame is a camera cut (ruling R3, {@link #resetOn}): FSR's reset flag. False when this frame was not captured. */
    public static boolean resetThisFrame() {
        return capturedThisFrame() && reset;
    }

    /**
     * This frame's HUD-less colour: the VkImage handle the capture wrote this frame, {@link #HUD_LESS_FORMAT}, display size
     * ({@link #hudLessWidth} x {@link #hudLessHeight}), left in {@link #HUD_LESS_LAYOUT}. 0 when this frame was not captured.
     */
    public static long hudLessImage() {
        return capturedThisFrame() ? capturedHudLess : 0;
    }

    public static int hudLessWidth() {
        return hudLess == null ? 0 : hudLess[0].width;
    }

    public static int hudLessHeight() {
        return hudLess == null ? 0 : hudLess[0].height;
    }

    /** FSR's depth and motion vectors (FsrInputs' images and layouts), written this frame when {@link #capturedThisFrame}; null before the first capture. */
    public static FsrInputs inputs() {
        return inputs;
    }

    /** This frame's camera (unjittered projection in 0..1 depth, model view, position), or null before WorldRenderer reported it. */
    public static TaaMath.Camera camera() {
        return cameraSerial == VkFrames.serial() ? camera : null;
    }

    // ---- frame ----

    /** FSR is the active frame generation and its swapchain is live. */
    private static boolean running() {
        if (!live) return false;
        VisualsConfig c = VisualsConfig.get();
        return FsrSettings.active(c.frameGeneration, c.fsrFrameGeneration, FsrSupport.current()) == FsrSettings.Active.FSR;
    }

    /**
     * WorldRenderer, next to FrameGen.onCamera (after the cutout terrain): this frame's camera, the projection unjittered (TaaJitter)
     * and in Reforged's 0..1 depth, as Optical Flow keeps it. The first call of a frame counts.
     */
    public static void onCamera(Matrix4fc projection, Matrix4fc modelView, double camX, double camY, double camZ) {
        if (!running() || cameraSerial == VkFrames.serial()) return;
        float[] jitter = TaaJitter.appliedNdc();
        camera = new TaaMath.Camera(TaaMath.unjitter(ReforgedProjection.vulkanDepth(projection), jitter[0], jitter[1]), new Matrix4f(modelView),
                camX, camY, camZ);
        cameraSerial = VkFrames.serial();
    }

    /**
     * GameRenderer.renderLevel, before the hand's depth clear (or at its end when no hand is drawn), next to FrameGen.beforeHand; once
     * per frame. Decides the pause reason (Optical Flow's rules) and the reset, then, outside the main pass, copies the main colour to
     * this frame's HUD-less image and records FsrInputs, and resumes the main pass for the hand and the GUI.
     */
    public static void beforeHand() {
        long serial = VkFrames.serial();
        if (!running() || handledSerial == serial) return;
        handledSerial = serial;
        if (deferred != null) deferred.tick(VkFrames.completedSerial());
        TaaMath.Camera now = camera();
        if (!VkContext.recordingFrame() || now == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            capture(mc, now, serial);
        } catch (RuntimeException | LinkageError e) {
            //FSR becomes unavailable (FsrSupport's FAILED): running() is false from now on
            FsrSupport.markFailed("the HUD-less capture failed", e);
            VkContext.resumeMainPass();
        }
    }

    private static void capture(Minecraft mc, TaaMath.Camera now, long serial) {
        //binds the main target when a mod left another framebuffer bound (as the post-process does)
        VkContext.resumeMainPass();
        VkContext.MainTarget main = VkContext.captureMainTarget();
        SwapChain sc = Vulkan.getSwapChain();
        if (main == null || main.width() <= 0 || main.height() <= 0 || sc.getWidth() <= 0 || sc.getHeight() <= 0) return;
        //2026-10-28 (Deceased: the frame joining the world had no perspective yet, "near plane NaN" turned FSR off): skip such a frame
        float near = nearPlane(now.projection());
        if (!usableNear(near)) return;
        createResources();
        ensureHudLess(sc.getWidth(), sc.getHeight(), VkContext.framesInFlight());
        inputs.ensure(main.width(), main.height());

        CameraState state = new CameraState(now.x(), now.y(), now.z(), mc.level.dimension(), mc.options.getCameraType());
        boolean cut = resetOn(previousState, state);
        String why = FrameGen.pauseReason(mc);
        //after a cut the previous camera is not this picture's past: no motion (the frame reprojects onto itself)
        TaaMath.Camera past = cut || previousCamera == null ? now : previousCamera;
        Matrix4f invModelView = new Matrix4f(now.modelView()).invert();
        Matrix4f prevFromView = TaaMath.targetFromView(past, invModelView, now.x(), now.y(), now.z());
        Matrix4f invProj = new Matrix4f(now.projection()).invert();
        Visuals.FrameGenLod lod = Visuals.frameGenLod();
        float[] jitter = TaaJitter.appliedNdc();
        Matrix4f invLodProj = lod != null ? TaaMath.unjitterInverse(lod.invProjection(), jitter[0], jitter[1]) : invProj;
        VkImage2D target = hudLess[Renderer.getCurrentFrame()];
        boolean scaled = main.width() != target.width || main.height() != target.height;

        VkCommandBuffer cmd = VkContext.frameCommands();
        VkContext.endMainPass();
        try {
            VkContext.mainColourToShaderRead(cmd, main);
            VkContext.mainDepthToShaderRead(cmd, main);
            VkBarriers.mainColourForCompute(cmd);
            //global: also covers the LOD depth, last made readable for fragment shaders
            VkBarriers.mainDepthForCompute(cmd);
            long colourView = VkContext.mainColourView(main);
            long mainDepth = mainDepthViews.get(main.identity(), deferred, serial);
            VkGpuTimer.mark(cmd, "fsr.capture");
            //rewritten whole: discard (an earlier frame on this frame index read it, finished before its fence was waited)
            target.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT);
            copy.dispatchWithPush(cmd, new VkBindings().storageImage(HUD_LESS_OUT, target.view()).sampledView(MAIN_COLOUR, colourView, scaled ? linear : nearest),
                    null, groups(target.width), groups(target.height), 1);
            target.transition(cmd, HUD_LESS_LAYOUT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT);
            inputs.record(cmd, colourView, mainDepth, lod != null ? lod.depthView() : 0, lod != null, prevFromView, invProj, invLodProj, near);
        } finally {
            //the transitions back to attachments (here and at the pass's begin) wait for fragment shaders only
            VkBarriers.mainColourAfterCompute(cmd);
            VkBarriers.mainDepthAfterCompute(cmd);
            VkContext.mainColourToAttachment(cmd, main);
            VkContext.resumeMainPass();
            VkContext.invalidatePipelineCache();
        }
        reason = why;
        reset = cut;
        capturedHudLess = target.handle();
        previousCamera = now;
        previousState = state;
        capturedSerial = serial;
    }

    /**
     * Ruling R3: FSR resets on a camera cut. True with no previous captured frame, a camera move of more than {@link #CUT_DISTANCE}
     * blocks, another dimension, or another camera type (F5); false otherwise. Pure.
     */
    static boolean resetOn(CameraState previous, CameraState now) {
        if (previous == null) return true;
        double dx = now.x() - previous.x(), dy = now.y() - previous.y(), dz = now.z() - previous.z();
        if (dx * dx + dy * dy + dz * dz > CUT_DISTANCE * CUT_DISTANCE) return true;
        return !Objects.equals(previous.dimension(), now.dimension()) || !Objects.equals(previous.cameraType(), now.cameraType());
    }

    /** The near plane in blocks of a perspective projection in 0..1 depth (depth 0 at view z = -near): FSR's cameraNear. */
    static float nearPlane(Matrix4fc projection) {
        return projection.m32() / projection.m22();
    }

    /** Whether a near plane (nearPlane's) is one FSR can take: positive and finite. Pure. */
    static boolean usableNear(float near) {
        return near > 0 && Float.isFinite(near);
    }

    private static int groups(int pixels) {
        return (pixels + GROUP - 1) / GROUP;
    }

    // ---- resources ----

    private static void createResources() {
        if (inputs != null) return;
        DeferredFree d = new DeferredFree();
        VkComputeProgram program = VkComputeProgram.of(PROGRAM);
        try {
            inputs = new FsrInputs(d);
        } catch (RuntimeException | LinkageError e) {
            program.free();
            throw e;
        }
        deferred = d;
        copy = program;
        mainDepthViews = new ImageViewCache(VkContext::createMainDepthView, VkImage2D::destroyView);
        nearest = VkSamplers.nearestClamp();
        linear = VkSamplers.linearClamp();
    }

    /** One HUD-less image per render frame in flight at width x height; the old ones are retired through DeferredFree. */
    private static void ensureHudLess(int width, int height, int frames) {
        if (frames <= 0 || frames > FrameGenTargets.MAX_FRAMES_IN_FLIGHT) {
            throw new IllegalStateException(frames + " render frames in flight (FSR supports " + FrameGenTargets.MAX_FRAMES_IN_FLIGHT + ")");
        }
        if (hudLess != null && hudLess.length == frames && hudLess[0].width == width && hudLess[0].height == height) return;
        VkImage2D[] made = new VkImage2D[frames];
        try {
            for (int i = 0; i < frames; i++) {
                made[i] = VkImage2D.create(HUD_LESS_FORMAT, width, height, 1, HUD_LESS_USAGE);
            }
        } catch (RuntimeException e) {
            for (VkImage2D i : made) if (i != null) i.free();//never used: freed at once
            throw e;
        }
        retireHudLess();
        hudLess = made;
    }

    private static void retireHudLess() {
        VkImage2D[] old = hudLess;
        hudLess = null;
        if (old != null) deferred.retire(() -> {
            for (VkImage2D i : old) i.free();
        }, VkFrames.serial());
    }

    /**
     * Frees everything (the FSR swapchain is torn down, the game closes): the device must be idle, or at least done with every frame
     * that used these resources. Render thread.
     */
    public static void free() {
        if (inputs == null) return;
        retireHudLess();
        inputs.free();
        copy.free();
        mainDepthViews.free();
        VkSamplers.free(nearest);
        VkSamplers.free(linear);
        deferred.freeAll();
        inputs = null;
        copy = null;
        mainDepthViews = null;
        deferred = null;
        nearest = linear = 0;
        previousCamera = null;
        previousState = null;
        capturedSerial = -1;
        capturedHudLess = 0;
    }
}
