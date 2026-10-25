package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.FramebufferRestart;
import dev.vulkanfix.compat.ReforgedViewport;
import dev.vulkanfix.compat.ViewportTracker;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reforged's createAndBind() rebuilds the framebuffer and render pass when an attachment of the currently bound
 * framebuffer changes, ending the active pass, but never begins the new one. Xaero's world map binds its waypoint
 * atlas framebuffer and then attaches the atlas texture, so the next draw had no render pass and crashed
 * ("state.renderPass is null"). Resume the pass and put back the viewport the mod had set, as GL would keep it.
 */
@Mixin(value = GlFramebuffer.class, remap = false)
public abstract class GlFramebufferMixin {
    @Shadow(remap = false)
    private static GlFramebuffer boundFramebuffer;
    @Shadow(remap = false)
    Framebuffer framebuffer;
    @Shadow(remap = false)
    RenderPass renderPass;

    @Inject(method = "createAndBind", at = @At("RETURN"), remap = false)
    private void vulkanfix$resumeBoundRenderPass(CallbackInfo ci) {
        GlFramebuffer self = (GlFramebuffer) (Object) this;
        boolean recording = Renderer.isRecording();
        boolean passActive = recording && Renderer.getInstance().getBoundRenderPass() == this.renderPass;
        if (FramebufferRestart.shouldRestart(boundFramebuffer == self, this.framebuffer != null, recording, passActive)) {
            int[] viewport = ViewportTracker.last();
            GlFramebuffer.beginRendering(self);
            if (viewport != null) {
                ReforgedViewport.apply(viewport[0], viewport[1], viewport[2], viewport[3]);
            }
        }
    }
}
