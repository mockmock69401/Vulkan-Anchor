package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 2026-11-11 (session 13): a sprite's texels (kept by the atlas), measured once per sprite (ParticleLook.spriteStats). */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage vulkanmod$originalImage();
}
