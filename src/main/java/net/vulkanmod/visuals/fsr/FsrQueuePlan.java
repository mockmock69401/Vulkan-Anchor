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
        //the next spare queue index per family
        int[] next = new int[counts.length];
        for (int family : usedFamilies) {
            next[family] = 1;
        }

        int present = -1;
        for (int f = 0; f < counts.length && present < 0; f++) {
            if (presentSupport[f] && (flags[f] & (VK_QUEUE_TRANSFER_BIT | VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) != 0
                    && next[f] < counts[f]) {
                present = f;
            }
        }
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
