package net.vulkanmod.mixin.render.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import net.vulkanmod.render.texture.SpriteActivity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** SpriteActivity: an item's quads mark their animated sprites as drawn (items carry baked UVs and never call the UV getters). */
@Mixin(ItemRenderer.class)
public class ItemRendererActivityM {
    @Inject(method = "renderQuadList", at = @At("HEAD"))
    private void vulkanmod$markSprites(PoseStack poseStack, VertexConsumer consumer, List<BakedQuad> quads, ItemStack stack, int light,
                                       int overlay, CallbackInfo ci) {
        SpriteActivity.markQuads(quads);
    }
}
