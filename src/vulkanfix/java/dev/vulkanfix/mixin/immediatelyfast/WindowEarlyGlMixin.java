package dev.vulkanfix.mixin.immediatelyfast;

import dev.vulkanfix.compat.ReforgedBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ImmediatelyFast's MixinWindow calls ImmediatelyFast.windowInit() -> GL11C.glGetString at Window.<init> RETURN.
 * VulkanMod-Reforged only installs its emulated GL capabilities later (RenderSystem.initRenderer), and on Forge its
 * @Overwrite of GL11C never reaches LWJGL, so the raw nglGetString read a null GL context and the JVM died with
 * EXCEPTION_ACCESS_VIOLATION in lwjgl_opengl.dll. Priority 500 puts this callback ahead of ImmediatelyFast's (1000).
 */
@Mixin(targets = "com.mojang.blaze3d.platform.Window", priority = 500, remap = false)
public abstract class WindowEarlyGlMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void vulkanfix$installEmulatedGlBeforeImmediatelyFast(CallbackInfo ci) {
        ReforgedBridge.installEmulatedGl();
    }
}
