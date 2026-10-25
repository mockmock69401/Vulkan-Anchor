package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.ReforgedReadback;
import net.vulkanmod.gl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reforged's glGetTexImage into a pixel pack buffer submits its own command buffer and waits, so it reads the image
 * before this frame's (unsubmitted) draws into it, and ignores format/type. Xaero renders each zoomed-out branch map
 * texture and reads it straight back into a PBO, then caches those bytes: below 0.5x zoom the map showed stale,
 * channel-rotated tiles. Record the copy into the frame being built instead; the PBO is filled when it is mapped.
 */
@Mixin(value = GlTexture.class, remap = false)
public abstract class GlTextureReadbackMixin {
    @Inject(method = "getTexImage(IIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vulkanfix$deferPackReadback(int target, int level, int format, int type, long pixels, CallbackInfo ci) {
        if (level == 0 && ReforgedReadback.requestIntoPackBuffer(format, type, pixels)) {
            ci.cancel();
        }
    }
}
