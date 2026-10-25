package net.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(SpriteContents.AnimatedTexture.class)
public interface SpriteAnimatedTextureAccessor {
    /** 1.20.1: not opened by an access transformer as on 1.21.1. */
    @Accessor("frames")
    List<SpriteContents.FrameInfo> vulkanmod$getFrames();
    @Invoker("getFrameX")
    int vulkanmod$getFrameX(int frame);
    @Invoker("getFrameY")
    int vulkanmod$getFrameY(int frame);
}
