package net.vulkanmod.mixin.visuals.legacydh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH 3.3.0 and 3.3.1: depth runs forward (DH's GL renderer picks reverse Z, which the fork's projections and composite do not
 * take), as DhRenderDefinition says through the render API. The enum is DH core's EDhRenderDepth, gone in 3.3.2, so it is looked
 * up by name.
 */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.GlDhRenderApiDefinition",
        "com.seibel.distanthorizons.common.render.openGl.GlDhRenderApiDefinition_forge"
}, remap = false)
public abstract class GlRenderDefinitionRenderDepthMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "getRenderDepth", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$forwardZ(CallbackInfoReturnable cir) {
        try {
            Class enumType = Class.forName("com.seibel.distanthorizons.core.render.EDhRenderDepth", false,
                    GlRenderDefinitionRenderDepthMixin.class.getClassLoader());
            cir.setReturnValue(Enum.valueOf(enumType, "FORWARD_Z"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            //DH keeps its own answer; the projections then disagree, which the frame capture's checks show
        }
    }
}
