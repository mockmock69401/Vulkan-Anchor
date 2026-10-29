package net.vulkanmod.mixin.render.block;

import net.minecraft.client.resources.model.ModelBakery;
import net.vulkanmod.render.model.quad.BakedQuadDeduplicator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-30 (audit): bakeModels catches a model that fails to bake ("Unable to bake model"), and ModelBakerImplM's exit never runs
 * for it; the bake marks of this thread are cleared when the bakery is done, so quads made on it later are not shared.
 */
@Mixin(ModelBakery.class)
public class ModelBakeryM {

    @Inject(method = "bakeModels", at = @At("RETURN"), require = 0)
    private void vulkanmod$endBakes(CallbackInfo ci) {
        BakedQuadDeduplicator.resetBake();
    }
}
