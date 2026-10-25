package net.vulkanmod.visuals.render;

import java.util.function.LongPredicate;

/**
 * W9: whether the TAA history can be blended this frame. It can only when it was written by the frame just before (a frame without
 * TAA in between leaves it stale), that frame ran on the GPU (a dropped frame never wrote it), the size has not changed and
 * nothing discarded it (VkTaa.skipped). It also keeps the camera of the frame that wrote it, which the reprojection needs.
 */
final class TaaHistory {
    private int width = -1, height = -1;
    private long serial = -1;
    private TaaMath.Camera camera;

    /** A new screen size discards the history. */
    void resize(int width, int height) {
        if (width == this.width && height == this.height) return;
        this.width = width;
        this.height = height;
        this.discard();
    }

    void discard() {
        this.serial = -1;
        this.camera = null;
    }

    /** The frame with this serial wrote the history from this camera. */
    void written(long serial, TaaMath.Camera camera) {
        this.serial = serial;
        this.camera = camera;
    }

    /** Whether the frame now being recorded (serial now) may blend the history. */
    boolean usable(long now, LongPredicate dropped) {
        return this.serial >= 0 && this.camera != null && this.serial == now - 1 && !dropped.test(this.serial);
    }

    /** The camera of the frame that wrote the history (null: none). */
    TaaMath.Camera camera() {
        return this.camera;
    }
}
