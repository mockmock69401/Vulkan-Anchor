package dev.vulkanfix.mixin.reforged;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a RenderedBuffer was released; release() throws when called twice. */
@Mixin(value = BufferBuilder.RenderedBuffer.class, remap = false)
public interface RenderedBufferAccessor {
    @Accessor(value = "f_231191_", remap = false)
    boolean vulkanfix$released();
}
