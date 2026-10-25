package net.vulkanmod.visuals.api;

import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.VkDownloadStream;
import net.vulkanmod.visuals.vk.VkUploadStream;
import org.joml.Matrix4fc;

/** What an extension sees of one frame: the matrices, the camera, the size, the streams and the vanilla views. */
public record FrameContext(long serial, int frameId, Matrix4fc projection, Matrix4fc modelView, Matrix4fc vanillaMvp,
                           double cameraX, double cameraY, double cameraZ, int width, int height,
                           VkUploadStream upload, VkDownloadStream download, DeferredFree deferred, long lightmapView, long mainDepthView) {
}
