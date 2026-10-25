package net.vulkanmod.visuals.framegen;

import java.lang.invoke.VarHandle;
import java.util.function.Consumer;

/**
 * What the pacer did per present or skipped generated frame (FG design 3.2, the gate's measurement): a single-producer ring the pacer
 * thread records into and the render thread drains, without a lock. A record is {kind (0 real, 1 generated), renderValue, scheduledNs,
 * callNs, acquireWaitNs, decisionOrdinal, inputNs, gpuNs}. When the ring is full the oldest records are overwritten.
 *
 * Two volatile counters order it: started (records begun) is raised before a record's fields are written, written (records complete)
 * after. A drained record whose slot the producer may have begun to overwrite meanwhile (started past it by a full ring) is discarded.
 */
public final class PresentLog {
    public static final int CAPACITY = 4096;
    public static final int FIELDS = 8;
    public static final int KIND_REAL = 0, KIND_GENERATED = 1;

    private final long[] ring = new long[CAPACITY * FIELDS];
    private volatile long started;
    private volatile long written;
    /** Consumer side only. */
    private long read;

    /** Pacer thread (the only producer). */
    public void record(long kind, long renderValue, long scheduledNs, long callNs, long acquireWaitNs, long decisionOrdinal, long inputNs,
                       long gpuNs) {
        long n = this.written;
        this.started = n + 1;
        //the fields must not be written before started says this slot is being replaced
        VarHandle.storeStoreFence();
        int at = (int) (n % CAPACITY) * FIELDS;
        long[] r = this.ring;
        r[at] = kind;
        r[at + 1] = renderValue;
        r[at + 2] = scheduledNs;
        r[at + 3] = callNs;
        r[at + 4] = acquireWaitNs;
        r[at + 5] = decisionOrdinal;
        r[at + 6] = inputNs;
        r[at + 7] = gpuNs;
        this.written = n + 1;
    }

    /** Render thread (the only consumer): every record since the last drain, oldest first, each once. */
    public void drain(Consumer<long[]> sink) {
        long end = this.written;
        long from = Math.max(this.read, end - CAPACITY);
        int count = (int) (end - from);
        long[][] copies = new long[count][];
        for (int i = 0; i < count; i++) {
            int at = (int) ((from + i) % CAPACITY) * FIELDS;
            long[] c = new long[FIELDS];
            System.arraycopy(this.ring, at, c, 0, FIELDS);
            copies[i] = c;
        }
        //records the producer began to overwrite while they were copied may be torn: drop them
        VarHandle.loadLoadFence();
        long firstSafe = this.started - CAPACITY;
        for (int i = 0; i < count; i++) {
            if (from + i >= firstSafe) sink.accept(copies[i]);
        }
        this.read = end;
    }
}
