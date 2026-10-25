package dev.vulkanfix.mixin.aaaparticles;

import dev.vulkanfix.compat.EffekseerGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps AAA Particles from loading Effekseer's native OpenGL renderer under VulkanMod; see {@link EffekseerGuard}. */
@Mixin(targets = "mod.chloeprime.aaaparticles.client.AAAParticlesClient", remap = false)
public abstract class NativeLibraryGuardMixin {
    @Inject(method = "installNativeLibrary", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$skipEffekseerUnderVulkan(CallbackInfo ci) {
        if (EffekseerGuard.markPlatformUnsupported()) {
            ci.cancel();
        }
    }
}
