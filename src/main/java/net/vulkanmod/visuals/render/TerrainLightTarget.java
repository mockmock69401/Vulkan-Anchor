package net.vulkanmod.visuals.render;

import net.vulkanmod.vulkan.shader.descriptor.StorageImages;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkImage2D;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The terrain light (terrain light design 2026-10-23): a R32_UINT storage image the
 * size of the main target, which the terrain fragment shaders fill with each pixel's nearest terrain fragment (terrain_light.glsl,
 * TerrainLightMath) while LightOut is 1, and the composite reads instead of the light volume where its depth is the main depth's.
 * <p>
 * The terrain draws before the visuals' frame, so the image is cleared for the next frame after the post-process has read it:
 * a frame's terrain writes only when the previous frame cleared it at this frame's size (LightOut), and the post-process reads it
 * only then. It stays in GENERAL.
 */
public final class TerrainLightTarget {
    /** The writers' storage images (terrain.json, the core entity, block model and particle JSONs): the light and the albedo. */
    public static final String NAME = "TerrainLight", ALBEDO_NAME = "TerrainAlbedo";
    public static final int FORMAT = VK_FORMAT_R32_UINT;
    private static final int FRAGMENT_AND_COMPUTE = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

    private static TerrainLightTarget current;

    static {
        StorageImages.register(NAME, () -> current != null && current.image != null ? current.image.view() : 0);
        StorageImages.register(ALBEDO_NAME, () -> current != null && current.albedo != null ? current.albedo.view() : 0);
    }

    private final DeferredFree deferred;
    private VkImage2D image, albedo;
    private long clearedSerial = Long.MIN_VALUE;
    private int clearedWidth, clearedHeight;

    public TerrainLightTarget(DeferredFree deferred) {
        this.deferred = deferred;
        current = this;
    }

    /** terrain.json's LightOut: 1 while this frame's terrain may write (the last frame cleared the image for it). */
    public static float lightOut() {
        TerrainLightTarget t = current;
        return t != null && t.clearedSerial == VkFrames.serial() - 1 && !VkFrames.isDropped(t.clearedSerial) ? 1 : 0;
    }

    /** At the post-process, before the reads: whether this frame's terrain filled the image for a main target of this size. */
    public boolean filled(int width, int height) {
        return this.image != null && lightOut() > 0 && this.clearedWidth == width && this.clearedHeight == height;
    }

    /** Outside a render pass, before the compute reads a filled image: the terrain's fragment writes become visible to compute. */
    public void toCompute(VkCommandBuffer cmd) {
        for (VkImage2D i : new VkImage2D[]{this.image, this.albedo}) {
            i.transition(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
    }

    /** The view the compute binds (the image when filled; else any GENERAL view, which the uniforms say not to read). */
    public long view() {
        return this.image != null ? this.image.view() : StorageImages.view(NAME);
    }

    /** The albedo's view, as view(). */
    public long albedoView() {
        return this.albedo != null ? this.albedo.view() : StorageImages.view(ALBEDO_NAME);
    }

    /**
     * Outside a render pass, after this frame's reads (or when nothing reads it): with on, clears the image at the main target's size
     * for the next frame's terrain, recreating it on a resize; without, the terrain stops writing (nothing is cleared).
     */
    public void prepareNext(VkCommandBuffer cmd, boolean on, int width, int height) {
        if (!on || width <= 0 || height <= 0) {
            this.clearedSerial = Long.MIN_VALUE;
            return;
        }
        if (this.image == null || this.image.width != width || this.image.height != height) {
            for (VkImage2D old : new VkImage2D[]{this.image, this.albedo}) {
                if (old != null) this.deferred.retire(old::free, VkFrames.serial());
            }
            this.image = null;
            this.albedo = null;
            this.image = create(cmd, width, height);
            this.albedo = create(cmd, width, height);
        } else {
            for (VkImage2D i : new VkImage2D[]{this.image, this.albedo}) {
                i.transition(cmd, VK_IMAGE_LAYOUT_GENERAL, FRAGMENT_AND_COMPUTE, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            }
        }
        clear(cmd, this.image, TerrainLightMath.NONE);
        clear(cmd, this.albedo, 0);
        for (VkImage2D i : new VkImage2D[]{this.image, this.albedo}) {
            i.transition(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        }
        this.clearedSerial = VkFrames.serial();
        this.clearedWidth = width;
        this.clearedHeight = height;
    }

    private static VkImage2D create(VkCommandBuffer cmd, int width, int height) {
        VkImage2D i = VkImage2D.create(FORMAT, width, height, 1, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        i.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        return i;
    }

    private static void clear(VkCommandBuffer cmd, VkImage2D i, int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue v = VkClearColorValue.calloc(stack).uint32(0, value).uint32(1, 0).uint32(2, 0).uint32(3, 0);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            vkCmdClearColorImage(cmd, i.handle(), VK_IMAGE_LAYOUT_GENERAL, v, range);
        }
    }

    /** Call when a frame starts recording (VkImage2D.tick). */
    public void tick() {
        if (this.image != null) this.image.tick();
        if (this.albedo != null) this.albedo.tick();
    }

    public void free() {
        if (current == this) current = null;
        for (VkImage2D old : new VkImage2D[]{this.image, this.albedo}) {
            if (old != null) this.deferred.retire(old::free, VkFrames.serial());
        }
        this.image = null;
        this.albedo = null;
        this.clearedSerial = Long.MIN_VALUE;
    }
}
