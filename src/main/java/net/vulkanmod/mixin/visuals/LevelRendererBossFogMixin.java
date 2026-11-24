package net.vulkanmod.mixin.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.visuals.render.DimensionUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 2026-11-24 (the user: "엔더드래곤을 처치해야만 하늘이 출력되고 있어. 하늘은 엔더드래곤 처치 여부와 상관없이 항상 보이고 있어야 해"): the dragon's
 * boss bar asks for the world fog (BossEvent.createWorldFog), which made vanilla's fog cover everything for the whole fight: the
 * atmosphere then took the fog's mode, and the End's own sky (endsky.glsl) gave way to the fog colour until the dragon died. While the
 * End sky is drawn (DimensionUniforms.endSkyDrawn) that fog is left out, so the sky shows during the fight too.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBossFogMixin {
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z"))
    private boolean visuals$bossFog(BossHealthOverlay overlay) {
        return overlay.shouldCreateWorldFog() && !DimensionUniforms.endSkyDrawn(Minecraft.getInstance().level);
    }
}
