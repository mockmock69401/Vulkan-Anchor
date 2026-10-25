package net.vulkanmod.mixin.visuals.legacydh;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH 3.3.2 to 3.3.4: depth runs forward (DH's GL renderer picks reverse Z, which the fork's projections and composite do not
 * take), as DhRenderDefinition says through the render API.
 */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.GlDhRenderApiDefinition",
        "com.seibel.distanthorizons.common.render.openGl.GlDhRenderApiDefinition_forge"
}, remap = false)
public abstract class GlRenderDefinitionDepthDirectionMixin {

    @Inject(method = "getDepthDirection", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$forwardZ(CallbackInfoReturnable<EDhApiDepthDirection> cir) {
        cir.setReturnValue(EDhApiDepthDirection.FORWARD_Z);
    }
}
