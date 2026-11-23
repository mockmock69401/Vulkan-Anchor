package net.vulkanmod.mixin.compatibility.ftbchunks;

import net.vulkanmod.compat.FtbChunksTaskBudget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandles;

/**
 * 2026-11-23: FTB Chunks' map task queue runs under a time budget a tick (FtbChunksTaskBudget; Experimental > Mod Compatibility). Only
 * with FTB Chunks (MixinPlugin); require = 0, so another FTB Chunks without this method loads without it.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.client.ClientTaskQueue", remap = false)
public abstract class FtbChunksTaskQueueMixin {
    @Inject(method = "runQueuedTasks()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void vulkanmod$runUnderBudget(CallbackInfo ci) {
        //The lookup is ClientTaskQueue's own (the mixin is merged into it): FtbChunksTaskBudget reads the queue through it, no shadow
        // field that a changed FTB Chunks could fail to apply
        if (FtbChunksTaskBudget.runQueued(MethodHandles.lookup())) ci.cancel();
    }
}
