package dev.vulkanfix.compat;

import dev.vulkanfix.mixin.reforged.GlFramebufferAccessor;
import net.vulkanmod.vulkan.Renderer;

/**
 * Sets a GL viewport the way the bound framebuffer needs it. Reforged's setViewport is top-down (negative height),
 * which flips framebuffer contents relative to GL; framebuffers in {@link GlMemoryLayoutFramebuffers} get Vulkan's
 * native viewport (Reforged's "inverted" one), whose rows land where GL puts them.
 */
public final class ReforgedViewport {
    private ReforgedViewport() {
    }

    public static boolean boundUsesGlMemoryLayout() {
        return GlMemoryLayoutFramebuffers.uses(GlFramebufferAccessor.vulkanfix$getBoundId());
    }

    public static void apply(int x, int y, int width, int height) {
        if (boundUsesGlMemoryLayout()) {
            Renderer.setInvertedViewport(x, y, width, height);
        } else {
            Renderer.setViewport(x, y, width, height);
        }
    }
}
