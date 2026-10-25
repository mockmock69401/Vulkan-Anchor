package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.vulkanmod.visuals.framegen.FrameGen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;

/**
 * Frame generation phase 1, final review I4: F2 runs Screenshot.grab between frames, after endFrame. While paced the world framebuffer
 * it reads then holds no hand, GUI, vignette or crosshair (they went to the UI layer and the compose pass), so the grab is handed to
 * FrameGen.deferScreenshot: the next frame pauses ("screenshot") and draws them into the world framebuffer, and the grab runs right
 * after that frame's endFrame. With frame generation off nothing changes. Every grab goes through this four-argument form (F2's
 * three-argument one calls it). require = 0: a mod that replaces it keeps the game starting (paced screenshots then lack the GUI).
 */
@Mixin(Screenshot.class)
public abstract class ScreenshotFrameGenMixin {
    @Inject(method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void visuals$deferGrab(File gameDirectory, String name, RenderTarget target, Consumer<Component> messages, CallbackInfo ci) {
        if (FrameGen.deferScreenshot(gameDirectory, name, target, messages)) ci.cancel();
    }
}
