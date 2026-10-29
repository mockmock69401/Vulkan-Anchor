package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.FrameRing;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The GPU side of DH's buffers: device-local allocations, and a staging ring of their own, apart from the visuals' shared
 * upload stream (a full shared stream would stall the whole frame). An upload copies DH's bytes into the ring on the calling
 * thread (DH calls from the render thread, through its render-thread task queue); {@link #flush} records the copies into the
 * frame's command buffer, outside the main pass. When the ring has no room for an upload, that upload alone goes through a
 * temporary host buffer, freed once the frame that copied it has completed; temporary memory has a ceiling, past which an
 * upload is refused. A staging write that fails gives its ring region back and throws to the caller (DhVertexBuffer switches DH
 * drawing off). Everything is guarded by this object's lock.
 */
public final class DhStaging {
    /** A device-local buffer DH's data lives in. */
    public interface Allocation {
        long handle();

        long size();
    }

    /** What DH's buffer wrappers need of the device. */
    public interface Gpu {
        Allocation allocate(long bytes);

        /** Copies src now (src is left as given); the GPU copy is recorded later. False when refused: dst keeps old contents. */
        boolean upload(Allocation dst, ByteBuffer src);

        /** Frees a once no frame can use it; its copies not yet recorded are dropped. */
        void retire(Allocation a);
    }

    /** The device and the frame clock, apart so the bookkeeping is testable. */
    interface Backend {
        Allocation allocate(long bytes);

        void free(Allocation a);

        void writeRing(long offset, ByteBuffer src);

        /** A host buffer holding a copy of src. */
        Object copyToTemp(ByteBuffer src);

        void freeTemp(Object temp);

        /** The copies and their barriers, outside any render pass. */
        void record(VkCommandBuffer cmd, List<Copy> copies);

        long serial();

        long completedSerial();

        boolean isDropped(long serial);
    }

    /** One recorded copy: from the ring at srcOffset (temp null) or from the start of temp. */
    record Copy(Object temp, long srcOffset, Allocation dst, long size) {}

    enum Route { RING, TEMP, REFUSE }

    /** Room for a frame or two of DH's busiest uploads (DH caps one buffer at 2 MiB and runs its uploads in half a frame). */
    static final long RING_BYTES = 64L << 20;
    static final long TEMP_LIMIT = 256L << 20;
    private static final long ALIGNMENT = 16;

    public static final DhStaging INSTANCE = new DhStaging(new VkBackend(RING_BYTES), RING_BYTES, TEMP_LIMIT);

    private static final class Op {
        final Allocation dst;
        final Object temp;
        final long offset;
        final long size;
        long serial = FrameRing.PENDING;

        Op(Allocation dst, Object temp, long offset, long size) {
            this.dst = dst;
            this.temp = temp;
            this.offset = offset;
            this.size = size;
        }
    }

    private final Backend backend;
    private final FrameRing ring;
    private final long tempLimit;
    private final List<Op> pending = new ArrayList<>();
    private final List<Op> inFlight = new ArrayList<>();
    private final Set<Allocation> retired = Collections.newSetFromMap(new IdentityHashMap<>());
    private final DeferredFree deferred = new DeferredFree();
    private final Runnable listener = this::onRecordingStarted;
    private final Gpu gpu = new Gpu() {
        @Override
        public Allocation allocate(long bytes) {
            return DhStaging.this.allocate(bytes);
        }

        @Override
        public boolean upload(Allocation dst, ByteBuffer src) {
            return DhStaging.this.upload(dst, src);
        }

        @Override
        public void retire(Allocation a) {
            DhStaging.this.retire(a);
        }
    };
    private long pendingBytes, tempOutstanding, refused, requeued, tempUploads;
    private boolean listening;

    DhStaging(Backend backend, long ringBytes, long tempLimit) {
        this.backend = backend;
        this.ring = new FrameRing(ringBytes);
        this.tempLimit = tempLimit;
    }

    /** Where an upload's bytes go: the ring when it has room, else a temporary buffer within the limit, else nowhere. */
    static Route route(boolean ringHasRoom, long size, long tempOutstanding, long tempLimit) {
        if (ringHasRoom) return Route.RING;
        return tempOutstanding + size <= tempLimit ? Route.TEMP : Route.REFUSE;
    }

    /** Follows the frames from now on (the frame listener frees completed staging and retired buffers). */
    public synchronized void install() {
        if (this.listening) return;
        VkFrames.addListener(this.listener);
        this.listening = true;
    }

    /** This staging as DH's buffer wrappers see it. */
    public Gpu gpu() {
        return this.gpu;
    }

    public Allocation allocate(long bytes) {
        return this.backend.allocate(bytes);
    }

    public synchronized boolean upload(Allocation dst, ByteBuffer src) {
        long size = src.remaining();
        if (size == 0) return true;
        this.dropPending(dst);
        long offset = this.ring.allocate(size, ALIGNMENT, FrameRing.PENDING);
        Op op;
        switch (route(offset >= 0, size, this.tempOutstanding, this.tempLimit)) {
            case RING -> {
                try {
                    this.backend.writeRing(offset, src);
                } catch (RuntimeException | Error e) {
                    //The region never gets a copy: given back, so it cannot stay PENDING and block the ring
                    this.ring.stamp(offset, Long.MIN_VALUE);
                    throw e;
                }
                op = new Op(dst, null, offset, size);
            }
            case TEMP -> {
                op = new Op(dst, this.backend.copyToTemp(src), 0, size);
                this.tempOutstanding += size;
                this.tempUploads++;
            }
            default -> {
                this.refused++;
                return false;
            }
        }
        this.pending.add(op);
        this.pendingBytes += size;
        return true;
    }

    public synchronized void retire(Allocation a) {
        this.dropPending(a);
        this.retired.add(a);
        this.deferred.retire(() -> {
            this.backend.free(a);
            this.retired.remove(a);
        }, this.backend.serial());
    }

    /** 2026-10-30 (audit): Visuals.frame when no visuals frame runs: the pending copies, recorded outside the main pass. Render thread. */
    public void flushOutsideVisuals() {
        synchronized (this) {
            if (this.pending.isEmpty()) return;
        }
        if (!net.vulkanmod.visuals.vk.VkContext.recordingFrame()) return;
        VkCommandBuffer cmd = net.vulkanmod.visuals.vk.VkContext.frameCommands();
        net.vulkanmod.visuals.vk.VkContext.endMainPass();
        try {
            this.flush(cmd);
        } finally {
            net.vulkanmod.visuals.vk.VkContext.resumeMainPass();
        }
    }

    /** Records the pending copies into the frame being recorded, outside any render pass. */
    public synchronized void flush(VkCommandBuffer cmd) {
        if (this.pending.isEmpty()) return;
        long serial = this.backend.serial();
        List<Copy> copies = new ArrayList<>(this.pending.size());
        for (Op op : this.pending) {
            if (op.temp == null) this.ring.stamp(op.offset, serial);
            op.serial = serial;
            this.inFlight.add(op);
            copies.add(new Copy(op.temp, op.offset, op.dst, op.size));
        }
        this.pending.clear();
        this.pendingBytes = 0;
        this.backend.record(cmd, copies);
    }

    /** A frame starts recording: copies of completed frames are done, copies of dropped frames are recorded again. */
    synchronized void onRecordingStarted() {
        long completed = this.backend.completedSerial();
        for (Iterator<Op> it = this.inFlight.iterator(); it.hasNext(); ) {
            Op op = it.next();
            if (this.backend.isDropped(op.serial)) {
                it.remove();
                if (this.retired.contains(op.dst) || this.hasPending(op.dst)) {
                    this.release(op, true);
                } else {
                    if (op.temp == null) this.ring.stamp(op.offset, FrameRing.PENDING);
                    op.serial = FrameRing.PENDING;
                    this.pending.add(op);
                    this.pendingBytes += op.size;
                    this.requeued++;
                }
            } else if (op.serial <= completed) {
                it.remove();
                this.release(op, false);
            }
        }
        this.ring.retire(completed);
        this.deferred.tick(completed);
    }

    private boolean hasPending(Allocation dst) {
        for (Op op : this.pending) if (op.dst == dst) return true;
        return false;
    }

    /** The unrecorded copies into dst: a newer upload replaces all of its contents, a retired buffer needs none. */
    private void dropPending(Allocation dst) {
        for (Iterator<Op> it = this.pending.iterator(); it.hasNext(); ) {
            Op op = it.next();
            if (op.dst == dst) {
                it.remove();
                this.pendingBytes -= op.size;
                this.release(op, true);
            }
        }
    }

    /** Gives back an op's staging; neverRan: its copy never executed (the ring region is released out of order). */
    private void release(Op op, boolean neverRan) {
        if (op.temp != null) {
            this.backend.freeTemp(op.temp);
            this.tempOutstanding -= op.size;
        } else if (neverRan) {
            this.ring.stamp(op.offset, Long.MIN_VALUE);
        }
    }

    synchronized long pendingBytes() {
        return this.pendingBytes;
    }

    synchronized int inFlightCount() {
        return this.inFlight.size();
    }

    synchronized long tempOutstanding() {
        return this.tempOutstanding;
    }

    synchronized long refused() {
        return this.refused;
    }

    synchronized long requeued() {
        return this.requeued;
    }

    /** F3 and the stats line. */
    synchronized String describe() {
        return "staging " + (this.pendingBytes >> 10) + " KiB pending, " + this.inFlight.size() + " in flight, temp " + (this.tempOutstanding >> 10)
                + " KiB (" + this.tempUploads + " uploads), refused " + this.refused + ", retired " + this.deferred.pendingCount();
    }

    /** The Vulkan side: VMA buffers, the mapped ring, and the copy commands with their barriers. */
    private static final class VkBackend implements Backend {
        /** Vertex input (DH quads as attributes or storage) and the shared quad index buffer. */
        private static final int USAGE = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        private static final int READ_STAGES = VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

        private final long ringBytes;
        private VkGpuBuffer ring;

        VkBackend(long ringBytes) {
            this.ringBytes = ringBytes;
        }

        private record Buffer(VkGpuBuffer buffer) implements Allocation {
            @Override
            public long handle() {
                return this.buffer.handle();
            }

            @Override
            public long size() {
                return this.buffer.size();
            }
        }

        @Override
        public Allocation allocate(long bytes) {
            return new Buffer(VkGpuBuffer.device(bytes, USAGE));
        }

        @Override
        public void free(Allocation a) {
            ((Buffer) a).buffer().free();
        }

        @Override
        public void writeRing(long offset, ByteBuffer src) {
            if (this.ring == null) this.ring = VkGpuBuffer.host(this.ringBytes, 0);
            copy(src, MemoryUtil.memAddress(this.ring.mapped()) + offset);
        }

        @Override
        public Object copyToTemp(ByteBuffer src) {
            VkGpuBuffer temp = VkGpuBuffer.host(src.remaining(), 0);
            try {
                copy(src, MemoryUtil.memAddress(temp.mapped()));
            } catch (RuntimeException | Error e) {
                temp.free();
                throw e;
            }
            return temp;
        }

        private static void copy(ByteBuffer src, long address) {
            if (src.isDirect()) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(src), address, src.remaining());
            } else {
                MemoryUtil.memByteBuffer(address, src.remaining()).put(src.duplicate());
            }
        }

        @Override
        public void freeTemp(Object temp) {
            ((VkGpuBuffer) temp).free();
        }

        @Override
        public void record(VkCommandBuffer cmd, List<Copy> copies) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                //Earlier frames' reads of a reused buffer, and earlier copies, finish before these copies write
                barrier(cmd, stack, READ_STAGES | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                for (Copy c : copies) {
                    long src = c.temp() == null ? this.ring.handle() : ((VkGpuBuffer) c.temp()).handle();
                    region.srcOffset(c.srcOffset()).dstOffset(0).size(c.size());
                    vkCmdCopyBuffer(cmd, src, c.dst().handle(), region);
                }
                //The copied quads and indices are visible to vertex input and shaders
                barrier(cmd, stack, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, READ_STAGES,
                        VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
            }
        }

        private static void barrier(VkCommandBuffer cmd, MemoryStack stack, int srcStages, int srcAccess, int dstStages, int dstAccess) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStages, dstStages, 0, b, null, null);
        }

        @Override
        public long serial() {
            return VkFrames.serial();
        }

        @Override
        public long completedSerial() {
            return VkFrames.completedSerial();
        }

        @Override
        public boolean isDropped(long serial) {
            return VkFrames.isDropped(serial);
        }
    }
}
