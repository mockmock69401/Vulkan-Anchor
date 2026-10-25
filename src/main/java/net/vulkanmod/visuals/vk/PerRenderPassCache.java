package net.vulkanmod.visuals.vk;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.function.LongUnaryOperator;

/**
 * One pipeline per render pass compatibility class, never destroyed while the game runs: frames still in flight may
 * reference it. Reforged replaces its main render pass on resize; compatible passes (same attachment formats) share a
 * pipeline, so the cache does not grow with every resize. the visuals' own passes are keyed by their handle.
 */
public final class PerRenderPassCache {
    private final Long2LongOpenHashMap pipelines = new Long2LongOpenHashMap();
    private final LongUnaryOperator build;

    public PerRenderPassCache(LongUnaryOperator build) {
        this.build = build;
    }

    public long get(long renderPass) {
        return this.get(renderPass, renderPass);
    }

    /** The pipeline for passes compatible with renderPass, built against renderPass the first time. */
    public long get(long compatibilityKey, long renderPass) {
        long pipeline = this.pipelines.get(compatibilityKey);
        if (pipeline == 0) {
            pipeline = this.build.applyAsLong(renderPass);
            this.pipelines.put(compatibilityKey, pipeline);
        }
        return pipeline;
    }

    /** Render pass compatibility for single colour + depth attachment passes at one sample (Reforged's main passes). */
    public static long compatibilityKey(int colourFormat, int depthFormat) {
        return ((long) colourFormat << 32) | (depthFormat & 0xFFFFFFFFL);
    }
}
