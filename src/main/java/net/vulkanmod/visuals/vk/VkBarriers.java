package net.vulkanmod.visuals.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/** Memory barriers for the visuals' own buffers, recorded outside any render pass. */
public final class VkBarriers {
    private static final int STAGES = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT
            | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    private static final int READS_AND_WRITES = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT
            | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT;

    private VkBarriers() {
    }

    /** Everything the visuals did in earlier frames (reads and writes) happens before this frame's the visuals work. */
    public static void frameStart(VkCommandBuffer cmd) {
        barrier(cmd, STAGES, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, STAGES, READS_AND_WRITES);
    }

    /** Compute writes are visible to later compute, indirect arguments, transfers and shaders. */
    public static void afterCompute(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, STAGES, READS_AND_WRITES);
    }

    /** Fill/update/copy writes are visible to later compute, indirect arguments, transfers and shaders. */
    public static void afterTransfer(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, STAGES, READS_AND_WRITES);
    }

    /** Draw-time shader writes (the cull raster's visibility) and indirect/vertex reads finish before later the visuals work. */
    public static void afterGraphics(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, STAGES, READS_AND_WRITES);
    }

    /**
     * Vanilla's depth, written by Reforged's main pass, is visible to compute reads (the vanilla distance map, the
     * better SSAO): Reforged's layout transition only waits for and exposes it to fragment shaders.
     */
    public static void mainDepthForCompute(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /**
     * The main colour, written by Reforged's main pass, is visible to compute reads (the post-process copy): Reforged's
     * layout transition only waits for and exposes it to fragment shaders.
     */
    public static void mainColourForCompute(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /** Compute reads of the main colour finish before Reforged's transition back to an attachment, which waits for fragment shaders only. */
    public static void mainColourAfterCompute(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0);
    }

    /**
     * Compute reads of the main depth (P7e-1's copies) finish before Reforged's transition back to an attachment, which waits
     * for fragment shaders only.
     */
    public static void mainDepthAfterCompute(VkCommandBuffer cmd) {
        barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0);
    }

    private static void barrier(VkCommandBuffer cmd, int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStages, dstStages, 0, b, null, null);
        }
    }
}
