package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Invoker("upload")
    void vulkanmod$upload(int x, int y, int frameX, int frameY, NativeImage[] data);

    /** 2026-10-28: the sprite's texels (OreKinds reads an ore's to tell pale specks). */
    @Accessor("originalImage")
    NativeImage vulkanmod$originalImage();
}
