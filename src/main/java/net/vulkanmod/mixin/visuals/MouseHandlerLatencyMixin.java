package net.vulkanmod.mixin.visuals;

import net.minecraft.client.MouseHandler;
import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.framegen.FrameGen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame generation phase 1, Task 10: the input-to-present latency's start. MouseHandler.turnPlayer applies the mouse movement to the
 * camera once per frame; its entry is the frame's input time, which travels with the frame to the present log (FrameGen.inputSampled).
 * Nothing happens unless the recorder is on. require = 0: a mod that replaces the method leaves the latency column empty, not the game
 * unstartable.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerLatencyMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), require = 0)
    private void visuals$inputSampled(CallbackInfo ci) {
        if (FrameRecorder.ON) FrameGen.inputSampled(System.nanoTime());
    }
}
