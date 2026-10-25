package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.ReforgedViewport;
import dev.vulkanfix.compat.ViewportTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records GL viewports and, inside framebuffers that keep GL's memory layout, sets the viewport that layout needs;
 * priority 1100 lands inside Reforged's @Overwrite of _viewport (1000).
 */
@Mixin(targets = "com.mojang.blaze3d.platform.GlStateManager", priority = 1100, remap = false)
public abstract class GlStateViewportMixin {
    @Inject(method = "_viewport(IIII)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$viewport(int x, int y, int width, int height, CallbackInfo ci) {
        ViewportTracker.record(x, y, width, height);
        if (ReforgedViewport.boundUsesGlMemoryLayout()) {
            ReforgedViewport.apply(x, y, width, height);
            ci.cancel();
        }
    }
}
