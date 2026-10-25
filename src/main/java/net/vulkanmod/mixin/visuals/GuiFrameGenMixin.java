package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.visuals.framegen.FrameGen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FG design 4: two GUI draws blend with the colour under them, which a transparent UI layer cannot hold. While the UI layer is bound
 * (FrameGen.uiLayerActive) the vignette (a multiply) and the crosshair with the attack indicator (an inversion) are not drawn: FrameGen
 * records vanilla's vignette colour and the crosshair quads (through the GuiGraphics pose, which Shoulder Surfing's dynamic crosshair
 * translates), and the compose pass applies them over the world and the UI. Otherwise both draw as in vanilla. Forge's GUI overlays
 * call these same methods. require = 0: a HUD mod that redirects the same blit keeps the game starting (frame generation then draws
 * that blit into the layer as it is).
 */
@Mixin(Gui.class)
public abstract class GuiFrameGenMixin {
    @Redirect(method = "renderVignette", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIFFIIII)V"), require = 0)
    private void visuals$vignette(GuiGraphics graphics, ResourceLocation texture, int x, int y, int z, float u, float v, int width, int height,
                                  int textureWidth, int textureHeight) {
        if (FrameGen.uiLayerActive()) {
            //renderVignette set the brightness (or the world border's tint) as the shader colour
            float[] colour = RenderSystem.getShaderColor();
            FrameGen.vignette(colour[0], colour[1], colour[2]);
            return;
        }
        graphics.blit(texture, x, y, z, u, v, width, height, textureWidth, textureHeight);
    }

    @Redirect(method = "renderCrosshair", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"), require = 0)
    private void visuals$crosshair(GuiGraphics graphics, ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
        if (FrameGen.uiLayerActive()) {
            //GuiGraphics.blit's seven-argument form samples a 256 x 256 texture; the pose places it (a HUD mod may move the crosshair)
            FrameGen.crosshairQuad(graphics.pose().last().pose(), x, y, width, height, u, v, 256, 256);
            return;
        }
        graphics.blit(texture, x, y, u, v, width, height);
    }
}
