package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.LegacyProjection;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Registers the ProjMat uniform of shaders Reforged built as "legacy" (converted GLSL), whose projection it does not
 * remap to Vulkan's depth range. Priority 1100 runs after Reforged's own constructor hook has built the pipeline.
 */
@Mixin(value = ShaderInstance.class, priority = 1100, remap = false)
public abstract class LegacyShaderRegisterMixin {
    private static Field vulkanfix$isLegacy;
    private static boolean vulkanfix$warned;

    @Inject(
            method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At("RETURN"),
            remap = false
    )
    private void vulkanfix$registerLegacyProjection(CallbackInfo ci) {
        ShaderInstance self = (ShaderInstance) (Object) this;
        if (self.PROJECTION_MATRIX != null && vulkanfix$isLegacy(self)) {
            LegacyProjection.register(self.PROJECTION_MATRIX);
        }
    }

    private static boolean vulkanfix$isLegacy(ShaderInstance shader) {
        try {
            if (vulkanfix$isLegacy == null) {
                Field field = ShaderInstance.class.getDeclaredField("isLegacy");
                field.setAccessible(true);
                vulkanfix$isLegacy = field;
            }
            return vulkanfix$isLegacy.getBoolean(shader);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!vulkanfix$warned) {
                vulkanfix$warned = true;
                LoggerFactory.getLogger("vulkanfix").warn("Cannot read Reforged's ShaderInstance.isLegacy; legacy shader depth is left unconverted", e);
            }
            return false;
        }
    }
}
