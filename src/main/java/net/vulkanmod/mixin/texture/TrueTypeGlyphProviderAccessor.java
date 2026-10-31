package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 2026-10-31: a TTF font's placement fields, for TrueTypeGlyphProviderM's centring. */
@Mixin(TrueTypeGlyphProvider.class)
public interface TrueTypeGlyphProviderAccessor {
    @Accessor("ascent")
    float vulkanmod$ascent();

    @Mutable
    @Accessor("ascent")
    void vulkanmod$setAscent(float ascent);

    @Accessor("oversample")
    float vulkanmod$oversample();

    @Accessor("pointScale")
    float vulkanmod$pointScale();

    @Accessor("skip")
    IntSet vulkanmod$skip();
}
