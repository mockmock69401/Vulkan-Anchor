package net.vulkanmod.visuals.framegen;

/**
 * Which pacer timeline value the render thread must wait for before it overwrites a capture slot (FG design 3.1). The pacer reserves a
 * value for every submit that reads a slot (its world and UI images) and signals exactly that value; the render thread's submit that
 * writes the slot waits on the last value reserved for it. Values only grow, one counter over all slots, so the pacer timeline is
 * signalled in increasing order.
 *
 * The pacer reserves and submits inside one synchronized block on this ledger (FramePacer), so readValue never returns a value whose
 * signal has not been submitted yet: a wait-before-signal on the same queue could stall that queue for good.
 */
public final class SlotLedger {
    private final long[] reads;
    private long last;
    //2026-10-30 (audit): the slot the render thread last asked to write (readValue), -1 before any
    private int lastWritten = -1;

    public SlotLedger(int slots) {
        this.reads = new long[slots];
    }

    /** Pacer thread, before recording reads of slot: the pacer timeline value its next submit will signal. */
    public synchronized long reserveRead(int slot) {
        this.reads[slot] = ++this.last;
        return this.last;
    }

    /** Render thread: the value its submit waits on before writing slot (0, always reached, when the pacer never read it). */
    public synchronized long readValue(int slot) {
        this.lastWritten = slot;
        return this.reads[slot];
    }

    /**
     * 2026-10-30 (audit): the slot the render thread's latest submit writes. Under this monitor a pacer read of any other slot is
     * still safe to reserve: the render thread's next write of it asks readValue afterwards and waits for that read.
     */
    public synchronized int lastWrittenSlot() {
        return this.lastWritten;
    }

    /** The highest value reserved so far: once the pacer timeline reaches it, the pacer has no GPU work left. */
    public synchronized long lastValue() {
        return this.last;
    }
}
