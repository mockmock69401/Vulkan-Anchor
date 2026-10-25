package dev.vulkanfix.mixin.reforged;

import org.spongepowered.asm.mixin.Mixin;

/**
 * VulkanMod-Reforged's SpriteCoordinateExpanderM fast path calls sprite.getU(u)/getV(v) with the model's 0..1 UVs,
 * but 1.20.1's TextureAtlasSprite.getU(double) expects 0..16 (vanilla passes u * 16). Every block-entity model drawn
 * through ModelPartM's fast path (chests, beds, signs, shulker boxes...) therefore samples a 1/16 corner of its
 * sprite and shows flat colours. Reporting "no fast vertex" sends ModelPartM down vanilla's
 * SpriteCoordinateExpander.vertex(...), which scales correctly. Priority 1100 replaces Reforged's merged method (1000).
 */
@Mixin(targets = "net.minecraft.client.renderer.SpriteCoordinateExpander", priority = 1100, remap = false)
public abstract class SpriteCoordinateExpanderMixin {
    public boolean canUseFastVertex() {
        return false;
    }
}
