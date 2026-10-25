package dev.vulkanfix.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.vulkanfix.mixin.reforged.RenderedBufferAccessor;

/**
 * Releases the RenderedBuffer of a Reforged draw that threw. Left unreleased, its BufferBuilder keeps a pending batch
 * and never clears again, and every later batch is appended until the buffer overflows at 2 GB.
 */
public final class ReforgedDrawRelease {
    public static final AbandonedDraws<BufferBuilder.RenderedBuffer> DRAWS = new AbandonedDraws<>();
    private static final int WARNINGS = 5;
    private static int warned;

    private ReforgedDrawRelease() {
    }

    public static void releaseAbandoned() {
        DRAWS.drain(ReforgedDrawRelease::release);
    }

    private static void release(BufferBuilder.RenderedBuffer buffer) {
        if (((RenderedBufferAccessor) (Object) buffer).vulkanfix$released()) {
            return;
        }
        buffer.release();
        if (warned < WARNINGS) {
            warned++;
            BufferBuilder.DrawState state = buffer.drawState();
            VrGuard.LOGGER.warn("[vulkanfix] A Reforged draw threw before releasing its vertex data ({} {}, {} vertices);"
                    + " released it at the end of the frame", state.mode(), state.format(), state.vertexCount());
        }
    }
}
