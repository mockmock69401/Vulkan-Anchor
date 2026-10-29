package net.vulkanmod.visuals.vk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Frame serials for the Vulkan backend, driven by Reforged's Renderer source hooks from Reforged's own synchronisation (see
 * FrameTracker). Listeners run when a frame starts recording, after its slot's fence was waited.
 */
public final class VkFrames {
    private static final FrameTracker TRACKER = new FrameTracker();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private VkFrames() {
    }

    /** The frame currently being recorded. */
    public static long serial() {
        return TRACKER.recordingSerial();
    }

    /** Every submitted frame up to this serial has finished on the GPU. */
    public static long completedSerial() {
        return TRACKER.completedSerial();
    }

    /** The frame started recording but was never submitted; its commands never ran. */
    public static boolean isDropped(long serial) {
        return TRACKER.isDropped(serial);
    }

    public static void addListener(Runnable onRecordingStarted) {
        LISTENERS.add(onRecordingStarted);
    }

    public static void removeListener(Runnable onRecordingStarted) {
        LISTENERS.remove(onRecordingStarted);
    }

    /** The fence slot of the frame being recorded. */
    public static int recordingSlot() {
        return TRACKER.recordingSlot();
    }

    public static void onFenceWaited(int slot) {
        TRACKER.fenceWaited(slot);
        VkGpuTimer.onFenceWaited(slot);
    }

    /** P7f: the frame's command buffer is begun, Reforged's main pass not yet (before onRecordingStarted). */
    public static void onFrameStart(org.lwjgl.vulkan.VkCommandBuffer cmd, int slot) {
        VkGpuTimer.onFrameStart(cmd, slot);
    }

    public static void onRecordingStarted(int slot) {
        TRACKER.recordingStarted(slot);
        VkDescriptorRing.INSTANCE.onRecordingStarted(slot);
        VkGpuTimer.onRecordingStarted(slot);
        //2026-10-30 (audit): one throwing listener no longer skips the others' tick (layout trackers, rings, deferred frees)
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (RuntimeException e) {
                net.vulkanmod.visuals.VisualsLog.error("Visuals: a frame listener failed", e);
            }
        }
    }

    /** 2026-10-30 (audit): Renderer.recreateSwapChain, after it changed the number of frames in flight (device idle). */
    public static void onFrameCountChanged() {
        VkGpuTimer.onFrameCountChanged();
    }

    public static void onSubmitted() {
        TRACKER.submitted();
    }

    public static void onDeviceIdle() {
        TRACKER.deviceIdle();
        VkGpuTimer.onDeviceIdle();
    }
}
