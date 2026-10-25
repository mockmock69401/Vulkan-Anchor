package net.vulkanmod.mixin.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.dimension.DimensionType;
import net.vulkanmod.visuals.Visuals;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.Moonlight;
import net.vulkanmod.visuals.render.PostProcessSettings;
import net.vulkanmod.visuals.render.SunShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The lightmap while the visuals post-process: moonlight (design 2026-10-11 3.1) gives the sky light a night floor, and while
 * the sun shadows run (P7j design 3.4) the sky brightness is flattened so overhangs darken less and the shadow maps shade.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureVisualsMixin {
    //Slot 4 is updateLightTexture's sky-light factor, stored with 1 (lightning) or skyDarken * 0.95 + 0.05 (LightTextureVisualsMixinTest)
    @ModifyVariable(method = "updateLightTexture", at = @At("STORE"), index = 4)
    private float visuals$moonlight(float skyFactor) {
        //LightTexture's own minecraft field is Minecraft.getInstance(); no @Shadow (the jar's reobf does not rename shadow fields)
        ClientLevel level = Minecraft.getInstance().level;
        PostProcessSettings s = PostProcessSettings.from(VisualsConfig.get());
        boolean applies = level != null && level.dimensionType().hasSkyLight() && level.getSkyFlashTime() <= 0
                && s.enabled() && Visuals.active();
        if (!applies) return skyFactor;
        return Moonlight.skyFactor(skyFactor, s.moonlight(), level.getTimeOfDay(1.0F), level.getRainLevel(1.0F), level.getThunderLevel(1.0F), true);
    }

    //The first getBrightness call of each texel is the sky's; sky level 0 (caves) stays 0
    @Redirect(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LightTexture;getBrightness(Lnet/minecraft/world/level/dimension/DimensionType;I)F", ordinal = 0))
    private float visuals$skyBrightness(DimensionType type, int level) {
        return SunShadows.skyBrightness(LightTexture.getBrightness(type, level));
    }
}
