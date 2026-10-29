package net.vulkanmod.visuals.api;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * A hook into the visuals frame. Every method has an empty default, so an extension overrides only the points it needs.
 * Registered through {@link VisualsExtensions}; the render thread calls the hooks in the order listed here.
 */
public interface VisualsExtension {
    /** Before the upload commit, once per frame. Put whatever must be uploaded into {@code ctx.upload()}. */
    default void prepare(FrameContext ctx) {
    }

    /** Outside the main pass, after the first upload commit. Offscreen draws and compute. */
    default void recordOffscreen(VkCommandBuffer cmd, FrameContext ctx) {
    }

    /** Inside the restarted main pass, just before the edge cover. Composites into the main colour and depth. */
    default void drawIntoMain(VkCommandBuffer cmd, FrameContext ctx) {
    }

    /**
     * Once a frame, right after drawIntoMain. The LOD inputs for this frame, or null when there are none; the first non-null one
     * feeds the edge cover, the frame's atmosphere (LOD projection, whether LODs were drawn) and the post-process.
     */
    default LodInputs postInputs(FrameContext ctx) {
        return null;
    }

    /** When a shadow cascade is redrawn this frame: prepare before the upload commit, compute outside the pass, draw inside it. */
    default void prepareShadow(ShadowCascadeContext c) {
    }

    default void recordShadowCompute(VkCommandBuffer cmd, ShadowCascadeContext c) {
    }

    default void drawShadowCasters(VkCommandBuffer cmd, long renderPass, ShadowCascadeContext c) {
    }

    /**
     * 2026-10-29: whether this frame's shadow casters cover the terrain past the vanilla shadow distance (an LOD renderer's): the
     * vanilla terrain then casts only out to the shadow distance in every cascade (ShadowMath.vanillaRadius's lodBeyond).
     */
    default boolean castsFarTerrain() {
        return false;
    }

    /** After the download stream commit. */
    default void afterDownloads(VkCommandBuffer cmd) {
    }

    default void addDebugInfo(List<String> lines) {
    }
}
