package dev.vulkanfix.mixin.reforged;

import net.vulkanmod.gl.GlFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GlFramebuffer.class, remap = false)
public interface GlFramebufferAccessor {
    @Accessor(value = "boundFramebuffer", remap = false)
    static GlFramebuffer vulkanfix$getBoundFramebuffer() {
        throw new AssertionError();
    }

    @Accessor(value = "boundId", remap = false)
    static int vulkanfix$getBoundId() {
        throw new AssertionError();
    }
}
