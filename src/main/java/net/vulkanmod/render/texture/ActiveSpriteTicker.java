package net.vulkanmod.render.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** An animated sprite's ticker that ticks only while the sprite was drawn lately (SpriteActivity). */
public final class ActiveSpriteTicker implements TextureAtlasSprite.Ticker {
    private final TextureAtlasSprite.Ticker ticker;
    private final SpriteActivity.Holder sprite;

    public ActiveSpriteTicker(TextureAtlasSprite.Ticker ticker, SpriteActivity.Holder sprite) {
        this.ticker = ticker;
        this.sprite = sprite;
    }

    @Override
    public void tickAndUpload() {
        if (SpriteActivity.shouldTick(this.sprite)) this.ticker.tickAndUpload();
    }

    @Override
    public void close() {
        this.ticker.close();
    }
}
