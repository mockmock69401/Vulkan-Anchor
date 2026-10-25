package net.vulkanmod.mixin.visuals.legacydh;

import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhAntiAliasRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFarFadeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFogRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhMetaRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhSsaoRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTestTriangleRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhVanillaFadeRenderer;
import net.vulkanmod.visuals.dh.LegacyDhContainerUniform;
import net.vulkanmod.visuals.dh.LegacyDhRenderers;
import net.vulkanmod.visuals.dh.LegacyDhTerrain;
import net.vulkanmod.visuals.dh.LegacyDhVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH 3.3.0 to 3.3.4 (no render API): DH's GL render definition hands out the fork's passes and buffers instead of its GL ones, as
 * DhRenderDefinition does through the render API from DH 3.3.5. DH's released jars carry the Fabric and the NeoForge build at once
 * and run NeoForge's copy of this class under a suffixed name. The depth direction is the two
 * GlRenderDefinition...Mixin's (DH renamed it in 3.3.2). Applied only with such a DH (MixinPlugin.legacyDhMixin).
 */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.GlDhRenderApiDefinition",
        "com.seibel.distanthorizons.common.render.openGl.GlDhRenderApiDefinition_neoforge"
}, remap = false)
public abstract class GlRenderDefinitionMixin {

    @Inject(method = "isNativeRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$notNative(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "getMetaRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$meta(CallbackInfoReturnable<IDhMetaRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.META);
    }

    @Inject(method = "getTerrainRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$terrain(CallbackInfoReturnable<IDhTerrainRenderer> cir) {
        cir.setReturnValue(LegacyDhTerrain.INSTANCE);
    }

    @Inject(method = "getSsaoRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$ssao(CallbackInfoReturnable<IDhSsaoRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.SSAO);
    }

    @Inject(method = "getFogRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$fog(CallbackInfoReturnable<IDhFogRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.FOG);
    }

    @Inject(method = "getFarFadeRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$farFade(CallbackInfoReturnable<IDhFarFadeRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.FAR_FADE);
    }

    @Inject(method = "getAntiAliasRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$antiAlias(CallbackInfoReturnable<IDhAntiAliasRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.ANTI_ALIAS);
    }

    @Inject(method = "getVanillaFadeRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$vanillaFade(CallbackInfoReturnable<IDhVanillaFadeRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.VANILLA_FADE);
    }

    @Inject(method = "getTestTriangleRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$testTriangle(CallbackInfoReturnable<IDhTestTriangleRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.TEST_TRIANGLE);
    }

    @Inject(method = "createGenericRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$genericRenderer(CallbackInfoReturnable<IDhGenericRenderer> cir) {
        cir.setReturnValue(LegacyDhRenderers.genericRenderer());
    }

    @Inject(method = "createVboWrapper", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$vbo(String name, CallbackInfoReturnable<IVertexBufferWrapper> cir) {
        cir.setReturnValue(new LegacyDhVertexBuffer(name));
    }

    @Inject(method = "createLodContainerUniformWrapper", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$uniform(CallbackInfoReturnable<ILodContainerUniformBufferWrapper> cir) {
        cir.setReturnValue(new LegacyDhContainerUniform());
    }

    @Inject(method = "createGenericVboContainer", at = @At("HEAD"), cancellable = true, remap = false)
    private void vulkanmod$genericVbo(CallbackInfoReturnable<IDhGenericObjectVertexBufferContainer> cir) {
        cir.setReturnValue(LegacyDhRenderers.genericVboContainer());
    }
}
