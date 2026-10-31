package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Style;
import net.vulkanmod.render.text.FontBold;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-10-31 (the user: bold text is vanilla's second copy one pixel over): a bold glyph of an oversampled font is drawn once from its
 * own widened glyph (FontBold); anything else (pixel fonts, obfuscated text) keeps vanilla's two copies.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public abstract class FontStringRenderOutputM {
    @Unique
    private FontSet vulkanmod$fontSet;
    @Unique
    private int vulkanmod$codepoint;

    @Inject(method = "accept", at = @At("HEAD"))
    private void vulkanmod$forget(int index, Style style, int codepoint, CallbackInfoReturnable<Boolean> cir) {
        this.vulkanmod$fontSet = null;
    }

    @Redirect(method = "accept", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/font/FontSet;getGlyph(I)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;"))
    private BakedGlyph vulkanmod$glyph(FontSet fontSet, int codepoint) {
        this.vulkanmod$fontSet = fontSet;
        this.vulkanmod$codepoint = codepoint;
        return fontSet.getGlyph(codepoint);
    }

    @Redirect(method = "accept", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;renderChar(Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;ZZFFFLorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFFI)V"))
    private void vulkanmod$renderChar(Font font, BakedGlyph glyph, boolean bold, boolean italic, float boldOffset, float x, float y, Matrix4f pose,
                                      VertexConsumer consumer, float r, float g, float b, float a, int light) {
        BakedGlyph wide = bold && this.vulkanmod$fontSet != null
                ? ((FontBold.Source) this.vulkanmod$fontSet).vulkanmod$boldGlyph(this.vulkanmod$codepoint, boldOffset) : null;
        if (wide == null) {
            ((FontInvoker) font).vulkanmod$renderChar(glyph, bold, italic, boldOffset, x, y, pose, consumer, r, g, b, a, light);
            return;
        }
        FontStringRenderOutputAccessor self = (FontStringRenderOutputAccessor) this;
        wide.render(italic, x, y, pose, self.vulkanmod$bufferSource().getBuffer(wide.renderType(self.vulkanmod$mode())), r, g, b, a, light);
    }
}
