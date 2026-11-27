package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.vulkanmod.visuals.fsr.FsrScreenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;

/**
 * 2026-11-27: while FSR's swapchain is live a grab between frames (F2) is kept for the next frame, which copies the swapchain image before
 * handing it to FSR (FsrScreenshot). With FSR off nothing changes. require = 0 as ScreenshotFrameGenMixin.
 */
@Mixin(Screenshot.class)
public abstract class ScreenshotFsrMixin {
    @Inject(method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void visuals$deferFsrGrab(File gameDirectory, String name, RenderTarget target, Consumer<Component> messages, CallbackInfo ci) {
        if (FsrScreenshot.defer(gameDirectory, name, target, messages)) ci.cancel();
    }
}
