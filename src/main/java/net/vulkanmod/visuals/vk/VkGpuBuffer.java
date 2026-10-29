package net.vulkanmod.visuals.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** A VMA buffer: device-local for GPU data, or host-visible and persistently mapped for staging and readback. */
public final class VkGpuBuffer {
    private final long buffer;
    private final long allocation;
    private boolean freed;
    private final long size;
    private final ByteBuffer mapped;

    private VkGpuBuffer(long size, int usage, boolean host) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(usage | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack);
            if (host) {
                alloc.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                        .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
            } else {
                alloc.requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            int result = Vma.vmaCreateBuffer(VkContext.allocator(), info, alloc, pBuffer, pAlloc, null);
            if (result != VK_SUCCESS) throw creationFailure(result);
            this.buffer = pBuffer.get(0);
            this.allocation = pAlloc.get(0);
            this.size = size;
            if (host) {
                PointerBuffer pData = stack.mallocPointer(1);
                result = Vma.vmaMapMemory(VkContext.allocator(), this.allocation, pData);
                if (result != VK_SUCCESS) {
                    //2026-10-30 (audit): the buffer no longer leaks when it cannot be mapped
                    Vma.vmaDestroyBuffer(VkContext.allocator(), this.buffer, this.allocation);
                    throw new IllegalStateException("vmaMapMemory failed: " + result);
                }
                this.mapped = MemoryUtil.memByteBuffer(pData.get(0), (int) size);
            } else {
                this.mapped = null;
            }
        }
    }

    /** What a failed vmaCreateBuffer throws: out of memory is told apart, so a large pool can be retried smaller (P7a). */
    static IllegalStateException creationFailure(int result) {
        String message = "vmaCreateBuffer failed: " + result;
        return result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY ? new VkOutOfMemoryException(message)
                : new IllegalStateException(message);
    }

    public static VkGpuBuffer device(long size, int usage) {
        return new VkGpuBuffer(size, usage, false);
    }

    public static VkGpuBuffer host(long size, int usage) {
        return new VkGpuBuffer(size, usage, true);
    }

    public long handle() {
        return this.buffer;
    }

    public long size() {
        return this.size;
    }

    public ByteBuffer mapped() {
        if (this.mapped == null) throw new IllegalStateException("device-local buffer is not mapped");
        return this.mapped;
    }

    /** Only once no submitted frame can still use it. 2026-10-30 (audit): a second call does nothing. */
    public void free() {
        if (this.freed) return;
        this.freed = true;
        if (this.mapped != null) Vma.vmaUnmapMemory(VkContext.allocator(), this.allocation);
        Vma.vmaDestroyBuffer(VkContext.allocator(), this.buffer, this.allocation);
    }

    /** 2026-10-30 (audit): whether free ran (VkUploadStream drops copies into a freed buffer). */
    public boolean freed() {
        return this.freed;
    }
}
