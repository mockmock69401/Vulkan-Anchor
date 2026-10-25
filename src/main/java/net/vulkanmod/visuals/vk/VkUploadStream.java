package net.vulkanmod.visuals.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * CPU → GPU uploads through a host-visible ring; copies are recorded by commit(), outside any render pass. A buffer or
 * image copy recorded into a frame Reforged never submitted is recorded again by the next commit. Raw slices are read
 * by shaders directly from the ring in the frame that commits them.
 */
public final class VkUploadStream {
    public record RawSlice(VkGpuBuffer buffer, long offset, long size, long address) {}

    private sealed interface Op permits BufferCopy, ImageCopy, Raw {}
    private record BufferCopy(long srcOffset, VkGpuBuffer dst, long dstOffset, long size) implements Op {}
    private record ImageCopy(long srcOffset, VkImage2D dst, int level, int x, int y, int width, int height) implements Op {}
    private record Raw() implements Op {}

    /** The largest minStorageBufferOffsetAlignment the Vulkan spec allows. */
    static final int RAW_ALIGNMENT = 256;
    private static final int SHADER_STAGES = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;

    private final VkGpuBuffer staging;
    private final CopyLedger<Op> ledger;

    public VkUploadStream(long capacity) {
        this.staging = VkGpuBuffer.host(capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.ledger = new CopyLedger<>(new FrameRing(capacity));
    }

    public void upload(VkGpuBuffer dst, long dstOffset, ByteBuffer data) {
        this.upload(dst, dstOffset, MemoryUtil.memAddress(data), data.remaining());
    }

    public void upload(VkGpuBuffer dst, long dstOffset, long address, long size) {
        long offset = this.reserve(size, 16);
        MemoryUtil.memCopy(address, this.base() + offset, size);
        this.ledger.queue(new BufferCopy(offset, dst, dstOffset, size), offset);
    }

    /** RGBA8 pixels into one mip level region of dst. */
    public void uploadImage(VkImage2D dst, int level, int x, int y, int width, int height, long address) {
        long size = (long) width * height * 4;
        long offset = this.reserve(size, 16);
        MemoryUtil.memCopy(address, this.base() + offset, size);
        this.ledger.queue(new ImageCopy(offset, dst, level, x, y, width, height), offset);
    }

    /** Ring memory the caller fills and binds as a storage buffer; it must be committed in the frame that reads it. */
    public RawSlice raw(long size) {
        long offset = this.reserve(size, RAW_ALIGNMENT);
        this.ledger.queue(new Raw(), offset);
        return new RawSlice(this.staging, offset, size, this.base() + offset);
    }

    private long reserve(long size, long alignment) {
        long offset = this.ledger.reserve(size, alignment);
        if (offset < 0) throw new IllegalStateException("upload stream full (" + size + " bytes)");
        return offset;
    }

    private long base() {
        return MemoryUtil.memAddress(this.staging.mapped());
    }

    public void commit(VkCommandBuffer cmd) {
        if (!this.ledger.hasPending()) return;
        List<Op> ops = this.ledger.commit(VkFrames.serial());
        Set<VkImage2D> images = new LinkedHashSet<>();
        for (Op op : ops) if (op instanceof ImageCopy c) images.add(c.dst());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Earlier reads and writes of the destinations (this or earlier frames) finish before the copy overwrites them.
            VkMemoryBarrier.Buffer before = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, SHADER_STAGES | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, before, null, null);
            OverlapTracker written = new OverlapTracker();
            for (Op op : ops) {
                if (op instanceof BufferCopy c) {
                    if (written.needsBarrier(c.dst().handle(), c.dstOffset(), c.size())) {
                        //A later copy to the same bytes (e.g. a biome patch inside a model record, or a requeued copy) must land last
                        VkMemoryBarrier.Buffer order = VkMemoryBarrier.calloc(1, stack).sType$Default()
                                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, order, null, null);
                    }
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(c.srcOffset()).dstOffset(c.dstOffset()).size(c.size());
                    vkCmdCopyBuffer(cmd, this.staging.handle(), c.dst().handle(), region);
                }
            }
            for (VkImage2D image : images) {
                image.transition(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, SHADER_STAGES | VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                for (Op op : ops) {
                    if (op instanceof ImageCopy c && c.dst() == image) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack).bufferOffset(c.srcOffset()).bufferRowLength(0).bufferImageHeight(0);
                        region.imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, c.level(), 0, 1);
                        region.imageOffset().set(c.x(), c.y(), 0);
                        region.imageExtent().set(c.width(), c.height(), 1);
                        vkCmdCopyBufferToImage(cmd, this.staging.handle(), image.handle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                    }
                }
                image.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        SHADER_STAGES, VK_ACCESS_SHADER_READ_BIT);
            }
            VkMemoryBarrier.Buffer after = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT
                            | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_UNIFORM_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, SHADER_STAGES | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, after, null, null);
        }
    }

    /** Call when a frame starts recording (VkFrames listener). */
    public void tick() {
        this.ledger.tick(VkFrames.completedSerial(), VkFrames::isDropped, op -> { }, op -> !(op instanceof Raw));
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        this.staging.free();
    }
}
