package dev.vulkanfix.mixin.reforged;

import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.util.DrawUtil;
import org.lwjgl.vulkan.VK11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reforged resolves the render-scale framebuffer onto the swapchain through the generic fast blit, whose INVERTED_UV
 * vertex shader samples row 0 at the bottom of the screen under Reforged's top-down viewport: below 100% render scale
 * the whole frame was shown upside down. Draw it with Reforged's own render_scale_blit pipeline (upright UVs) instead,
 * exactly as fastBlit draws.
 */
@Mixin(value = DrawUtil.class, remap = false)
public abstract class RenderScaleBlitMixin {
    @Inject(method = "blitRenderScaleToScreen()V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$blitUpright(CallbackInfo ci) {
        GraphicsPipeline pipeline = PipelineManager.getRenderScaleBlitPipeline();
        RenderSystem.disableCull();
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
        VK11.vkCmdDraw(Renderer.getCommandBuffer(), 3, 1, 0, 0);
        RenderSystem.enableCull();
        ci.cancel();
    }
}
