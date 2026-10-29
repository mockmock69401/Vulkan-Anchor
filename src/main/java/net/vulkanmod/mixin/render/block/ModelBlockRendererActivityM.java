package net.vulkanmod.mixin.render.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.texture.SpriteActivity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * SpriteActivity: block models drawn outside the terrain (block entities' models, falling and moving blocks, held blocks) mark their
 * animated sprites as drawn. The terrain does not come here: its sections record their sprites when they are built.
 */
@Mixin(ModelBlockRenderer.class)
public class ModelBlockRendererActivityM {
    @Inject(method = "renderQuadList", at = @At("HEAD"))
    private static void vulkanmod$markModelSprites(PoseStack.Pose pose, VertexConsumer consumer, float r, float g, float b, List<BakedQuad> quads,
                                                   int light, int overlay, CallbackInfo ci) {
        SpriteActivity.markQuads(quads);
    }

    @Inject(method = "putQuadData", at = @At("HEAD"))
    private void vulkanmod$markTesselatedSprite(BlockAndTintGetter level, BlockState state, BlockPos pos, VertexConsumer consumer, PoseStack.Pose pose,
                                                BakedQuad quad, float b0, float b1, float b2, float b3, int l0, int l1, int l2, int l3, int overlay,
                                                CallbackInfo ci) {
        //2026-10-30 (audit): a tesselated block often goes into a mesh drawn for many frames (contraptions): its sprite keeps ticking
        SpriteActivity.keepSprite(quad.getSprite());
    }
}
