package net.vulkanmod.mixin.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.vulkanmod.compat.render.RenderStateSnapshot;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenM {
    @Unique
    private RenderStateSnapshot vulkanMod$screenStateSnapshot;

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void vulkanMod$beginScreenRenderStateBoundary(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        this.vulkanMod$screenStateSnapshot = new RenderStateSnapshot();

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableDepthTest();
        //2026-11-05 (the user: Pufferfish's Skills lost its effect icons): depth writes rest on, as in vanilla's GUI state. The mod draws
        // the icons at z 1 before their frames at z 0 and relies on the icons' depth to keep the frames off them
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

                                                                                                
                                                                                                      
                                                                                                     
                                                                                                     
                                                                                                    
                                                                                       
        Renderer.clearAttachments(GL11.GL_DEPTH_BUFFER_BIT);
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"))
    private void vulkanMod$endScreenRenderStateBoundary(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        RenderStateSnapshot snapshot = this.vulkanMod$screenStateSnapshot;
        this.vulkanMod$screenStateSnapshot = null;

        if (snapshot != null) {
            snapshot.restore();
        }
    }
}

