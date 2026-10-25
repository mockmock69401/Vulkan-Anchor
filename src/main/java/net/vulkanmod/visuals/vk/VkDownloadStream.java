package net.vulkanmod.visuals.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU → CPU readback through a host-visible ring; a consumer runs once the frame that copied has finished on the GPU,
 * or receives null when that frame was never submitted (the copy did not run).
 */
public final class VkDownloadStream {
    private record Copy(VkGpuBuffer src, long srcOffset, long ringOffset, long size, Consumer<ByteBuffer> consumer) {}

    private static final int SHADER_STAGES = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private final VkGpuBuffer staging;
    private final CopyLedger<Copy> ledger;

    public VkDownloadStream(long capacity) {
        this.staging = VkGpuBuffer.host(capacity, 0);
        this.ledger = new CopyLedger<>(new FrameRing(capacity));
    }

    public void download(VkGpuBuffer src, long srcOffset, long size, Consumer<ByteBuffer> consumer) {
        long offset = this.ledger.reserve(size, 16);
        if (offset < 0) throw new IllegalStateException("download stream full (" + size + " bytes)");
        this.ledger.queue(new Copy(src, srcOffset, offset, size, consumer), offset);
    }

    public void commit(VkCommandBuffer cmd) {
        if (!this.ledger.hasPending()) return;
        List<Copy> copies = this.ledger.commit(VkFrames.serial());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer before = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cmd, SHADER_STAGES | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, before, null, null);
            for (Copy c : copies) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(c.srcOffset()).dstOffset(c.ringOffset()).size(c.size());
                vkCmdCopyBuffer(cmd, c.src().handle(), this.staging.handle(), region);
            }
            // Host reads wait for the copy; later writes to the sources wait for the copy to have read them.
            VkMemoryBarrier.Buffer after = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT | SHADER_STAGES | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, after, null, null);
        }
    }

    /** Call when a frame starts recording (VkFrames listener). */
    public void tick() {
        this.ledger.tick(VkFrames.completedSerial(), VkFrames::isDropped,
                c -> c.consumer().accept(MemoryUtil.memByteBuffer(MemoryUtil.memAddress(this.staging.mapped()) + c.ringOffset(), (int) c.size())),
                c -> {
                    c.consumer().accept(null);
                    return false;
                });
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        this.staging.free();
    }
}
