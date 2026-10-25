package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.GlslComments;
import net.vulkanmod.vulkan.shader.parser.GlslConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Feeds Reforged's GLSL converter comment-free vertex and fragment sources; see {@link GlslComments}. */
@Mixin(value = GlslConverter.class, remap = false)
public abstract class GlslCommentStripMixin {
    @ModifyVariable(method = "process", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private String vulkanfix$stripVertexComments(String vsh) {
        return GlslComments.strip(vsh);
    }

    @ModifyVariable(method = "process", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private String vulkanfix$stripFragmentComments(String fsh) {
        return GlslComments.strip(fsh);
    }
}
