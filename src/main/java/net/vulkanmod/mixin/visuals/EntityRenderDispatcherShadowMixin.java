package net.vulkanmod.mixin.visuals;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.vulkanmod.visuals.render.VanillaBlobShadows;
import net.vulkanmod.visuals.vk.VkFrames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-11-19: vanilla's round entity shadow is left out in a frame whose sun or moon shadow maps are drawn (VanillaBlobShadows).
 * Optional: without the target the disc stays.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherShadowMixin {
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true, require = 0)
    private static void visuals$hideBlob(CallbackInfo ci) {
        if (VanillaBlobShadows.hidden(VkFrames.serial())) ci.cancel();
    }
}
