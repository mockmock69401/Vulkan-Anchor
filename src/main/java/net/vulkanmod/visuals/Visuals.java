package net.vulkanmod.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.visuals.framegen.FrameGen;
import net.vulkanmod.visuals.render.VisualsRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.function.Consumer;

/**
 * The visuals' one renderer, tied to Reforged's WorldRenderer lifecycle: built at the end of allChanged, dropped at the start of
 * setLevel. Reforged's frame (renderSectionLayer) and GameRendererPostMixin call in here; every call is a no-op without a renderer.
 */
public final class Visuals {
    private static VisualsRenderer current;

    private Visuals() {
    }

    /** WorldRenderer.allChanged: a renderer for the client's level, whenever a world is open. */
    public static void recreate(ClientLevel level) {
        shutdown();
        if (!isClientLevel(level, Minecraft.getInstance().level)) {
            //Reforged's allChanged inside vanilla setLevel still sees the old level; its own setLevel rebuilds for the new one
            return;
        }
        try {
            current = new VisualsRenderer(level);
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.error("Visuals renderer not created; the game continues without the visuals", e);
        }
    }

    /** Whether the renderer's level is the one the client is in (not null, the same object). */
    static boolean isClientLevel(Object rendererLevel, Object clientLevel) {
        return rendererLevel != null && rendererLevel == clientLevel;
    }

    /** This world has a renderer whose post-process has not failed. */
    public static boolean active() {
        return current != null && current.active();
    }

    /** WorldRenderer.setLevel; safe to call again. */
    public static void shutdown() {
        if (current != null) {
            VisualsRenderer r = current;
            current = null;
            r.close();
            boolean recording = net.vulkanmod.vulkan.Renderer.isRecording();
            retire(() -> r.free(!recording), recording, op -> net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(op));
        }
    }

    /**
     * 2026-10-31 (the user: turning on a font resource pack in game froze it): a reload's allChanged runs inside a frame (its
     * LoadingOverlay renders it), whose commands, not yet submitted, use the old renderer's buffers and images (VK_ERROR_DEVICE_LOST
     * once they were destroyed). Inside a frame they are freed after it completes (afterFrame: MemoryManager's frame ops, run once
     * that frame's fence is waited for), else now.
     */
    static void retire(Runnable free, boolean recording, Consumer<Runnable> afterFrame) {
        if (recording) {
            afterFrame.accept(free);
        } else {
            free.run();
        }
    }

    /** WorldRenderer.renderSectionLayer, after the cutout terrain. */
    public static void frame(Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        VisualsRenderer r = current;
        if (r != null && r.runsFrames()) {
            r.frame(projection, modelView, cameraX, cameraY, cameraZ);
            return;
        }
        //2026-10-30 (audit): no visuals frame (the renderer failed or there is none): DH's staged uploads are still recorded, else
        // they piled up (up to 320 MB) and were then refused, leaving LOD holes after a reload
        if (net.vulkanmod.visuals.dh.DhPresence.isActive()) net.vulkanmod.visuals.dh.DhStaging.INSTANCE.flushOutsideVisuals();
    }

    /** WorldRenderer.renderSectionLayer: the main depth right before (VkDepthCapture.BEFORE) or after (AFTER) the translucent terrain. */
    public static void captureDepth(int slot) {
        if (current != null) current.captureDepth(slot);
    }

    /** GameRendererPostMixin: the full-screen post-process, after the level and before the hand. */
    public static void postProcess() {
        if (current != null) current.postProcess();
    }

    /** The LOD depth view and inverse LOD projection frame generation's capture reads (FG design 4). */
    public record FrameGenLod(long depthView, Matrix4f invProjection) {
    }

    /** FrameGen.beforeHand: this serial's LOD depth for the capture, or null (no LODs this frame). */
    public static FrameGenLod frameGenLod() {
        return current == null ? null : current.frameGenLod();
    }

    /** F3 (DebugScreenOverlayM): the renderer's lines, then frame generation's (none while it is off). */
    public static void addDebugInfo(List<String> lines) {
        if (current != null) current.addDebugInfo(lines);
        FrameGen.addDebugInfo(lines);
    }
}
