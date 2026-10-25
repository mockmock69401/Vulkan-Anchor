package net.vulkanmod.mixin.visuals.legacydh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH 3.3.0's 1.21.1 jar (DhPresence: its NeoForge build runs on suffixed copies of DH's classes, which the takeover cannot hand
 * objects to): DH's own GL passes would run under VulkanMod and fail on their first frame, so DH draws nothing. DH still loads,
 * generates and saves its LODs. Applied only with that DH (MixinPlugin).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi_neoforge", remap = false)
public abstract class SuffixedClientApiMixin {

    @Inject(method = {"renderLods", "renderDeferredLodsForShaders", "renderFadeOpaque", "renderFadeTransparent"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$noLods(CallbackInfo ci) {
        ci.cancel();
    }
}
