package net.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.vulkanmod.render.texture.SpriteActivity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A sprite's activity record for SpriteActivity: whether it is animated and the last frame it was drawn in. */
@Mixin(SpriteContents.class)
public class SpriteContentsActivityM implements SpriteActivity.Holder {
    @Unique
    private volatile boolean vulkanmod$animatedFlag;
    @Unique
    private volatile long vulkanmod$frame = SpriteActivity.NEVER;

    @Override
    public void vulkanmod$setAnimated() {
        this.vulkanmod$animatedFlag = true;
    }

    @Override
    public boolean vulkanmod$isAnimated() {
        return this.vulkanmod$animatedFlag;
    }

    @Override
    public long vulkanmod$activeFrame() {
        return this.vulkanmod$frame;
    }

    @Override
    public void vulkanmod$markFrame(long frame) {
        this.vulkanmod$frame = frame;
    }
}
