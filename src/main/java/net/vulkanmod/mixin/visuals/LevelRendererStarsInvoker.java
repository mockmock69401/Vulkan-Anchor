package net.vulkanmod.mixin.visuals;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 2026-11-11 (session 12): vanilla's star buffer rebuilt when the star count or size setting changes (StarSky.update). */
@Mixin(LevelRenderer.class)
public interface LevelRendererStarsInvoker {
    @Invoker("createStars")
    void visuals$createStars();
}
