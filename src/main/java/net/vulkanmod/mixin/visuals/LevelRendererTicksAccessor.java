package net.vulkanmod.mixin.visuals;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** P7h design 3.3: vanilla's cloud drift counts these ticks; the far clouds use the same so they sit where vanilla's would. */
@Mixin(LevelRenderer.class)
public interface LevelRendererTicksAccessor {
    @Accessor("ticks")
    int visuals$getTicks();
}
