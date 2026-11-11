package net.vulkanmod.mixin.visuals;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 2026-11-11 (session 12): vanilla's star buffer rebuilt when the star count or size setting changes (StarSky.update). */
@Mixin(LevelRenderer.class)
public interface LevelRendererStarsInvoker {
    @Invoker("createStars")
    void visuals$createStars();

    /** Session 13: the star buffer, which the round stars draw with their own texture. */
    @org.spongepowered.asm.mixin.gen.Accessor("starBuffer")
    com.mojang.blaze3d.vertex.VertexBuffer visuals$starBuffer();
}
