package dev.vulkanfix.mixin.reforged;

import com.mojang.blaze3d.shaders.Uniform;
import dev.vulkanfix.compat.LegacyProjection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Remaps the projection written into a legacy shader's ProjMat to Vulkan's 0..1 depth range (see LegacyProjection). */
@Mixin(value = Uniform.class, remap = false)
public abstract class LegacyProjectionUniformMixin {
    @ModifyVariable(method = "m_5679_(Lorg/joml/Matrix4f;)V", at = @At("HEAD"), argsOnly = true, remap = false)
    private Matrix4f vulkanfix$toVulkanDepth(Matrix4f matrix) {
        return LegacyProjection.isLegacyProjection(this) ? LegacyProjection.toVulkanDepth(matrix) : matrix;
    }
}
