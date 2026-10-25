package net.vulkanmod.visuals.framegen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.RenderScale;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.visuals.Visuals;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.fsr.FsrSettings;
import net.vulkanmod.visuals.fsr.FsrSupport;
import net.vulkanmod.visuals.framegen.CameraPrediction.CameraSample;
import net.vulkanmod.visuals.render.ReforgedProjection;
import net.vulkanmod.visuals.render.TaaJitter;
import net.vulkanmod.visuals.render.TaaMath;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.ImageViewCache;
import net.vulkanmod.visuals.vk.VkBarriers;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFeaturePlan;
import net.vulkanmod.visuals.vk.VkFeatures;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

/**
 * Frame generation's orchestration on the render thread (FG design 3.1, 3.5, 4, 7). The core renderer asks it whether this frame is
 * paced (the pacer thread owns acquire and present), hands it each submitted frame, and stops and restarts the pacer around a swapchain
 * recreation. The paced state changes only there (and in beginFrame, before the frame latches it), so it never changes inside a frame.
 *
 * A paced frame: Reforged's frame reports the camera (onCamera); before the hand's depth clear (GameRendererFrameGenMixin) the main
 * colour and depth are captured into the slot's world image and the hand and GUI move to the slot's UI layer, unless a pause rule
 * (PauseRules) other than "forced" applies; the GUI's vignette and crosshair are taken out of the layer (GuiFrameGenMixin) and applied
 * by the compose pass; at the end of the main pass the slot's parameter block is completed (or, without the UI layer, the world
 * framebuffer with its GUI is captured whole); after the submit the frame is handed to the pacer. F2 while paced is deferred by one
 * frame (deferScreenshot), which pauses with "screenshot" so the grab sees the hand and GUI. With the setting off every hook returns at
 * once and the core paths run as before.
 *
 * Render thread only, except where a method says otherwise.
 */
public final class FrameGen {
    private static final Matrix4fc IDENTITY = new Matrix4f();
    private static final long STATS_WINDOW_NS = 1_000_000_000L;

    /** Vanilla's vignette and GUI icons (the crosshair) for the compose pass; built on first use, so the class loads without the game. */
    private static final class Textures {
        static final ResourceLocation VIGNETTE = new ResourceLocation("textures/misc/vignette.png");
        static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
    }

    /** Latched at swapchain recreation: the pacer runs and frames are recorded for it. */
    private static boolean paced;
    /** The setting the last swapchain recreation applied; beginFrame schedules a recreation when the config differs (FG design 7). */
    private static boolean appliedSetting;
    /** Setting up failed, or the pacer died: frame generation stays off until the game restarts. */
    private static boolean broken;

    /** The resources, created when frame generation is first wanted and freed when it is turned off. */
    static FrameGenTargets targets;
    static VkWarp warp;
    static FramePacer pacer;
    /** Phase 1 presents 2 frames per real frame; VisualsConfig.frameGenerationFactor is read from phase 3 on. */
    static int factor = 2;
    private static DeferredFree deferred;
    private static ImageViewCache mainDepthViews;
    /** The textures the compose sets were built with: the image object and its view (a handle can be reused after a reload). */
    private static VulkanImage vignetteImage, iconsImage;
    private static long vignetteView, iconsView;

    /** The capture slot this frame records into; the next frame uses the other one. */
    static int slot;
    /** This frame's unjittered camera and camera sample (null when none), its input time and whether it may be generated from. */
    static TaaMath.Camera camera;
    static CameraSample sample;
    static long inputNanos;
    static boolean generate;

    //This frame: beforeHand ran, the UI layer is bound, the slot's world image was written
    private static boolean handled, uiLayer, captured;
    /** The pause reason of the last paced frame (null: generating), for F3. */
    private static String reason;
    //The slot's parameter block as this frame wrote it (FrameGenParams.write)
    private static final Matrix4f invRealProj = new Matrix4f(), invLodProj = new Matrix4f();
    private static boolean lodValid, depthValid;
    private static final float[] vignetteRgb = new float[3];
    private static boolean vignetteOn;
    private static final float[] quads = new float[FrameGenParams.MAX_QUADS * 8];
    private static int quadCount;

