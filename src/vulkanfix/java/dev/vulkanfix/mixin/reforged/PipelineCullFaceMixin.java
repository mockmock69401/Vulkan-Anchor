package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.CullFaces;
import dev.vulkanfix.compat.ReforgedViewport;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reforged's pipelines fix frontFace to counter-clockwise, which matches GL only under its top-down viewport.
 * Framebuffers that keep GL's memory layout are drawn with Vulkan's native viewport, which mirrors the winding, so the
 * culled face is swapped while one is bound (otherwise every front face was culled).
 */
@Mixin(value = PipelineState.class, remap = false)
public abstract class PipelineCullFaceMixin {
    @Redirect(
            method = "getAssemblyRasterState()I",
            at = @At(value = "FIELD", target = "Lnet/vulkanmod/vulkan/VRenderSystem;cullFace:I"),
            remap = false,
            require = 1
    )
    private static int vulkanfix$cullFaceForLayout() {
        int cullFace = VRenderSystem.cullFace;
        return ReforgedViewport.boundUsesGlMemoryLayout() ? CullFaces.forGlMemoryLayout(cullFace) : cullFace;
    }
}
