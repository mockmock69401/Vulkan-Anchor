package net.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 2026-11-10 (shader roadmap session 9, PBR): the block atlas' sprites, for the LabPBR atlases laid out as it (PbrAtlas). */
@Mixin(TextureAtlas.class)
public class TextureAtlasPbrM {
    @Inject(method = "upload", at = @At("TAIL"))
    private void pbrSprites(SpriteLoader.Preparations data, CallbackInfo ci) {
        net.vulkanmod.visuals.render.PbrAtlas.onUpload((TextureAtlas) (Object) this, data);
    }
}
