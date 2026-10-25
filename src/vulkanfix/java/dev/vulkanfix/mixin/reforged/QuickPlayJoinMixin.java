package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.QuickPlayJoin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.quickplay.QuickPlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Quick play's join reaches QuickPlay.connect from the LoadingOverlay's finish callback, mid-frame, with the reload
 * already done, so thenRunAsync(join, minecraft) ran it inline. Minecraft.doWorldLoad's nested runTick frames then
 * ended Reforged's recording of the frame being drawn, and the rest of the loading overlay drew with no render pass
 * (NPE in drawWithShader before its RenderedBuffer was released; the shared BufferBuilder grew to 2 GB). Queue the
 * join instead: it runs from the next tick's task loop, before that frame begins, like a world opened from the list.
 */
@Mixin(value = QuickPlay.class, remap = false)
public abstract class QuickPlayJoinMixin {
    @Redirect(
            method = "m_278613_(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/main/GameConfig$QuickPlayData;"
                    + "Lnet/minecraft/server/packs/resources/ReloadInstance;Lcom/mojang/realmsclient/client/RealmsClient;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenRunAsync"
                    + "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            remap = false,
            require = 1
    )
    private static CompletableFuture<Void> vulkanfix$joinBetweenFrames(CompletableFuture<?> reload, Runnable join,
                                                                       Executor executor) {
        if (executor instanceof Minecraft minecraft) {
            return QuickPlayJoin.queued(reload, join, task -> minecraft.tell(task));
        }
        return reload.thenRunAsync(join, executor);
    }
}
