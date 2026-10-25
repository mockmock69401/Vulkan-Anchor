package net.vulkanmod.interfaces;

import net.vulkanmod.vulkan.framebuffer.RenderPass;

public interface ExtendedRenderTarget {

    boolean isBound();

    RenderPass getRenderPass();

    void vulkanmod$prepareForDepthCopy();

    /** Minecraft's own MainTarget: the swapchain pass. A MainTarget a mod makes for itself is an ordinary target (MainTargetMixin). */
    boolean vulkanmod$isWindowTarget();

    void vulkanmod$markOffscreenMainTarget();
}
