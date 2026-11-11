package net.vulkanmod.mixin.visuals;

import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 2026-11-11 (session 13): a particle's sprite, whose mean colour and coverage the realistic shapes take (ParticleLook.spriteStats). */
@Mixin(TextureSheetParticle.class)
public interface TextureSheetParticleAccessor {
    @Accessor("sprite")
    TextureAtlasSprite vulkanmod$sprite();
}
