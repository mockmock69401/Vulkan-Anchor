package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkSamplers;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vanilla's main depth right before and right after its translucent terrain (design 2026-10-11 P7e-1 3.2), copied by a
 * compute pass (depth_capture.comp) into R32F images the lighting pass compares: where they differ a translucent surface
 * lies over an opaque one. Entities and block entities are drawn before the first copy, particles and clouds after the
 * second. The pair counts only when both were taken in this frame at the main target's size. 2026-10-29: with the AFTER depth the
 * main colour too (colour_capture.comp, captureColour), which the composite compares with the final colour to find what was
 * drawn over the translucent terrain afterwards (rain and snow particles) and leave it unreflected.
 */
public final class VkDepthCapture {
    static final int FORMAT = VK_FORMAT_R32_SFLOAT;
    static final int COLOUR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    public static final int BEFORE = 0, AFTER = 1;
    private static final int DST = 0, SRC = 1;

    /** The frame each copy was taken in. */
    static final class Serials {
        private final long[] taken = {Long.MIN_VALUE, Long.MIN_VALUE};

        void taken(int slot, long serial) {
            this.taken[slot] = serial;
        }

        boolean both(long serial) {
            return this.taken[BEFORE] == serial && this.taken[AFTER] == serial;
        }

        void clear() {
            this.taken[BEFORE] = Long.MIN_VALUE;
            this.taken[AFTER] = Long.MIN_VALUE;
        }
    }

    private final DeferredFree deferred;
    private final VkComputeProgram program;
    private final VkComputeProgram colourProgram;
    private final long nearest;
    private final Serials serials = new Serials();
    private VkImage2D before, after, colourAfter;
    private long colourSerial = Long.MIN_VALUE;
    private int width, height;

    public VkDepthCapture(DeferredFree deferred) {
        this.deferred = deferred;
        VkComputeProgram program = null, colourProgram = null;
        long nearest = 0;
        try {
            program = VkComputeProgram.of("postDepthCapture");
            if (program.meta().pushSize() != 0) throw new IllegalStateException("postDepthCapture: expected no loose uniforms");
            colourProgram = VkComputeProgram.of("postColourCapture");
            nearest = VkSamplers.nearestClamp();
        } catch (RuntimeException | LinkageError e) {
            if (program != null) program.free();
            if (colourProgram != null) colourProgram.free();
            if (nearest != 0) VkSamplers.free(nearest);
            throw e;
        }
        this.program = program;
        this.colourProgram = colourProgram;
        this.nearest = nearest;
    }

    /** Recreates the images at a new size; the old ones are freed once the frames that may use them have completed. */
    public void ensureSize(int width, int height) {
        if (this.before != null && this.width == width && this.height == height) return;
        if (this.before != null) {
            VkImage2D oldBefore = this.before, oldAfter = this.after, oldColour = this.colourAfter;
            this.deferred.retire(() -> {
                oldBefore.free();
                oldAfter.free();
                oldColour.free();
            }, VkFrames.serial());
            this.before = null;
            this.after = null;
            this.colourAfter = null;
        }
        this.colourSerial = Long.MIN_VALUE;
        this.serials.clear();
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        VkImage2D before = VkImage2D.create(FORMAT, width, height, 1, usage);
        VkImage2D after = null, colour;
        try {
            after = VkImage2D.create(FORMAT, width, height, 1, usage);
            colour = VkImage2D.create(COLOUR_FORMAT, width, height, 1, usage);
        } catch (RuntimeException e) {
            before.free();
            if (after != null) after.free();
            throw e;
        }
        this.before = before;
        this.after = after;
        this.colourAfter = colour;
        this.width = width;
        this.height = height;
    }

    /**
     * Outside a render pass, after ensureSize, with the main depth readable by compute: copies it into slot's image, left
     * readable by the lighting pass. Last frame's lighting pass read the image; the copy rewrites it whole.
     */
    public void capture(VkCommandBuffer cmd, int slot, long mainDepthView) {
        VkImage2D image = this.image(slot);
        image.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        this.program.dispatchWithPush(cmd, new VkBindings().storageImage(DST, image.view()).sampledView(SRC, mainDepthView, this.nearest), null,
                (this.width + 7) / 8, (this.height + 7) / 8, 1);
        image.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.serials.taken(slot, VkFrames.serial());
    }

    /**
     * 2026-10-29: with the AFTER depth, outside a render pass, the main colour readable by compute: copies it into the colour image,
     * left readable by the composite.
     */
    public void captureColour(VkCommandBuffer cmd, long mainColourView) {
        this.colourAfter.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        this.colourProgram.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.colourAfter.view()).sampledView(SRC, mainColourView, this.nearest),
                null, (this.width + 7) / 8, (this.height + 7) / 8, 1);
        this.colourAfter.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.colourSerial = VkFrames.serial();
    }

    /** The colour copy's view when it was taken in this frame at this size, else 0. */
    public long colourAfter(long serial, int width, int height) {
        return this.colourAfter != null && this.width == width && this.height == height && this.colourSerial == serial ? this.colourAfter.sampledView() : 0;
    }

    /** Both copies were taken in this frame at this size, so the lighting pass may read them. */
    public boolean captured(long serial, int width, int height) {
        return this.before != null && this.width == width && this.height == height && this.serials.both(serial);
    }

    public VkImage2D image(int slot) {
        return slot == BEFORE ? this.before : this.after;
    }

    /** Call when a frame starts recording: transitions of a dropped frame are undone. */
    public void tick() {
        if (this.before == null) return;
        this.before.tick();
        this.after.tick();
        this.colourAfter.tick();
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        this.program.free();
        this.colourProgram.free();
        VkSamplers.free(this.nearest);
        if (this.before != null) {
            this.before.free();
            this.after.free();
            this.colourAfter.free();
        }
        this.before = null;
        this.after = null;
        this.colourAfter = null;
    }
}
