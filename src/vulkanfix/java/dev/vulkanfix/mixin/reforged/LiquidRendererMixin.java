package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.LegacySpriteUv;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * VulkanMod-Reforged's LiquidRenderer (water/lava chunk meshes) calls sprite.getU(0.0), getV(1.0),
 * getU(0.5F + flow)... with 0..1 values, but 1.20.1's TextureAtlasSprite.getU/getV expect 0..16 (vanilla
 * LiquidBlockRenderer uses getU(0.0), getV(16.0), getU(8.0F + flow * 16.0F)). Fluids sampled a 1/16 corner of
 * their sprite: nearly flat colour with visible triangle seams. Scale every such call back to vanilla's range.
 */
@Mixin(targets = "net.vulkanmod.render.chunk.build.LiquidRenderer", remap = false)
public abstract class LiquidRendererMixin {
    @Redirect(
            method = "*",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;m_118367_(D)F"),
            remap = false,
            require = 1
    )
    private float vulkanfix$legacyU(TextureAtlasSprite sprite, double u) {
        return sprite.getU(LegacySpriteUv.toLegacy(u));
    }

    @Redirect(
            method = "*",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;m_118393_(D)F"),
            remap = false,
            require = 1
    )
    private float vulkanfix$legacyV(TextureAtlasSprite sprite, double v) {
        return sprite.getV(LegacySpriteUv.toLegacy(v));
    }
}
