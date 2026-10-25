package dev.vulkanfix.mixin.reforged;

import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reforged builds GL framebuffer render passes with loadOp CLEAR, so beginning one (every bind, every attachment
 * change) wipes the attachments. GL keeps framebuffer contents across binds; explicit glClear is emulated with
 * vkCmdClearAttachments inside the pass. Xaero's zoomed-out map textures are rendered through one reused FBO that
 * only redraws changed quarters, so each bind erased the previous tile and the untouched quarters. Load instead
 * (VK_ATTACHMENT_LOAD_OP_LOAD = 0).
 */
@Mixin(value = GlFramebuffer.class, remap = false)
public abstract class GlFramebufferLoadOpMixin {
    @Redirect(
            method = "createAndBind()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/vulkanmod/vulkan/framebuffer/RenderPass$Builder;build()Lnet/vulkanmod/vulkan/framebuffer/RenderPass;"),
            remap = false,
            require = 1
    )
    private RenderPass vulkanfix$buildWithLoad(RenderPass.Builder builder) {
        builder.getColorAttachmentInfo().setLoadOp(0);
        RenderPass.AttachmentInfo depth = builder.getDepthAttachmentInfo();
        if (depth != null) {
            depth.setLoadOp(0);
        }
        return builder.build();
    }
}
