package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.GlslPercent;
import net.vulkanmod.vulkan.shader.parser.CodeParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Reforged's CodeParser from rewriting "%" into a mangled mod() call; see {@link GlslPercent}. */
@Mixin(value = CodeParser.class, remap = false)
public abstract class CodeParserPercentMixin {
    @ModifyVariable(method = "parseCodeLine", at = @At("HEAD"), argsOnly = true, remap = false)
    private static String vulkanfix$hidePercent(String line) {
        return GlslPercent.protect(line);
    }

    @Inject(method = "parseCodeLine", at = @At("RETURN"), cancellable = true, remap = false)
    private static void vulkanfix$restorePercent(String line, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(GlslPercent.restore(cir.getReturnValue()));
    }
}
