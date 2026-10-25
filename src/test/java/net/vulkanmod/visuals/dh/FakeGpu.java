package net.vulkanmod.visuals.dh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** A DhStaging.Gpu without a device: records what was allocated, uploaded and retired. */
final class FakeGpu implements DhStaging.Gpu {
    record Buffer(long handle, long size) implements DhStaging.Allocation {}

    record Upload(Buffer dst, long bytes, byte[] data) {}

    final List<Buffer> allocated = new ArrayList<>();
    final List<Upload> uploads = new ArrayList<>();
    final List<DhStaging.Allocation> retired = new ArrayList<>();
    boolean refuse, failAllocate, failUpload;
    private long nextHandle = 1;

    @Override
    public DhStaging.Allocation allocate(long bytes) {
        if (this.failAllocate) throw new IllegalStateException("vmaCreateBuffer failed: -2");
        Buffer b = new Buffer(this.nextHandle++, bytes);
        this.allocated.add(b);
        return b;
    }

    @Override
    public boolean upload(DhStaging.Allocation dst, ByteBuffer src) {
        if (this.failUpload) throw new IllegalStateException("vkMapMemory failed: -5");
        if (this.refuse) return false;
        byte[] data = new byte[src.remaining()];
        src.duplicate().get(data);
        this.uploads.add(new Upload((Buffer) dst, data.length, data));
        return true;
    }

    @Override
    public void retire(DhStaging.Allocation a) {
        this.retired.add(a);
    }
}
