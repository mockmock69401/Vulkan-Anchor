package dev.vulkanfix.compat;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Buffers handed to a draw that has not returned yet. Reforged's BufferUploader overwrites release the RenderedBuffer
 * only after recording the draw, so a draw that throws never releases it; whatever is still here when the frame ends
 * came from such a draw. Render thread only.
 */
public final class AbandonedDraws<T> {
    private final ArrayDeque<T> inFlight = new ArrayDeque<>();

    public void enter(T buffer) {
        this.inFlight.push(buffer);
    }

    /** Forgets {@code buffer}, which returned; draws nested in it that threw stay tracked. */
    public void exit(T buffer) {
        for (Iterator<T> it = this.inFlight.iterator(); it.hasNext(); ) {
            if (it.next() == buffer) {
                it.remove();
                return;
            }
        }
    }

    /** Hands every draw that never returned to {@code release}, oldest first, and forgets it. */
    public void drain(Consumer<T> release) {
        while (!this.inFlight.isEmpty()) {
            release.accept(this.inFlight.removeLast());
        }
    }
}