    /** Unhandled UI blend states already logged (by state, then by name: each name once). */
    private static final Set<Integer> seenBlends = new HashSet<>();
    private static final Set<String> loggedBlends = new HashSet<>();

    //F3: the present log over the last second, and T-hat from the real presents
    private static final FramePacing intervals = new FramePacing(2);
    private static long windowStart;
    private static int realCount, shownCount, droppedCount;
    private static double realFps, shownFps;
    private static int dropped;

    /** F2 while paced (final review I4): the grabs waiting for a frame with the hand and GUI in the world framebuffer. */
    private record Grab(File dir, String name, RenderTarget target, Consumer<Component> messages) {}
    private static final List<Grab> grabs = new ArrayList<>();
    /** This frame pauses for the waiting grabs ("screenshot"), which run after its endFrame; latched in beginFrame. */
    private static boolean screenshotFrame;
    /** The waiting grabs are running: Screenshot.grab is not deferred again. */
    private static boolean grabbing;

    //Task 10: the pilot's burst request (taken in beginFrame) and the burst the pacer runs
    private static String burstName;
    private static int burstCount;
    private static FrameBurst activeBurst;

    private FrameGen() {
    }

    /** Whether this frame is recorded for the pacer: no acquire, no present, the world offscreen. */
    public static boolean paced() {
        return paced;
    }

    /** The setting is on, the device has timeline semaphores, and neither the set-up nor the pacer has failed. */
    static boolean wanted() {
        VisualsConfig c = VisualsConfig.get();
        if (broken || FsrSettings.active(c.frameGeneration, c.fsrFrameGeneration, FsrSupport.current()) != FsrSettings.Active.OPTICAL_FLOW
                || !supported()) return false;
        return pacer == null || !pacer.failed();
    }

    private static boolean supported() {
        VkFeaturePlan plan = VkFeatures.plan();
        return plan != null && plan.frameGenerationSupported();
    }

    public static int slot() {
        return slot;
    }

    public static FrameGenTargets targets() {
        return targets;
    }

    public static FramePacer pacer() {
        return pacer;
    }

    // ---- frame ----

    /**
     * Renderer.beginFrame, first thing (before a pending swapchain recreation and before the frame latches paced()): a changed setting
     * schedules a recreation, which applies it (FG design 7). While paced: retires what a restart replaced, reads the present log for
     * F3, and restarts the pacer when the world size changed (a screen opened or closed with the render scale below 100: the targets
     * are sized at start) or the vignette or icons texture was replaced (a resource reload; this runs before MemoryManager frees the
     * old images, so the pacer stops reading them first).
     */
    public static void beginFrame() {
        boolean setting = VisualsConfig.get().frameGeneration;
        if (setting != appliedSetting) Renderer.scheduleSwapChainUpdate();
        if (deferred != null) deferred.tick(VkFrames.completedSerial());
        //a grab deferred before this frame pauses it; one made inside a frame waits for the next
        screenshotFrame = !grabs.isEmpty();
        if (!paced) return;
        drainLog();
        SwapChain sc = Vulkan.getSwapChain();
        Minecraft mc = Minecraft.getInstance();
        int[] world = FrameGenTargets.worldSize(sc.getWidth(), sc.getHeight(), RenderScale.clamp(Initializer.CONFIG.renderScale),
                mc.level != null && mc.screen == null);
        boolean resized = !targets.ready() || world[0] != targets.worldWidth() || world[1] != targets.worldHeight();
        if (resized || texturesChanged()) restartPacer();
        else if (burstName != null) startBurst();
        else if (activeBurst != null && activeBurst.done()) finishBurst();
    }

    /** Stops the pacer and starts it on the current state; when that fails the recreation that follows retries it cleanly. */
    private static void restartPacer() {
        pacer.stop();
        dropBurst();
        paced = startPacer();
        resetFrame();
        //beginFrame runs before Renderer's pending-recreation check, so this recreation happens before the frame records
        if (!paced) Renderer.scheduleSwapChainUpdate();
    }

