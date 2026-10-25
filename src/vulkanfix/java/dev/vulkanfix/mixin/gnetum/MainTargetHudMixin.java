package dev.vulkanfix.mixin.gnetum;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vulkanfix.compat.GnetumHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gnetum's main-target redirect (see {@link GnetumHud}) for VulkanMod's MainTarget.bindWrite, which VulkanMod's MainTargetMixin adds
 * as an override: applied after it (priority), by its SRG name (MainTarget declares no bindWrite of its own for the refmap).
 */
@Mixin(value = MainTarget.class, priority = 1500)
public abstract class MainTargetHudMixin {
    @Inject(method = "m_83947_(Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanfix$gnetumHudTarget(boolean updateViewport, CallbackInfo ci) {
        if (GnetumHud.bindInstead((RenderTarget) (Object) this)) {
            ci.cancel();
        }
    }
}
