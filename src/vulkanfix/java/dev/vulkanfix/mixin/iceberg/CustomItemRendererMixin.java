package dev.vulkanfix.mixin.iceberg;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.vulkanfix.compat.GlMemoryLayoutFramebuffers;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iceberg draws GUI item icons (used by Advancement Plaques) into a 96x96 offscreen buffer created as
 * {@code new MainTarget(96, 96)}, then blits it at 16x16. VulkanMod-Reforged's MainTargetMixin treats every
 * MainTarget as the window's swapchain pass (no buffers, bindWrite rebinds the main pass), so the 96x-scaled icon is
 * drawn straight onto the screen and covers it. A TextureTarget is a real offscreen framebuffer under Reforged.
 */
@Mixin(targets = "com.anthonyhilyard.iceberg.renderer.CustomItemRenderer", remap = false)
public abstract class CustomItemRendererMixin {
    @Shadow(remap = false)
    private static RenderTarget iconFrameBuffer;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void vulkanfix$useRealOffscreenIconBuffer(CallbackInfo ci) {
        if (iconFrameBuffer instanceof MainTarget) {
            RenderTarget offscreen = new TextureTarget(iconFrameBuffer.width, iconFrameBuffer.height, true, Minecraft.ON_OSX);
            offscreen.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            offscreen.clear(Minecraft.ON_OSX);
            //2026-11-14 (session 21): Iceberg blits it with a CPU texture's UVs, so its rows keep GL's layout (the icon was upside down)
            GlMemoryLayoutFramebuffers.register(offscreen.frameBufferId);
            iconFrameBuffer = offscreen;
        }
    }
}
