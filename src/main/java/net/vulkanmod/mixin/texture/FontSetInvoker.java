package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** 2026-10-31: FontSet's active providers and stitch (a glyph onto the font's pages), for the bold glyphs (FontSetM). */
@Mixin(FontSet.class)
public interface FontSetInvoker {
    @Accessor("activeProviders")
    List<GlyphProvider> vulkanmod$providers();

    @Invoker("stitch")
    BakedGlyph vulkanmod$stitch(SheetGlyphInfo info);
}
