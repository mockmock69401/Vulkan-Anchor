package dev.vulkanfix.mixin.vivecraft;

import dev.vulkanfix.compat.VrGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vivecraft hands OpenGL textures to OpenVR; VulkanMod has no GL context, so VR start-up would crash.
 * Every path that sets VR_ENABLED (title button, hotkey, saved vrEnabled=true) ends in initializeVR().
 */
@Mixin(targets = "org.vivecraft.client_vr.VRState", remap = false)
public abstract class VRStateMixin {
    @Shadow(remap = false)
    public static boolean VR_ENABLED;

    @Inject(method = "initializeVR", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$blockVrUnderVulkan(CallbackInfo ci) {
        VR_ENABLED = false;
        VrGuard.clearSavedVrEnabled();
        if (VrGuard.claimFirstWarning()) {
            VrGuard.LOGGER.warn("[vulkanfix] Blocked Vivecraft VR start-up: VulkanMod cannot give OpenGL textures to OpenVR. Non-VR play is unaffected.");
        }
        ci.cancel();
    }
}
