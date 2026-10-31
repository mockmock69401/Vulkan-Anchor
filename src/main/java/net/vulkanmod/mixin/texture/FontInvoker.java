package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 2026-10-31: vanilla's two-copy bold draw, for the glyphs FontStringRenderOutputM leaves to it. */
@Mixin(Font.class)
public interface FontInvoker {
    @Invoker("renderChar")
    void vulkanmod$renderChar(BakedGlyph glyph, boolean bold, boolean italic, float boldOffset, float x, float y, Matrix4f pose,
                              VertexConsumer consumer, float r, float g, float b, float a, int light);
}
