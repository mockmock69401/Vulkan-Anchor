package net.vulkanmod.visuals.dh;

/**
 * A DH terrain container's per-container data: its minimum corner in blocks, which the quads' u16 positions are relative to, and
 * its section's detail level (-1 when unknown), which the culling frustum's answers are found by.
 * DH creates one per container on its worker threads and never calls tryUpload itself (its own GL renderer does, before a
 * container's first draw), so the terrain renderer calls it. It names no DH type: DhApiContainerUniform hands it to DH's render
 * API, LegacyDhContainerUniform to DH 3.3.0 to 3.3.4 (both containers have the fields DhInternals reads).
 */
public class DhContainerUniform implements AutoCloseable {
    private volatile long x, y, z;
    private volatile int detailLevel = -1;
    private volatile boolean valid, closed;

    /** Reads the corner of DH's container (whichever DH's container class it is). */
    public void tryUpload(Object container) {
        if (this.closed || this.valid) return;
        long[] corner = DhInternals.minCornerBlockPos(container);
        if (corner == null) return;
        this.x = corner[0];
        this.y = corner[1];
        this.z = corner[2];
        this.detailLevel = DhInternals.detailLevel(container);
        this.valid = true;
    }

    /** The corner was read. */
    public boolean valid() {
        return this.valid && !this.closed;
    }

    public long x() {
        return this.x;
    }

    public long y() {
        return this.y;
    }

    public long z() {
        return this.z;
    }

    /** The section's detail level, -1 when DH's section key could not be read. */
    public int detailLevel() {
        return this.detailLevel;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
