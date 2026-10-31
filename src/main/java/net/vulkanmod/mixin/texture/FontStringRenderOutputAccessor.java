package net.vulkanmod.mixin.texture;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 2026-10-31: where a text output draws, for a bold glyph on another page (FontStringRenderOutputM). */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public interface FontStringRenderOutputAccessor {
    @Accessor("bufferSource")
    MultiBufferSource vulkanmod$bufferSource();

    @Accessor("mode")
    Font.DisplayMode vulkanmod$mode();
}
