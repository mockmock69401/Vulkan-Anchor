package net.vulkanmod.mixin.texture;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.vulkanmod.render.text.FontBold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 2026-10-31: each font's bold glyphs (FontBold), baked on first use and dropped with its glyphs (resetTextures: a reload, a font option change). */
@Mixin(FontSet.class)
public abstract class FontSetM implements FontBold.Source {
    @Unique
    private final Int2ObjectMap<BakedGlyph> vulkanmod$bold = new Int2ObjectOpenHashMap<>();

    @Inject(method = "resetTextures", at = @At("HEAD"))
    private void vulkanmod$dropBold(CallbackInfo ci) {
        this.vulkanmod$bold.clear();
    }

    @Override
    public BakedGlyph vulkanmod$boldGlyph(int codepoint, float boldOffset) {
        if (this.vulkanmod$bold.containsKey(codepoint)) return this.vulkanmod$bold.get(codepoint);
        FontSetInvoker self = (FontSetInvoker) this;
        BakedGlyph glyph = FontBold.bake(self.vulkanmod$providers(), codepoint, self::vulkanmod$stitch, boldOffset);
        this.vulkanmod$bold.put(codepoint, glyph);
        return glyph;
    }
}
