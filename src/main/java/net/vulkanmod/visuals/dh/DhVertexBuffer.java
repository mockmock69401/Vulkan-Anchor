package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.vk.VkFrames;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One of DH's vertex buffers on the device: 16-byte little-endian vertices, four per quad (u16 x, y, z, meta; u8 r, g, b, a;
 * u8 material, u8 normal, u16 texture tile), no index buffer (the quads share {@link QuadIndexBuffer}). DH creates these on
 * its worker threads and uploads and closes them on the render thread; every method is synchronized all the same. Each upload
 * keeps its vertices' bounds (Task 5b: the fork culls a buffer by its own box, DhCulling). An upload never throws into DH's task:
 * a failure switches DH drawing off (DhInternals.disable), which frees every buffer and refuses every later upload.
 * It names no DH type: DhApiVertexBuffer hands it to DH's render API, LegacyDhVertexBuffer to DH 3.3.0 to 3.3.4.
 */
public class DhVertexBuffer implements AutoCloseable {
    public static final int BYTES_PER_VERTEX = 16;

    enum Plan { NONE, REUSE, GROW }

    /** The smallest and largest u16 position of a buffer's vertices along each axis, relative to the container's corner (no micro offset). */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        /**
         * Of the first count vertices of data (from its position; its position and order are left as given); null for none. It runs on
         * every DH upload, on the render thread: a direct buffer on a little-endian machine (DH's) is read a vertex at a time as one
         * native word (x, y, z, meta), anything else through the buffer.
         */
        static Bounds of(ByteBuffer data, int count) {
            if (count <= 0) return null;
            int minX = 0xFFFF, minY = 0xFFFF, minZ = 0xFFFF, maxX = 0, maxY = 0, maxZ = 0;
            if (data.isDirect() && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                if ((long) count * BYTES_PER_VERTEX > data.remaining()) throw new IndexOutOfBoundsException(count + " vertices past the buffer");
                long address = MemoryUtil.memAddress(data);
                for (int i = 0; i < count; i++) {
                    long w = MemoryUtil.memGetLong(address + (long) i * BYTES_PER_VERTEX);
                    int x = (int) w & 0xFFFF, y = (int) (w >>> 16) & 0xFFFF, z = (int) (w >>> 32) & 0xFFFF;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            } else {
                ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int base = b.position();
                for (int i = 0; i < count; i++) {
                    int at = base + i * BYTES_PER_VERTEX;
                    int x = b.getShort(at) & 0xFFFF, y = b.getShort(at + 2) & 0xFFFF, z = b.getShort(at + 4) & 0xFFFF;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    /** Every buffer not yet closed, so the world exit can free them all (DH's own closes afterwards are no-ops). */
    static final Set<DhVertexBuffer> LIVE = ConcurrentHashMap.newKeySet();
    /** The buffers freeAll handed to the render thread; closed when the next frame starts recording (closeHandedOver). */
    private static final Queue<DhVertexBuffer> HANDED_OVER = new ConcurrentLinkedQueue<>();
    private static final Runnable HAND_OVER = DhVertexBuffer::closeHandedOver;
    private static final AtomicBoolean HAND_OVER_INSTALLED = new AtomicBoolean();
    private static final AtomicLong LIVE_BYTES = new AtomicLong();
    private static final AtomicLong UPLOADS = new AtomicLong(), UPLOAD_BYTES = new AtomicLong(), REUPLOADS = new AtomicLong(),
            INDEX_UPLOADS_IGNORED = new AtomicLong(), CLOSED = new AtomicLong(), REFUSED = new AtomicLong();
    private static final AtomicBoolean INDEX_WARNED = new AtomicBoolean(), ALLOCATION_WARNED = new AtomicBoolean(), UPLOAD_WARNED = new AtomicBoolean();
    /** 2026-10-30 (audit): upload failures in a row; this many turn DH off (one did before, for the session). */
    static final int UPLOAD_FAILURES_ALLOWED = 32;
    private static final java.util.concurrent.atomic.AtomicInteger UPLOAD_FAILURES = new java.util.concurrent.atomic.AtomicInteger();

    static void resetUploadFailuresForTest() {
        UPLOAD_FAILURES.set(0);
        UPLOAD_WARNED.set(false);
    }

    private final String name;
    private final DhStaging.Gpu gpu;
    private DhStaging.Allocation buffer;
    private int vertexCount;
    /** The bounds of the vertices quadCount counts; null when there are none. */
    private Bounds bounds;
    private boolean uploadedBefore, closed;

    public DhVertexBuffer(String name, DhStaging.Gpu gpu) {
        this.name = name;
        this.gpu = gpu;
        LIVE.add(this);
    }

    /** What an upload of bytes does to a buffer of current bytes: nothing to keep, overwrite in place, or a larger buffer. */
    static Plan plan(long bytes, long current) {
        if (bytes <= 0) return Plan.NONE;
        return bytes <= current ? Plan.REUSE : Plan.GROW;
    }

    static long uploadBytes(int vertexCount) {
        return (long) vertexCount * BYTES_PER_VERTEX;
    }

    /**
     * Render thread (DH's task queue). DH's buffer is read from its position and left as given. Nothing is thrown into DH: a failure
     * leaves this buffer empty and switches DH drawing off; once off (DhInternals.ok false) an upload allocates nothing.
     */
    public synchronized void uploadVertexBuffer(ByteBuffer data, int vertexCount) {
        if (this.closed) return;
        if (!DhInternals.ok()) {
            this.empty();
            return;
        }
        long start = FrameRecorder.ON ? System.nanoTime() : 0;
        try {
            this.upload(data, vertexCount);
            UPLOAD_FAILURES.set(0);
            if (FrameRecorder.ON) {
                FrameRecorder.time(FrameRecorder.DH_UPLOAD, start);
                FrameRecorder.add(FrameRecorder.DH_UPLOADS, 1);
                FrameRecorder.add(FrameRecorder.DH_KB, uploadBytes(this.vertexCount) >> 10);
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            //a passing failure (a temporary staging buffer out of memory) leaves this section out; only many in a row turn DH off
            this.empty();
            REFUSED.incrementAndGet();
            if (UPLOAD_FAILURES.incrementAndGet() >= UPLOAD_FAILURES_ALLOWED) {
                DhInternals.disable("vertex buffer uploads failed " + UPLOAD_FAILURES_ALLOWED + " times in a row (" + e + ")", e);
            } else if (UPLOAD_WARNED.compareAndSet(false, true)) {
                VisualsLog.warn("Distant Horizons: a vertex buffer upload failed (" + e + "); that LOD section is not drawn");
            }
        }
    }

    /** Nothing drawable and no device memory kept. */
    private void empty() {
        this.vertexCount = 0;
        this.bounds = null;
        this.dropBuffer();
    }

    private void upload(ByteBuffer data, int vertexCount) {
        int count = (int) Math.min(Math.max(vertexCount, 0), data.remaining() / BYTES_PER_VERTEX);
        long bytes = uploadBytes(count);
        UPLOADS.incrementAndGet();
        if (this.uploadedBefore) REUPLOADS.incrementAndGet();
        this.uploadedBefore = true;
        this.bounds = null;
        switch (plan(bytes, this.buffer == null ? 0 : this.buffer.size())) {
            case NONE -> {
                this.vertexCount = 0;
                return;
            }
            case GROW -> {
                //Nothing is drawable until the new contents are in; a failed allocation must not reach DH's render-thread task
                this.vertexCount = 0;
                this.dropBuffer();
                try {
                    this.buffer = this.gpu.allocate(bytes);
                    if (FrameRecorder.ON) FrameRecorder.add(FrameRecorder.DH_ALLOC, 1);
                } catch (RuntimeException e) {
                    REFUSED.incrementAndGet();
                    if (ALLOCATION_WARNED.compareAndSet(false, true)) {
                        VisualsLog.warn("Distant Horizons: a vertex buffer could not be allocated (" + e.getMessage() + "); that LOD section is not drawn");
                    }
                    return;
                }
                LIVE_BYTES.addAndGet(bytes);
            }
            case REUSE -> {
            }
        }
        ByteBuffer slice = data.duplicate();
        slice.limit(slice.position() + (int) bytes);
        if (this.gpu.upload(this.buffer, slice)) {
            this.vertexCount = count;
            this.bounds = Bounds.of(data, count);
            UPLOAD_BYTES.addAndGet(bytes);
        } else {
            this.vertexCount = 0;
            REFUSED.incrementAndGet();
        }
    }

    /** DH builds no index buffer for an API renderer; the quads share {@link QuadIndexBuffer}. */
    public void uploadIndexBuffer(ByteBuffer data, int vertexCount) {
        INDEX_UPLOADS_IGNORED.incrementAndGet();
        if (INDEX_WARNED.compareAndSet(false, true)) {
            VisualsLog.info("Distant Horizons: an index buffer upload was ignored (the quads share one index buffer)");
        }
    }

    public synchronized int getVertexCount() {
        return this.vertexCount;
    }

    /**
     * The quads to draw. Drawable only in commands recorded after {@link DhStaging#flush} in the same frame: DH uploads and calls
     * its terrain renderer before the visuals frame records the copies.
     */
    public synchronized int quadCount() {
        return this.vertexCount / 4;
    }

    /** The bounds of the vertices quadCount counts (the same upload), or null when there are none. Drawable as quadCount is. */
    public synchronized Bounds bounds() {
        return this.closed ? null : this.bounds;
    }

    /**
     * The device buffer, or 0 when closed or never filled. Drawable only in commands recorded after {@link DhStaging#flush} in
     * the same frame: until then its latest upload may not be copied in.
     */
    public synchronized long handle() {
        return this.closed || this.buffer == null ? 0 : this.buffer.handle();
    }

    public String name() {
        return this.name;
    }

    /** Hands the device buffer to the deferred free; safe to call again. */
    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        LIVE.remove(this);
        this.dropBuffer();
        this.vertexCount = 0;
        this.bounds = null;
        CLOSED.incrementAndGet();
    }

    private void dropBuffer() {
        if (this.buffer == null) return;
        LIVE_BYTES.addAndGet(-this.buffer.size());
        this.gpu.retire(this.buffer);
        this.buffer = null;
    }

    /**
     * Frees every buffer live now (the world exit, or DH drawing switched off by a failure). Any thread (DH's world unload may come
     * from a thread of its own): the buffers are handed to the render thread, which closes them when the next frame starts recording,
     * so no frame being recorded loses a buffer it draws. Buffers made after this call are left alone; idempotent.
     */
    public static void freeAll() {
        HANDED_OVER.addAll(LIVE);
    }

    /** Runs the closes freeAll hands over at the start of every frame's recording (VkFrames listener); idempotent. */
    static void installHandOver() {
        if (HAND_OVER_INSTALLED.compareAndSet(false, true)) VkFrames.addListener(HAND_OVER);
    }

    /** Render thread, when a frame starts recording: closes the buffers freeAll handed over. */
    static void closeHandedOver() {
        for (DhVertexBuffer b; (b = HANDED_OVER.poll()) != null; ) b.close();
    }

    /** Tests: freeAll and its closes at once. */
    static void closeAllNow() {
        freeAll();
        closeHandedOver();
    }

    public static int liveCount() {
        return LIVE.size();
    }

    public static long liveBytes() {
        return LIVE_BYTES.get();
    }

    static long uploads() {
        return UPLOADS.get();
    }

    static long uploadedBytes() {
        return UPLOAD_BYTES.get();
    }

    /** Uploads into a wrapper that had been uploaded to before. */
    static long reuploads() {
        return REUPLOADS.get();
    }

    static long closedCount() {
        return CLOSED.get();
    }

    /** Uploads refused by the staging limit or lost to a failed allocation. */
    static long refusedUploads() {
        return REFUSED.get();
    }

    static long indexUploadsIgnored() {
        return INDEX_UPLOADS_IGNORED.get();
    }
}
