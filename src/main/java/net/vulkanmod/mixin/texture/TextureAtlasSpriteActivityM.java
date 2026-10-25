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
 * f_244165_ contents, f_118351_..f_118354_ u0 u1 v0 v1; m_118409_ getU0, m_118410_ getU1, m_118411_ getV0, m_118412_ getV1,
 * m_118367_ getU(double), m_118393_ getV(double).
 */
@Mixin(TextureAtlasSprite.class)
public class TextureAtlasSpriteActivityM implements SpriteActivity.AtlasSprite {
    @Shadow(remap = false) @Final private SpriteContents f_244165_;
    @Shadow(remap = false) @Final private float f_118351_;
    @Shadow(remap = false) @Final private float f_118352_;
    @Shadow(remap = false) @Final private float f_118353_;
    @Shadow(remap = false) @Final private float f_118354_;

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
        SpriteActivity.Holder holder = (SpriteActivity.Holder) (Object) this.f_244165_;
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
    @Overwrite(remap = false)
    public float m_118409_() {
        this.vulkanmod$drawn();
        return this.f_118351_;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getU1 marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite(remap = false)
    public float m_118410_() {
        this.vulkanmod$drawn();
        return this.f_118352_;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getV0 marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite(remap = false)
    public float m_118411_() {
        this.vulkanmod$drawn();
        return this.f_118353_;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getV1 marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite(remap = false)
    public float m_118412_() {
        this.vulkanmod$drawn();
        return this.f_118354_;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getU(double) marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite(remap = false)
    public float m_118367_(double u) {
        this.vulkanmod$drawn();
        float width = this.f_118352_ - this.f_118351_;
        return this.f_118351_ + width * (float) u / 16.0F;
    }

    /**
     * @author VulkanMod
     * @reason SpriteActivity: getV(double) marks the sprite as drawn (vanilla body otherwise)
     */
    @Overwrite(remap = false)
    public float m_118393_(double v) {
        this.vulkanmod$drawn();
        float height = this.f_118354_ - this.f_118353_;
        return this.f_118353_ + height * (float) v / 16.0F;
    }
}
