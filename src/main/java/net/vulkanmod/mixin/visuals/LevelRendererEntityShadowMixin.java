package net.vulkanmod.mixin.visuals;

import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.visuals.render.EntityShadowCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 2026-10-29: the entity sun shadows' capture (EntityShadowCapture) is open for the level render's entity section: from its
 * "entities" profiler section (the entities and the flush of their batches) to "blockentities"; closed again at the end in case a
 * mod reorders the sections. Optional: without the targets there are no entity shadows.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEntityShadowMixin {
    @Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=entities"))
    private void visuals$entitiesStart(CallbackInfo ci) {
        EntityShadowCapture.open();
    }

    @Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=blockentities"))
    private void visuals$entitiesEnd(CallbackInfo ci) {
        EntityShadowCapture.close();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void visuals$levelEnd(CallbackInfo ci) {
        EntityShadowCapture.close();
    }
}