    /**
     * WorldRenderer, next to Visuals.frame (after the cutout terrain): this frame's camera, the projection unjittered (TaaJitter) and in
     * Reforged's 0..1 depth, and the camera sample for the prediction (FG design 3.3). The first call of a frame counts.
     */
    public static void onCamera(Matrix4fc projection, Matrix4fc modelView, double camX, double camY, double camZ) {
        if (!paced || camera != null) return;
        float[] jitter = TaaJitter.appliedNdc();
        camera = new TaaMath.Camera(TaaMath.unjitter(ReforgedProjection.vulkanDepth(projection), jitter[0], jitter[1]), new Matrix4f(modelView),
                camX, camY, camZ);
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.getCameraEntity();
        if (e == null) return;
        Camera c = mc.gameRenderer.getMainCamera();
        float eye = e.getEyeHeight();
        sample = new CameraSample(System.nanoTime(), mc.getFrameTime(), e.xo, e.yo + eye, e.zo, e.getX(), e.getY() + eye, e.getZ(),
                camX, camY, camZ, c.getYRot(), c.getXRot());
    }

    /**
     * When this frame's mouse input was sampled (MouseHandlerLatencyMixin, only while FrameRecorder is on): it travels with the frame to
     * the present log (RealFrame.inputNanos); a frame that is not paced takes it in {@link #directPresented}.
     */
    public static void inputSampled(long nanos) {
        if (paced || FrameRecorder.ON) inputNanos = nanos;
    }

    /** Renderer.submitFrame, the direct (not paced) path, after the present call at callNs: its record for the recorder, with the input stamp. */
    public static void directPresented(long callNs) {
        long input = inputNanos;
        inputNanos = 0;
        FrameRecorder.present(new long[]{PresentLog.KIND_REAL, 0, 0, callNs, 0, FramePacing.Decision.PRESENT.ordinal(), input, 0});
    }

    /** The present log up to now to the recorder (the pilot's frames stop calls this before it writes the file). Render thread. */
    public static void flushPresentLog() {
        if (paced && pacer != null) pacer.log().drain(FrameGen::count);
    }

    /**
     * The pilot's fgburst: the next count presents are captured to images (FrameBurst). Taken at the next beginFrame, which restarts the
     * pacer with the burst armed. Ignored with a warning while the pacer is not running.
     */
    public static void requestBurst(String name, int count) {
        if (!paced) {
            VisualsLog.warn("Frame generation burst " + name + " ignored: frame generation is not running");
            return;
        }
        burstName = name;
        burstCount = count;
    }

    /** Stops the pacer and restarts it with a burst armed (the burst's image, buffers and framebuffer are made while it is stopped). */
    private static void startBurst() {
        String name = burstName;
        int count = burstCount;
        burstName = null;
        pacer.stop();
        dropBurst();
        SwapChain sc = Vulkan.getSwapChain();
        RenderPass present = Renderer.getInstance().getMainPass().presentRenderPass();
        try {
            if (present != null) {
                Path dir = Minecraft.getInstance().gameDirectory.toPath();
                activeBurst = new FrameBurst(name, count, dir, sc, present.getId(), pacer.pacerTimeline(), VisualsConfig.fgView == 1);
                pacer.arm(activeBurst);
                VisualsLog.info("Frame generation burst " + name + ": capturing " + count + " presents");
            }
        } catch (RuntimeException e) {
            VisualsLog.warn("Frame generation burst " + name + " could not be set up", e);
            activeBurst = null;
        }
        paced = startPacer();
        resetFrame();
        if (!paced) Renderer.scheduleSwapChainUpdate();
    }

    /** Every file of the finished burst is written: logs it and frees it (the pacer let go of it when it claimed the last present). */
    private static void finishBurst() {
        activeBurst.report();
        activeBurst.free();
        activeBurst = null;
    }

    /** The pacer is stopped (and the GPU done with its work): ends the burst in progress, if any. */
    private static void dropBurst() {
        if (pacer != null) pacer.arm(null);
        if (activeBurst == null) return;
        activeBurst.abort();
        activeBurst.report();
        activeBurst.free();
        activeBurst = null;
    }

