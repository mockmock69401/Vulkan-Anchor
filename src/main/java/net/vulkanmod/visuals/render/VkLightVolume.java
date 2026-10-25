package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** P7l design 3.1: the light volume on the GPU: one byte per block per slot, and the ready table (one key per slot, 0 = empty). */
public final class VkLightVolume {
    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private final LightVolumeMath.Layout layout;
    private final VkGpuBuffer data, ready;
    private final long key;
    private long clearedSerial = -1;

    public VkLightVolume(LightVolumeMath.Layout layout) {
        this.layout = layout;
        VkGpuBuffer data = VkGpuBuffer.device(layout.bytes(), USAGE);
        try {
            this.ready = VkGpuBuffer.device((long) layout.slots() * 4, USAGE);
        } catch (RuntimeException e) {
            data.free();
            throw e;
        }
        this.data = data;
        this.key = MemoryUtil.nmemAlloc(4);
    }

    public LightVolumeMath.Layout layout() {
        return this.layout;
    }

    public VkGpuBuffer data() {
        return this.data;
    }

    public VkGpuBuffer ready() {
        return this.ready;
    }

    /**
     * Outside a render pass, before the upload commit: the ready table emptied (all unknown) once, again if that frame was dropped.
     * True for a re-clear after a drop: the keys the CPU counts as held are gone (design 3.1).
     */
    public boolean clearIfNeeded(VkCommandBuffer cmd) {
        if (this.clearedSerial >= 0 && !VkFrames.isDropped(this.clearedSerial)) return false;
        boolean again = this.clearedSerial >= 0;
        vkCmdFillBuffer(cmd, this.ready.handle(), 0, VK_WHOLE_SIZE, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, barrier, null, null);
        }
        this.clearedSerial = VkFrames.serial();
        return again;
    }

    /** Queues a section's bytes into its slot, then the slot's key (the commit records the copies in this order). */
    public void upload(VkUploadStream upload, int sx, int sy, int sz, ByteBuffer bytes) {
        int slot = this.layout.slot(sx, sy, sz);
        upload.upload(this.data, (long) slot * LightVolumeMath.SECTION_BYTES, MemoryUtil.memAddress(bytes), LightVolumeMath.SECTION_BYTES);
        MemoryUtil.memPutInt(this.key, this.layout.key(sx, sy, sz));
        upload.upload(this.ready, (long) slot * 4, this.key, 4);
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        this.data.free();
        this.ready.free();
        MemoryUtil.nmemFree(this.key);
    }
}
