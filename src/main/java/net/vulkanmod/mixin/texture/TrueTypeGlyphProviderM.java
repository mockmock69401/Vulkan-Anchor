package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import net.vulkanmod.render.text.TtfCentring;
import org.lwjgl.stb.STBTTFontinfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * 2026-10-31 (the user: a font smaller than the default should sit in the middle of the line spacing): the ascent only places glyphs
 * (Glyph.bearingY), so moving it moves every glyph of the font by TtfCentring's shift without changing how they are rasterised.
 */
@Mixin(TrueTypeGlyphProvider.class)
public abstract class TrueTypeGlyphProviderM {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanmod$centre(ByteBuffer memory, STBTTFontinfo font, float size, float oversample, float shiftX, float shiftY, String skip,
                                  CallbackInfo ci) {
        TrueTypeGlyphProviderAccessor self = (TrueTypeGlyphProviderAccessor) this;
        float shift = TtfCentring.shift(font, self.vulkanmod$skip(), self.vulkanmod$pointScale(), self.vulkanmod$ascent(), self.vulkanmod$oversample());
        self.vulkanmod$setAscent(self.vulkanmod$ascent() + shift * self.vulkanmod$oversample());
    }
}
