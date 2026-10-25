package dev.vulkanfix.mixin.reforged;

import net.vulkanmod.gl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.ByteBuffer;

@Mixin(value = GlBuffer.class, remap = false)
public interface GlBufferAccessor {
    @Accessor(value = "data", remap = false)
    ByteBuffer vulkanfix$getData();
}