    /** The pause reason now (FG design 3.5); null when generation may run. Public for FSR's capture (FsrCapture), which pauses on the same rules. */
    public static String pauseReason(Minecraft mc) {
        boolean level = mc.level != null, screen = mc.screen != null, overlay = mc.getOverlay() != null, paused = mc.isPaused();
        boolean post = mc.gameRenderer.currentEffect() != null;
        //vanilla's shouldShowEntityOutlines() holds whenever the outline chain exists; the chain is drawn when an entity glows
        boolean outlines = level && !screen && !overlay && !paused && !post && outlinesDrawn(mc);
        //F3's charts or Alt+F3's FPS chart (FG design 3.5 "F3 debug graphs")
        boolean charts = mc.options.renderDebugCharts || mc.options.renderFpsChart;
        return PauseRules.reason(level, screen, overlay, screenshotFrame, paused, post, outlines, charts, mc.getCameraEntity() != mc.player,
                supported(), VisualsConfig.fgForcePause);
    }

    /** LevelRenderer draws and processes the entity outline chain this frame: the chain exists and an entity appears glowing. */
    private static boolean outlinesDrawn(Minecraft mc) {
        if (mc.levelRenderer.entityTarget() == null || mc.player == null) return false;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (mc.shouldEntityAppearGlowing(e)) return true;
        }
        return false;
    }

    /**
     * GameRenderer.renderLevel, before the hand's depth clear (or at its end when no hand is drawn); once per frame. When the frame
     * keeps the UI layer (no pause reason, or "forced"): the main colour and depth (and this frame's LOD depth) become readable, the
     * slot's parameter block is written, the capture writes the slot's world image, and the hand and GUI move to the slot's UI layer
     * (always after a capture: GUI drawn after it would otherwise be in neither image). Otherwise nothing here: the GUI draws into the
     * world framebuffer and endFrame captures that.
     */
    public static void beforeHand() {
        if (!paced || handled) return;
        handled = true;
        if (!VkContext.recordingFrame() || targets == null || !targets.ready()) return;
        reason = pauseReason(Minecraft.getInstance());
        if (!PauseRules.keepsUiLayer(reason) || camera == null) return;
        try {
            capture();
        } catch (RuntimeException e) {
            if (!uiLayer) VkContext.resumeMainPass();
            fail("Frame generation capture failed", e);
        }
    }

    private static void capture() {
        //binds the main target when a mod left another framebuffer bound (as the post-process does)
        VkContext.resumeMainPass();
        VkContext.MainTarget main = VkContext.captureMainTarget();
        //the size can change inside a frame (a screen opened); the frame is then not handed over and the next beginFrame restarts
        if (main == null || main.width() != targets.worldWidth() || main.height() != targets.worldHeight()) return;
        VkCommandBuffer cmd = VkContext.frameCommands();
        long serial = VkFrames.serial();
        Visuals.FrameGenLod lod = Visuals.frameGenLod();
        float[] jitter = TaaJitter.appliedNdc();
        invRealProj.set(camera.projection()).invert();
        lodValid = lod != null;
        if (lodValid) invLodProj.set(TaaMath.unjitterInverse(lod.invProjection(), jitter[0], jitter[1]));
        else invLodProj.set(invRealProj);
        depthValid = true;
        int params = writeCaptureParams();
        //complete already (endFrame adds the GUI's fields): the pacer copies this block when it takes the frame
        writeSlotParams(false);

        VkContext.endMainPass();
        try {
            VkContext.mainColourToShaderRead(cmd, main);
            VkContext.mainDepthToShaderRead(cmd, main);
            VkBarriers.mainColourForCompute(cmd);
            //global: also covers the LOD depth, last made readable for fragment shaders
            VkBarriers.mainDepthForCompute(cmd);
            long mainDepth = mainDepthViews.get(main.identity(), deferred, serial);
            VkGpuTimer.mark(cmd, "fg.capture");
            warp.capture(cmd, slot, params, VkContext.mainColourView(main), mainDepth, lodValid ? lod.depthView() : mainDepth, true);
            //the next frame's transitions back to attachments wait for fragment shaders only
            VkBarriers.mainColourAfterCompute(cmd);
            VkBarriers.mainDepthAfterCompute(cmd);
        } finally {
            VkContext.invalidatePipelineCache();
        }
        captured = true;
        generate = reason == null;
        Renderer.getInstance().getMainPass().beginUiLayer(slot);
        uiLayer = true;
    }

    /** The UI layer is bound this frame: the GUI's vignette and crosshair are recorded for the compose pass instead of drawn. */
    public static boolean uiLayerActive() {
        return paced && uiLayer;
    }

    /** Gui.renderVignette under the UI layer: vanilla's shader colour (brightness, or the world border's tint) for the compose pass. */
    public static void vignette(float r, float g, float b) {
        if (!uiLayerActive()) return;
        vignetteRgb[0] = r;
        vignetteRgb[1] = g;
        vignetteRgb[2] = b;
        vignetteOn = true;
    }

    /**
     * Gui.renderCrosshair under the UI layer: one blit of the icons texture (GUI units under the GUI pose, the uv in texels of a texW x
     * texH texture), drawn inverted by the compose pass in swapchain pixels. Quads past FrameGenParams.MAX_QUADS are dropped.
     */
    public static void crosshairQuad(Matrix4fc pose, int x, int y, int w, int h, int u, int v, int texW, int texH) {
        if (!uiLayerActive()) return;
        if (putQuad(quads, quadCount, pose, x, y, w, h, u, v, texW, texH, Minecraft.getInstance().getWindow().getGuiScale())) quadCount++;
    }

    /**
     * Writes quad index as {x, y, w, h} in swapchain pixels and {u, v, w, h} normalised; false (nothing written) when out of room or
     * empty. The corners go through pose (the GuiGraphics pose: a HUD mod may translate or scale the crosshair) and the rect is their
     * bounds, then times the GUI scale.
     */
    static boolean putQuad(float[] quads, int index, Matrix4fc pose, int x, int y, int w, int h, int u, int v, int texW, int texH,
                           double guiScale) {
        if (index < 0 || index >= FrameGenParams.MAX_QUADS || w <= 0 || h <= 0 || texW <= 0 || texH <= 0) return false;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        Vector3f corner = new Vector3f();
        for (int c = 0; c < 4; c++) {
            pose.transformPosition(x + ((c & 1) == 0 ? 0 : w), y + ((c & 2) == 0 ? 0 : h), 0, corner);
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
        }
        if (maxX <= minX || maxY <= minY) return false;
        float s = (float) guiScale;
        int at = index * 8;
        quads[at] = minX * s;
        quads[at + 1] = minY * s;
        quads[at + 2] = (maxX - minX) * s;
        quads[at + 3] = (maxY - minY) * s;
        quads[at + 4] = (float) u / texW;
        quads[at + 5] = (float) v / texH;
        quads[at + 6] = (float) w / texW;
        quads[at + 7] = (float) h / texH;
        return true;
    }

    /**
     * DefaultMainPass.end on a paced frame, after the last render pass ended: the compose textures become readable, and the slot's
     * parameter block is completed with what only the GUI knew (UI validity, vignette, crosshair). A frame without the UI layer (menus,
     * any pause reason but "forced") captures worldColour, the world framebuffer with its GUI, with no depth (FG design 3.5).
     */
    public static void endFrame(VkCommandBuffer cmd, VulkanImage worldColour) {
        if (!paced || targets == null || !targets.ready()) return;
        try {
            texturesReadable(cmd);
            if (uiLayer) {
                writeSlotParams(true);
                return;
            }
            if (!handled) reason = pauseReason(Minecraft.getInstance());
            if (worldColour == null || worldColour.width != targets.worldWidth() || worldColour.height != targets.worldHeight()) return;
            invRealProj.identity();
            invLodProj.identity();
            lodValid = false;
            depthValid = false;
            vignetteOn = false;
            quadCount = 0;
            int params = writeCaptureParams();
            writeSlotParams(false);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                worldColour.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            VkBarriers.mainColourForCompute(cmd);
            warp.capture(cmd, slot, params, worldColour.getImageView(), 0, 0, false);
            VkBarriers.mainColourAfterCompute(cmd);
            VkContext.invalidatePipelineCache();
            captured = true;
            generate = false;
        } catch (RuntimeException e) {
            fail("Frame generation end of frame failed", e);
        }
    }

    /**
     * The capture-time fields into this render frame's capture entry, which the capture dispatch reads on the GPU (FrameGenTargets: the
     * entry is free once beginFrame waited for this frame index's previous frame); returns its index.
     */
    private static int writeCaptureParams() {
        int index = FrameGenTargets.captureFrameParams(Renderer.getCurrentFrame());
        writeParams(index, false);
        return index;
    }

    /** The slot's complete block, the pacer's CPU-side copy (FramePacer.take); written before handOff, never read on the GPU. */
    private static void writeSlotParams(boolean uiValid) {
        writeParams(FrameGenTargets.captureParams(slot), uiValid);
    }

    private static void writeParams(int index, boolean uiValid) {
        ByteBuffer dst = targets.paramsMapped(index);
        dst.position(0);
        FrameGenParams.write(dst, invRealProj, invLodProj, IDENTITY, IDENTITY, invRealProj, targets.worldWidth(), targets.worldHeight(),
                targets.swapWidth(), targets.swapHeight(), lodValid, depthValid, VisualsConfig.fgView, uiValid, false, vignetteRgb,
                vignetteOn, quads, quadCount);
        dst.position(0);
    }

    /**
     * After a paced frame's submit: hands the frame (render value, slot, camera) to the pacer and moves to the other slot. A frame whose
     * world image was not written is not handed and keeps its slot: the pacer goes on showing the last frame, and the slot it reads is
     * not the one the next frame writes.
     */
    public static void handOff() {
        if (!paced) return;
        if (captured) {
            pacer.hand(new RealFrame(pacer.renderValue(), slot, camera, sample, System.nanoTime(), inputNanos,
                    generate && camera != null && sample != null));
            slot = (slot + 1) % FrameGenTargets.SLOTS;
        }
        resetFrame();
    }

    private static void resetFrame() {
        camera = null;
        sample = null;
        inputNanos = 0;
        generate = false;
        handled = false;
        uiLayer = false;
        captured = false;
        vignetteOn = false;
        quadCount = 0;
    }

    /**
     * Screenshot.grab's entry (ScreenshotFrameGenMixin; F2 runs it between frames, after endFrame): while paced the world framebuffer
     * holds no hand or GUI (they are in the UI layer), so the grab is stored and true is returned (the call is cancelled). The next frame
     * pauses with "screenshot" and draws them into the world framebuffer with vanilla's vignette and crosshair, and {@link #afterEndFrame}
     * runs the grab after it (final review I4). False (the grab runs now): not paced, or the stored grabs are running.
     */
    public static boolean deferScreenshot(File dir, String name, RenderTarget target, Consumer<Component> messages) {
        if (!paced || grabbing) return false;
        grabs.add(new Grab(dir, name, target, messages));
        return true;
    }

    /**
     * MinecraftMixin, right after Renderer.endFrame (where F2 runs): when this frame paused for them, the stored grabs run now. With
     * frame generation turned off in between they run all the same (the frame then went to the swapchain as without it).
     */
    public static void afterEndFrame() {
        if (!screenshotFrame) return;
        screenshotFrame = false;
        Grab[] waiting = grabs.toArray(new Grab[0]);
        grabs.clear();
        grabbing = true;
        try {
            for (Grab g : waiting) Screenshot.grab(g.dir(), g.name(), g.target(), g.messages());
        } finally {
            grabbing = false;
        }
    }

    /** PipelineState, while a UI layer pass is bound: a blend state UiBlend does not handle is logged once by name (FG design 4). */
    public static void reportBlend(int blendState) {
        if (UiBlend.handled(blendState) || !seenBlends.add(blendState)) return;
        String name = UiBlend.describe(blendState);
        if (loggedBlends.add(name)) VisualsLog.warn("Frame generation UI layer: blend state " + name + " is not handled; it is drawn without coverage");
    }

    // ---- compose textures ----

    private static VulkanImage texture(ResourceLocation location) {
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(location);
        GlTexture gl = t == null ? null : GlTexture.getTexture(t.getId());
        return gl == null ? null : gl.getVulkanImage();
    }

    /** The vignette or icons image is not the one (object and view) the compose sets were built with. */
    private static boolean texturesChanged() {
        VulkanImage v = texture(Textures.VIGNETTE), i = texture(Textures.ICONS);
        return v != vignetteImage || i != iconsImage || v == null || i == null || v.getImageView() != vignetteView || i.getImageView() != iconsView;
    }

    /**
     * Points the compose sets at the current vignette and icons textures; false when either is missing. A replaced image object rebuilds
     * the sets even when Vulkan reused its view's handle value (final review M2). Pacer stopped.
     */
    private static boolean bindTextures() {
        VulkanImage v = texture(Textures.VIGNETTE), i = texture(Textures.ICONS);
        if (v == null || i == null) return false;
        boolean replaced = v != vignetteImage || i != iconsImage;
        vignetteImage = v;
        iconsImage = i;
        vignetteView = v.getImageView();
        iconsView = i.getImageView();
        targets.refreshTextures(vignetteView, iconsView, replaced);
        return true;
    }

    /** Outside a render pass: the compose textures in SHADER_READ_ONLY_OPTIMAL for the pacer (usually already so: no barrier). */
    private static void texturesReadable(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (vignetteImage != null) vignetteImage.readOnlyLayout(stack, cmd);
            if (iconsImage != null) iconsImage.readOnlyLayout(stack, cmd);
        }
    }

    // ---- lifecycle ----

    /** Before the swapchain is recreated (and the device waited idle): the pacer stops and drains. */
    public static void beforeSwapchainRecreate() {
        if (pacer != null) pacer.stop();
        dropBurst();
        paced = false;
    }

    /**
     * After the swapchain was recreated and the resize callbacks ran (device idle): applies the setting. Wanted: the resources are made
     * on first use and the pacer restarts; not wanted: they are freed.
     */
    public static void afterSwapchainRecreate() {
        appliedSetting = VisualsConfig.get().frameGeneration;
        resetFrame();
        if (pacer != null && pacer.failed()) broken = true;
        if (!wanted()) {
            paced = false;
            freeResources();
            return;
        }
        paced = createResources() && startPacer();
        //a failed start marked frame generation broken: the device is idle here, so what it built goes now
        if (!paced && broken) freeResources();
    }

    /** Minecraft.close, before Reforged waits for the device: the pacer must not present into a destroyed swapchain. */
    public static void shutdown() {
        paced = false;
        if (pacer == null) return;
        try {
            pacer.stop();
            dropBurst();
            Vulkan.waitIdle();
            freeResources();
        } catch (RuntimeException e) {
            VisualsLog.error("Frame generation shutdown failed", e);
        }
    }

    private static boolean createResources() {
        if (pacer != null) return true;
        try {
            deferred = new DeferredFree();
            mainDepthViews = new ImageViewCache(VkContext::createMainDepthView, VkImage2D::destroyView);
            targets = new FrameGenTargets(deferred);
            warp = new VkWarp(targets);
            pacer = new FramePacer();
            VisualsLog.info("Frame generation set up (factor " + factor + ")");
            return true;
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.error("Frame generation could not be set up; it stays off until the game restarts", e);
            broken = true;
            freeResources();
            return false;
        }
    }

    /** Frees what exists. The pacer is stopped and the device idle (a swapchain recreation, the game's close). */
    private static void freeResources() {
        //the main pass lets go of the UI layer's passes and GL ids before its framebuffers go (final review M6)
        if (targets != null) Renderer.getInstance().getMainPass().releaseUiLayer();
        if (pacer != null) pacer.free();
        if (targets != null) targets.free();
        if (warp != null) warp.free();
        if (mainDepthViews != null) mainDepthViews.free();
        if (deferred != null) deferred.freeAll();
        pacer = null;
        targets = null;
        warp = null;
        mainDepthViews = null;
        deferred = null;
        vignetteImage = null;
        iconsImage = null;
        vignetteView = 0;
        iconsView = 0;
    }

    /** A render-thread failure: frame generation turns off through a swapchain recreation (no half-paced frames). */
    private static void fail(String what, RuntimeException e) {
        VisualsLog.error(what + "; frame generation stays off until the game restarts", e);
        broken = true;
        Renderer.scheduleSwapChainUpdate();
    }

    /**
     * Matches the targets to the swapchain, binds the compose textures and starts the pacer (it must be stopped). False when it cannot
     * run: no resources, a minimised window, no compose textures, or the start failed (final review I2: an allocation, a descriptor pool,
     * the compose pipeline). A failure marks frame generation broken; the caller's swapchain recreation (or, on the recreation path,
     * afterSwapchainRecreate itself) then frees everything, so nothing leaves Renderer.beginFrame by an exception.
     */
    static boolean startPacer() {
        if (pacer == null || targets == null || warp == null || Vulkan.DYNAMIC_RENDERING) return false;
        try {
            SwapChain sc = Vulkan.getSwapChain();
            if (sc.getWidth() == 0 || sc.getHeight() == 0) return false;
            Minecraft mc = Minecraft.getInstance();
            FrameGenTargets.Rebuild rebuilt = targets.ensure(sc.getWidth(), sc.getHeight(), RenderScale.clamp(Initializer.CONFIG.renderScale),
                    mc.level != null && mc.screen == null);
            //new UI framebuffers (a new swapchain size): the main pass drops the old UI passes and GL ids now (final review I3)
            if (rebuilt == FrameGenTargets.Rebuild.ALL) Renderer.getInstance().getMainPass().releaseUiLayer();
            if (!bindTextures()) return false;
            RenderPass present = Renderer.getInstance().getMainPass().presentRenderPass();
            if (present == null) return false;
            windowStart = 0;
            return pacer.start(targets, warp, present.getId(), factor);
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.error("Frame generation could not start; it stays off until the game restarts", e);
            broken = true;
            return false;
        }
    }

    // ---- F3 ----

    /** Counts the pacer's presents and skips; every second the rates for F3. */
    private static void drainLog() {
        pacer.log().drain(FrameGen::count);
        long now = System.nanoTime();
        if (windowStart == 0) {
            windowStart = now;
        } else if (now - windowStart >= STATS_WINDOW_NS) {
            double seconds = (now - windowStart) * 1e-9;
            realFps = realCount / seconds;
            shownFps = shownCount / seconds;
            dropped = droppedCount;
            realCount = 0;
            shownCount = 0;
            droppedCount = 0;
            windowStart = now;
        }
    }

    /** One present log record: shown frames, real ones (T-hat from their present calls), generated ones skipped while generating. */
    private static void count(long[] r) {
        if (FrameRecorder.ON) FrameRecorder.present(r);
        boolean real = r[0] == PresentLog.KIND_REAL;
        if (r[5] == FramePacing.Decision.PRESENT.ordinal()) {
            shownCount++;
            if (real) {
                realCount++;
                intervals.realPresented(r[3]);
            }
        } else if (!real && r[5] != FramePacing.Decision.SKIP_PAUSED.ordinal()) {
            droppedCount++;
        }
    }

    /**
     * F3 (FG design 7): real and shown frame rates and generated frames dropped over the last second, T-hat, and the pause reason
     * ("on" while generating). Nothing while the setting is off and the pacer does not run.
     */
    public static void addDebugInfo(List<String> lines) {
        if (!paced && !VisualsConfig.get().frameGeneration) return;
        String state;
        if (paced) state = reason == null ? "on" : reason;
        else if (!supported()) state = PauseRules.UNSUPPORTED;
        else if (broken || pacer != null && pacer.failed()) state = "failed";
        else state = "not running";
        lines.add(String.format(Locale.ROOT, "FG: real %.0f fps, shown %.0f fps, dropped %d, T̂ %.1f ms, %s", realFps, shownFps, dropped,
                intervals.intervalEstimate() * 1e-6, state));
    }
}
