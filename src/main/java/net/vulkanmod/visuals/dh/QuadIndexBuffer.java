package net.vulkanmod.visuals.dh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * The index buffer every DH quad is drawn with: DH sends four vertices per quad and no indices, so quad q is the triangles
 * (4q, 4q+1, 4q+2) and (4q+2, 4q+3, 4q). u32 indices (one DH buffer holds up to 131072 vertices). It holds at least the
 * largest quad count asked for, doubling when that grows. Render thread.
 */
public final class QuadIndexBuffer {
    public static final int INITIAL_QUADS = 4096;
    private static final int[] PATTERN = {0, 1, 2, 2, 3, 0};

    private final DhStaging.Gpu gpu;
    private DhStaging.Allocation buffer;
    private int capacity;

    public QuadIndexBuffer(DhStaging.Gpu gpu) {
        this.gpu = gpu;
    }

    public static int indexCount(int quadCount) {
        return quadCount * 6;
    }

    /** The indices of quadCount quads, put at out's position. */
    public static void fill(IntBuffer out, int quadCount) {
        for (int q = 0; q < quadCount; q++) {
            int base = q * 4;
            for (int i : PATTERN) out.put(base + i);
        }
    }

    /** The capacity that holds needed quads: the current one, or doubled (from INITIAL_QUADS) until it does. */
    static int grownCapacity(int current, int needed) {
        int c = current == 0 ? INITIAL_QUADS : current;
        while (c < needed) c *= 2;
        return c;
    }

    /** Makes room for quads quads; true when the buffer was replaced (the old one goes to the deferred free). */
    public boolean ensure(int quads) {
        if (this.buffer != null && quads <= this.capacity) return false;
        int grown = grownCapacity(this.capacity, quads);
        ByteBuffer data = ByteBuffer.allocateDirect(indexCount(grown) * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        fill(data.asIntBuffer(), grown);
        DhStaging.Allocation next = this.gpu.allocate(data.capacity());
        if (!this.gpu.upload(next, data)) {
            this.gpu.retire(next);
            return false;
        }
        if (this.buffer != null) this.gpu.retire(this.buffer);
        this.buffer = next;
        this.capacity = grown;
        return true;
    }

    public int capacityQuads() {
        return this.capacity;
    }

    /** The device buffer, or 0 before the first ensure and after free. */
    public long handle() {
        return this.buffer == null ? 0 : this.buffer.handle();
    }

    /** Safe to call again. */
    public void free() {
        if (this.buffer != null) this.gpu.retire(this.buffer);
        this.buffer = null;
        this.capacity = 0;
    }
}
