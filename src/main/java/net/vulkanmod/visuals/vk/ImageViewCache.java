package net.vulkanmod.visuals.vk;

import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/**
 * One view per image object, for images the visuals samples but does not own (Reforged's main depth, replaced on resize):
 * a new image object gets a new view, and the old view is retired, never destroyed while a frame may use it.
 */
public final class ImageViewCache {
    private final ToLongFunction<Object> create;
    private final LongConsumer destroy;
    private Object image;
    private long view;

    public ImageViewCache(ToLongFunction<Object> create, LongConsumer destroy) {
        this.create = create;
        this.destroy = destroy;
    }

    public long get(Object image, DeferredFree deferred, long serial) {
        if (image != this.image) {
            //2026-10-30 (audit): the old pair is forgotten before create, so a create that throws cannot retire the same view twice
            if (this.image != null) {
                long old = this.view;
                this.image = null;
                this.view = 0;
                deferred.retire(() -> this.destroy.accept(old), serial);
            }
            this.view = this.create.applyAsLong(image);
            this.image = image;
        }
        return this.view;
    }

    /** Only once no submitted frame can still use the view. */
    public void free() {
        if (this.image != null) {
            this.destroy.accept(this.view);
            this.image = null;
            this.view = 0;
        }
    }
}
