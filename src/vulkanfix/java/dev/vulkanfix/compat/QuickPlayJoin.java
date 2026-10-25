package dev.vulkanfix.compat;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Schedules quick play's world join on Minecraft's task queue. Called from inside a frame with the reload already
 * done, the join would otherwise run inline and its nested runTick frames would end the frame still being drawn.
 */
public final class QuickPlayJoin {
    private QuickPlayJoin() {
    }

    /** Runs {@code join} once {@code reload} is done, always through {@code taskQueue}, never inline. */
    public static CompletableFuture<Void> queued(CompletableFuture<?> reload, Runnable join, Consumer<Runnable> taskQueue) {
        return reload.thenRunAsync(join, taskQueue::accept);
    }
}
