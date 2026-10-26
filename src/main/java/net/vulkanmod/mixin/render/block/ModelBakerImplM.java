package net.vulkanmod.mixin.render.block;

import net.vulkanmod.render.model.quad.BakedQuadDeduplicator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks model baking on this thread, the only time BakedQuadDeduplicator shares vertex arrays (see there). */
@Mixin(targets = "net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl")
public class ModelBakerImplM {

    @Inject(method = {"bake", "bakeUncached"}, at = @At("HEAD"), require = 0)
    private void vulkanmod$enterBake(CallbackInfoReturnable<?> cir) {
        BakedQuadDeduplicator.enterBake();
    }

    @Inject(method = {"bake", "bakeUncached"}, at = @At("RETURN"), require = 0)
    private void vulkanmod$exitBake(CallbackInfoReturnable<?> cir) {
        BakedQuadDeduplicator.exitBake();
    }
}
