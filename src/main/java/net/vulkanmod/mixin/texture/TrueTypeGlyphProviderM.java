package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.vulkanmod.render.text.TtfCentring;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * 2026-10-31 (the user: a font smaller than the default should sit in the middle of the line spacing): every glyph of the font is
 * placed TtfCentring's shift lower (its bitmap top, in texels, lowered by it) without changing how it is rasterised.
 */
@Mixin(TrueTypeGlyphProvider.class)
public abstract class TrueTypeGlyphProviderM {
    @Shadow @Final float oversample;
    @Shadow @Final private IntSet skip;

    @Unique
    private int vulkanmod$down;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanmod$centre(ByteBuffer memory, FT_Face face, float size, float oversample, float shiftX, float shiftY, String skip,
                                  CallbackInfo ci) {
        this.vulkanmod$down = Math.round(TtfCentring.shift(face, this.skip, this.oversample) * this.oversample);
    }

    @Redirect(method = "getGlyph", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/freetype/FT_GlyphSlot;bitmap_top()I", remap = false))
    private int vulkanmod$top(FT_GlyphSlot slot) {
        return slot.bitmap_top() - this.vulkanmod$down;
    }
}
