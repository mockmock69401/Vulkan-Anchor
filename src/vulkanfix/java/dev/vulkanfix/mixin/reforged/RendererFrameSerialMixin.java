package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.ReforgedReadback;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame counter for deferred readbacks: once framesNum frames have begun since a copy, its fence has been waited. */
@Mixin(value = Renderer.class, remap = false)
public abstract class RendererFrameSerialMixin {
    @Inject(method = "beginFrame()V", at = @At("HEAD"), remap = false)
    private void vulkanfix$countFrame(CallbackInfo ci) {
        ReforgedReadback.onBeginFrame();
    }
}
