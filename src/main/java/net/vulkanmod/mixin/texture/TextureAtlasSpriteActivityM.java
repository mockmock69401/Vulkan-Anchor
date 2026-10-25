package net.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.vulkanmod.render.texture.ActiveSpriteTicker;
import net.vulkanmod.render.texture.SpriteActivity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SpriteActivity on atlas sprites: an animated sprite's ticker ticks only while the sprite was drawn lately, and the UV getters mark
 * it as drawn (particles, block entity materials, the GUI and mods that read a sprite's UVs themselves). The getters are rewritten
 * with vanilla's bodies plus the mark rather than injected into: an injection would allocate a callback on every call of these hot
 * getters. Fields and getters are named in SRG with remap off, as DirectionMixin does (the refmap does not carry them):
 */
@Mixin(TextureAtlasSprite.class)
public class TextureAtlasSpriteActivityM implements SpriteActivity.AtlasSprite {
    @Shadow @Final private SpriteContents contents;
    @Shadow @Final private float u0;
    @Shadow @Final private float u1;
    @Shadow @Final private float v0;
    @Shadow @Final private float v1;

    @Unique
    private volatile SpriteActivity.Holder vulkanmod$animatedContents;

    @Override
    public SpriteActivity.Holder vulkanmod$animated() {
        return this.vulkanmod$animatedContents;
    }

    @Inject(method = "createTicker", at = @At("RETURN"), cancellable = true)
    private void vulkanmod$tickWhenDrawn(CallbackInfoReturnable<TextureAtlasSprite.Ticker> cir) {
        TextureAtlasSprite.Ticker ticker = cir.getReturnValue();
        if (ticker == null) return;
        SpriteActivity.Holder holder = (SpriteActivity.Holder) (Object) this.contents;
        holder.vulkanmod$setAnimated();
        this.vulkanmod$animatedContents = holder;
        cir.setReturnValue(new ActiveSpriteTicker(ticker, holder));
    }

    @Unique
    private void vulkanmod$drawn() {
        SpriteActivity.Holder h = this.vulkanmod$animatedContents;
        if (h != null) SpriteActivity.mark(h);
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getU0 marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite
    public float getU0() {
        this.vulkanmod$drawn();
        return this.u0;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getU1 marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite
    public float getU1() {
        this.vulkanmod$drawn();
        return this.u1;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getV0 marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite
    public float getV0() {
        this.vulkanmod$drawn();
        return this.v0;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getV1 marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite
    public float getV1() {
        this.vulkanmod$drawn();
        return this.v1;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getU(float) marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite
    public float getU(float u) {
        this.vulkanmod$drawn();
        float width = this.u1 - this.u0;
        return this.u0 + width * u;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getV(float) marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite
    public float getV(float v) {
        this.vulkanmod$drawn();
        float height = this.v1 - this.v0;
        return this.v0 + height * v;
    }
}
