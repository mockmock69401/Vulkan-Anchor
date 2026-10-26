package net.vulkanmod.mixin.render.block;

import net.vulkanmod.render.model.quad.BakedQuadDeduplicator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks model baking on this thread, the only time BakedQuadDeduplicator shares vertex arrays (see there). */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl")
public class ModelBakerImplM {

    @Inject(method = {"m_245240_", "bake(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;"},
            at = @At("HEAD"), remap = false, require = 0)
    private void vulkanmod$enterBake(CallbackInfoReturnable<?> cir) {
        BakedQuadDeduplicator.enterBake();
    }

    @Inject(method = {"m_245240_", "bake(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/resources/model/ModelState;Ljava/util/function/Function;)Lnet/minecraft/client/resources/model/BakedModel;"},
            at = @At("RETURN"), remap = false, require = 0)
    private void vulkanmod$exitBake(CallbackInfoReturnable<?> cir) {
        BakedQuadDeduplicator.exitBake();
    }
}
