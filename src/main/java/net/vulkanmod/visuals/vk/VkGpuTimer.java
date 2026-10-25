package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.VisualsLog;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * P7f design 3: GPU timestamps on the frame's command buffer. Render thread only.
 * <ul>
 * <li>Frame start (Reforged's beginFrame, the command buffer begun, before its main pass): the slot's last frame is read
 * (its fence was waited), its queries cleared and the first interval, "vanilla.early" (sky, solid and cutout terrain up to
 * the visuals' frame()), marked. reset() does the clearing instead when that hook did not run this frame.</li>
 * <li>mark() starts a labelled interval (also inside a render pass).</li>
 * </ul>
 * On only with -Dvulkanmod.visuals.gpuTimes=true or the gate pilot (review 4: some drivers drain the pipe at each timestamp).
 */
public final class VkGpuTimer {
    static final int MARKS = 64;
    /** Read by the stats log (and cleared there) and by the pilot's gpu command (and cleared there). */
    public static final GpuTimes STATS = new GpuTimes(), PILOT = new GpuTimes();

    private static boolean tried, on;
    private static long pool;
    private static int slots;
    private static double nsPerTick;
    private static long validMask;
    private static long[] slotSerial;
    private static int[] slotCount;
    private static String[][] slotLabels;
    private static long resetSerial = -1;
    private static int currentSlot, armedSlot = -1, startedSlot = -1;
    private static final long[] TICKS = new long[MARKS];

    private VkGpuTimer() {
    }

    /** Whether the timer is wanted this session (the stats log prints its line only then). */
    public static boolean wanted() {
        return Boolean.getBoolean("vulkanmod.visuals.gpuTimes") || System.getProperty("vulkanmod.visuals.pilot") != null;
    }

    private static void init() {
        tried = true;
        if (!wanted()) return;
        try {
            int bits = VkContext.graphicsTimestampBits();
            float period = VkContext.timestampPeriod();
            int frames = VkContext.framesInFlight();
            if (bits == 0 || period <= 0 || frames <= 0) {
                VisualsLog.info("Vulkan GPU times off: no timestamps on the graphics queue (bits " + bits + ", period " + period + ")");
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP)
                        .queryCount(frames * MARKS);
                LongBuffer out = stack.mallocLong(1);
                int r = vkCreateQueryPool(VkContext.device(), info, null, out);
                if (r != VK_SUCCESS) {
                    VisualsLog.warn("Vulkan GPU times off: vkCreateQueryPool " + r);
                    return;
                }
                pool = out.get(0);
            }
            slots = frames;
            nsPerTick = period;
            validMask = bits >= 64 ? -1L : (1L << bits) - 1;
            slotSerial = new long[slots];
            slotCount = new int[slots];
            slotLabels = new String[slots][MARKS];
            on = true;
            VisualsLog.info("Vulkan GPU times on: " + slots + " frame slots, " + bits + " timestamp bits, " + period + " ns a tick");
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Vulkan GPU times off: " + e);
        }
    }

    /** VkFrames: the slot's fence was waited; the next frame start is this slot's. */
    static void onFenceWaited(int slot) {
        armedSlot = slot;
    }

    /** The Renderer source hook: the frame's command buffer is begun, its main pass not yet. */
    static void onFrameStart(VkCommandBuffer cmd, int slot) {
        if (slot != armedSlot) return;//another beginRenderPass call: not a frame start
        armedSlot = -1;
        if (!on || slot < 0 || slot >= slots) return;//made by the first reset() of the visuals' frame
        collect(slot);
        vkCmdResetQueryPool(cmd, pool, slot * MARKS, MARKS);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, slot * MARKS);
        slotLabels[slot][0] = "vanilla.early";
        slotCount[slot] = 1;
        slotSerial[slot] = -1;//stamped when VkFrames gives the frame its serial, right after
        startedSlot = slot;
    }

    /** VkFrames: this frame's serial exists now. */
    static void onRecordingStarted(int slot) {
        if (!on || slot < 0 || slot >= slots) return;
        if (startedSlot == slot) {
            startedSlot = -1;
            slotSerial[slot] = VkFrames.serial();
            resetSerial = slotSerial[slot];
            currentSlot = slot;
        } else {
            collect(slot);//the frame start hook did not run: read here, reset() clears later
        }
    }

    /** Review 2: the device is idle (swapchain recreated): no slot holds a frame to read; a changed frame count rebuilds the pool. */
    static void onDeviceIdle() {
        if (!on) return;
        java.util.Arrays.fill(slotCount, 0);
        if (VkContext.framesInFlight() != slots) free();
    }

    /** Outside a render pass: clears this frame slot's queries unless the frame start did; later calls do nothing. */
    public static void reset(VkCommandBuffer cmd) {
        if (!tried) init();
        if (!on) return;
        long serial = VkFrames.serial();
        if (resetSerial == serial) return;
        int slot = VkFrames.recordingSlot();
        if (slot < 0 || slot >= slots) return;
        vkCmdResetQueryPool(cmd, pool, slot * MARKS, MARKS);
        resetSerial = serial;
        currentSlot = slot;
        slotSerial[slot] = serial;
        slotCount[slot] = 0;
    }

    /** Starts the interval named label (also allowed inside a render pass); nothing before this frame's reset. */
    public static void mark(VkCommandBuffer cmd, String label) {
        if (!on || resetSerial != VkFrames.serial()) return;
        int n = slotCount[currentSlot];
        if (n >= MARKS) return;
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, currentSlot * MARKS + n);
        slotLabels[currentSlot][n] = label;
        slotCount[currentSlot] = n + 1;
    }

    /** The slot's last frame, when it ran (its fence was waited): added to both accumulators. */
    private static void collect(int slot) {
        int count = slotCount[slot];
        slotCount[slot] = 0;
        long serial = slotSerial[slot];
        if (count < 2 || serial < 0 || VkFrames.isDropped(serial) || VkFrames.completedSerial() < serial) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer results = stack.mallocLong(count);
            int r = vkGetQueryPoolResults(VkContext.device(), pool, slot * MARKS, count, results, Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (r != VK_SUCCESS) return;
            results.get(0, TICKS, 0, count);
        }
        if (FrameRecorder.ON) {
            FrameRecorder.add(FrameRecorder.GPU_US, (long) (((TICKS[count - 1] - TICKS[0]) & validMask) * nsPerTick / 1000));
            FrameRecorder.set(FrameRecorder.GPU_END, (long) ((TICKS[count - 1] & validMask) * nsPerTick / 1000));//when the frame ended on the GPU
        }
        STATS.add(slotLabels[slot], TICKS, count, nsPerTick, validMask);
        PILOT.add(slotLabels[slot], TICKS, count, nsPerTick, validMask);
    }

    /** CPU time of a render-thread step (P7f design 3: frame() and postProcess()). */
    public static void cpu(String label, long ns) {
        if (FrameRecorder.ON) FrameRecorder.add(label.equals("frame") ? FrameRecorder.VIS_FRAME : FrameRecorder.VIS_POST, ns);
        if (!on) return;
        STATS.addCpu(label, ns);
        PILOT.addCpu(label, ns);
    }

    public static boolean on() {
        return on;
    }

    /** Review 3: with the device idle (render system shutdown, swapchain rebuild): the pool goes; the next frame makes it again. */
    public static void free() {
        if (pool != 0) vkDestroyQueryPool(VkContext.device(), pool, null);
        pool = 0;
        on = false;
        tried = false;
        resetSerial = -1;
        armedSlot = startedSlot = -1;
    }
}
