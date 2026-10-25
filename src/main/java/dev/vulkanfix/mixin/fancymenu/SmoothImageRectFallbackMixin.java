package dev.vulkanfix.mixin.fancymenu;

import dev.vulkanfix.compat.FancyMenuImageFallback;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws FancyMenu's image elements without their PostChain under VulkanMod; see {@link FancyMenuImageFallback}. */
@Mixin(targets = "de.keksuccino.fancymenu.util.rendering.SmoothImageRectangleRenderer", remap = false)
public abstract class SmoothImageRectFallbackMixin {
    @Inject(method = "renderSmoothImageRectInternal", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$plainQuad(GuiGraphics graphics, ResourceLocation texture, float x, float y, float width, float height,
                                            @Coerce Object radii, @Coerce Object region, int color, float partial, CallbackInfo ci) {
        FancyMenuImageFallback.draw(graphics, texture, x, y, width, height, region, color);
        ci.cancel();
    }
}
