package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.vulkanmod.interfaces.ExtendedRenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {



    public MainTargetMixin(boolean useDepth) {
        super(useDepth);
    }

    /**
     * 2026-10-26 (1.20.1 Deceased, ZtE, BMC4: screens went black for a frame every 18 or so): mods build offscreen buffers as
     * {@code new MainTarget(w, h)} - What Are They Up To captures the open screen into them and clears them first, Iceberg draws
     * item icons into one - and every MainTarget was treated as the window, so their clears and draws landed on the screen. One made
     * once Minecraft has its own (made in Minecraft's constructor, before it has a main render target) is an ordinary render target.
     */
    @Overwrite
    private void createFrameBuffer(int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getMainRenderTarget() != null) {
            ((ExtendedRenderTarget) this).vulkanmod$markOffscreenMainTarget();
            this.resize(width, height, Minecraft.ON_OSX);
            return;
        }

        this.viewWidth = width;
        this.viewHeight = height;
        this.width = width;
        this.height = height;
    }
}
