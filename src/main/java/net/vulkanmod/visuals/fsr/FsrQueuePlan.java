package net.vulkanmod.visuals.fsr;

import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_TRANSFER_BIT;

/**
 * FSR design 4.2: the queues FSR's frame-generation swapchain gets besides VulkanMod's, as (family, index) pairs. VulkanMod
 * uses index 0 of each of its families; every other queue the GPU offers is spare. computeFamily is -1 when there is no
 * spare queue for async compute.
 */
public record FsrQueuePlan(int presentFamily, int presentIndex, int acquireFamily, int acquireIndex, int computeFamily,
                           int computeIndex) {

    /**
     * The present queue is the first spare queue in a family that can present and transfer (graphics or compute imply
     * transfer); the acquire queue is the next spare queue anywhere; the async compute queue is a spare queue in a compute
     * family, one without graphics first. Empty when the present or acquire queue cannot be placed. FSR would pick its composition
     * mode from the present family (graphics and compute: compose on the present queue); FsrSwapchain overrides it with composition
     * on the game queue, so the present queue only copies and presents, and any family that can present and transfer will do.
     *
     * @param flags          each family's VkQueueFlags
     * @param counts         each family's queue count
     * @param presentSupport whether each family can present to the window's surface
     * @param usedFamilies   VulkanMod's families, each using its queue 0
     */
    public static Optional<FsrQueuePlan> plan(int[] flags, int[] counts, boolean[] presentSupport, int[] usedFamilies) {
        return plan(flags, counts, presentSupport, usedFamilies, -1);
    }

    /**
     * 2026-11-03 (FSR command list crash, FsrCommandPoolGate): the present queue goes outside the game queue's family when another
     * family can take it, so the presenter thread's command lists come from that family's eight. Sharing one family (the RTX 5070's
     * family 0), one present took up to 7 lists and none were left (3 of 3 uncapped pilots crashed); apart, 3 of 3 ran through with
     * 2 free in the game's family at the fewest. FSR then transfers the images' queue family ownership on every present, which cost
     * about a sixth of the real frames uncapped. -Dvulkanmod.fsr.separatePresentFamily=false keeps the queues together.
     */
    public static final boolean SEPARATE_PRESENT_FAMILY = !"false".equals(System.getProperty("vulkanmod.fsr.separatePresentFamily"));

    /** {@link #plan(int[], int[], boolean[], int[])}, with the present queue outside avoidFamily when possible (-1: anywhere). */
    public static Optional<FsrQueuePlan> plan(int[] flags, int[] counts, boolean[] presentSupport, int[] usedFamilies, int avoidFamily) {
        //the next spare queue index per family
        int[] next = new int[counts.length];
        for (int family : usedFamilies) {
            next[family] = 1;
        }

        int present = presentFamily(flags, counts, presentSupport, next, avoidFamily);
        if (present < 0 && avoidFamily >= 0) present = presentFamily(flags, counts, presentSupport, next, -1);
        if (present < 0) return Optional.empty();
        int presentIndex = next[present]++;

        int acquire = -1;
        for (int f = 0; f < counts.length && acquire < 0; f++) {
            if (next[f] < counts[f]) {
                acquire = f;
            }
        }
        if (acquire < 0) return Optional.empty();
        int acquireIndex = next[acquire]++;

        int compute = spareCompute(flags, counts, next, true);
        if (compute < 0) compute = spareCompute(flags, counts, next, false);
        int computeIndex = compute < 0 ? -1 : next[compute]++;

        return Optional.of(new FsrQueuePlan(present, presentIndex, acquire, acquireIndex, compute, computeIndex));
    }

    /** The first family other than skip that can present and transfer and has a spare queue, or -1. */
    private static int presentFamily(int[] flags, int[] counts, boolean[] presentSupport, int[] next, int skip) {
        for (int f = 0; f < counts.length; f++) {
            if (f != skip && presentSupport[f] && (flags[f] & (VK_QUEUE_TRANSFER_BIT | VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) != 0
                    && next[f] < counts[f]) {
                return f;
            }
        }
        return -1;
    }

    private static int spareCompute(int[] flags, int[] counts, int[] next, boolean withoutGraphics) {
        for (int f = 0; f < counts.length; f++) {
            boolean compute = (flags[f] & VK_QUEUE_COMPUTE_BIT) != 0;
            boolean graphics = (flags[f] & VK_QUEUE_GRAPHICS_BIT) != 0;
            if (compute && !(withoutGraphics && graphics) && next[f] < counts[f]) {
                return f;
            }
        }
        return -1;
    }

    /**
     * The number of queues to create in each family, indexed by family: 1 for each of VulkanMod's families, raised to cover
     * the plan's queues, and the families only the plan uses. 0 for a family that gets no queue.
     */
    public static int[] queueCounts(Optional<FsrQueuePlan> plan, int[] uniqueFamilies) {
        int length = 0;
        for (int family : uniqueFamilies) {
            length = Math.max(length, family + 1);
        }
        if (plan.isPresent()) {
            FsrQueuePlan p = plan.get();
            length = Math.max(length, Math.max(p.presentFamily, Math.max(p.acquireFamily, p.computeFamily)) + 1);
        }

        int[] counts = new int[length];
        for (int family : uniqueFamilies) {
            counts[family] = 1;
        }
        plan.ifPresent(p -> {
            cover(counts, p.presentFamily, p.presentIndex);
            cover(counts, p.acquireFamily, p.acquireIndex);
            if (p.computeFamily >= 0) cover(counts, p.computeFamily, p.computeIndex);
        });
        return counts;
    }

    private static void cover(int[] counts, int family, int index) {
        counts[family] = Math.max(counts[family], index + 1);
    }
}
