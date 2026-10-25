package net.vulkanmod.visuals.api;

import net.vulkanmod.visuals.render.ShadowMath;
import net.vulkanmod.visuals.vk.VkDownloadStream;
import net.vulkanmod.visuals.vk.VkUploadStream;

/**
 * One shadow cascade being redrawn this frame. Both streams are this frame's (ruling R12). The upload stream is open for writing
 * only in prepareShadow; in recordShadowCompute and drawShadowCasters it is already committed. Performance (2026-10-21): a refresh
 * may be spread over several frames (slices > 1); this frame draws slice (0 .. slices - 1), and an extension draws the casters of
 * that share only, by a key that stays the same from frame to frame (one that draws every caster in every slice is still right).
 */
public record ShadowCascadeContext(String name, ShadowMath.Cascade cascade, int width, int height, long serial, int frameId, int slice,
                                   int slices, VkUploadStream upload, VkDownloadStream download) {
    /** A whole draw: slice 0 of 1. */
    public ShadowCascadeContext(String name, ShadowMath.Cascade cascade, int width, int height, long serial, int frameId, VkUploadStream upload,
                                VkDownloadStream download) {
        this(name, cascade, width, height, serial, frameId, 0, 1, upload, download);
    }
}
