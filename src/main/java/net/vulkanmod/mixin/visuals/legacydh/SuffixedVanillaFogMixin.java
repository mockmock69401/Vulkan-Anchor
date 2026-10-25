package net.vulkanmod.mixin.visuals.legacydh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH 3.3.0's 1.21.1 jar, kept from drawing (SuffixedClientApiMixin): DH would still move vanilla's fog out of the way for LODs that
 * are not drawn, and DhVanillaFog cannot hold it through this build's API (its own suffixed copy). Vanilla keeps its fog.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.commonMixins.MixinVanillaFogCommon_neoforge", remap = false)
public abstract class SuffixedVanillaFogMixin {

    @Inject(method = "cancelFog", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanmod$keepFog(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
