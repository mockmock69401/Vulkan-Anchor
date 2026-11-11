package net.vulkanmod.mixin.visuals;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.visuals.render.StarMath;
import net.vulkanmod.visuals.render.StarSky;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 2026-11-11 (shader roadmap session 12): the star settings on vanilla's stars (StarMath, StarSky). drawStars: the number of tries and
 * the half size (both of its size constants times the factor, so each star keeps its random size). renderSky: the stars' shader
 * colour. At the defaults every value is vanilla's.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererStarsMixin {
    @ModifyConstant(method = "drawStars", constant = @Constant(intValue = StarMath.VANILLA_STARS))
    private int visuals$starTries(int vanilla) {
        return StarSky.tries();
    }

    @ModifyConstant(method = "drawStars", constant = @Constant(floatValue = StarMath.VANILLA_SIZE_BASE))
    private float visuals$starSizeBase(float vanilla) {
        return vanilla * StarSky.sizeScale();
    }

    @ModifyConstant(method = "drawStars", constant = @Constant(floatValue = StarMath.VANILLA_SIZE_SPREAD))
    private float visuals$starSizeSpread(float vanilla) {
        return vanilla * StarSky.sizeScale();
    }

    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"))
    private float visuals$starBrightness(ClientLevel level, float partialTick) {
        return StarSky.drawValue(level.getStarBrightness(partialTick));
    }
}
