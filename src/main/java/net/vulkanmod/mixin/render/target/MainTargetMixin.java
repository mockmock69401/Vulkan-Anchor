package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@SuppressWarnings("target")
@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {

    /**
     * 2026-10-26 (Deceased, ZtE, BMC4: the inventory and FTB Quests screens went black for a frame every 18 or so): only Minecraft's
     * own MainTarget is the swapchain pass. Mods also build offscreen buffers as {@code new MainTarget(w, h)} - What Are They Up To
     * captures the open screen into two of them and clears them first, Iceberg draws item icons into one - and those were treated
     * as the window too (no buffers, bindWrite rebinding the main pass), so their clears and draws landed on the screen. A MainTarget
     * made once Minecraft has its own is an ordinary render target, as it is in GL.
     */
    @Unique
    private boolean vulkanmod$offscreen;

    /** Offscreen unless a mod made it Minecraft's main render target for good (MainTargets). */
    @Unique
    private boolean vulkanmod$offscreen() {
        return this.vulkanmod$offscreen && !net.vulkanmod.render.target.MainTargets.adopted(this);
    }

    public MainTargetMixin(boolean useDepth) {
        super(useDepth);
    }

    @Overwrite(remap = false)
    private void m_166141_(int width, int height) {
        //Minecraft's own is made in its constructor, before it has a main render target
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getMainRenderTarget() != null) {
            this.vulkanmod$offscreen = true;
            this.resize(width, height, Minecraft.ON_OSX);
            return;
        }

        this.viewWidth = width;
        this.viewHeight = height;
        this.width = width;
        this.height = height;
    }

    @Override
    public void bindWrite(boolean updateScissor) {
        if (this.vulkanmod$offscreen()) {
            super.bindWrite(updateScissor);
            return;
        }
        Renderer.getInstance().getMainPass().rebindMainTarget();
    }

    @Override
    public void bindRead() {
        if (this.vulkanmod$offscreen()) {
            super.bindRead();
            return;
        }
        Renderer.getInstance().getMainPass().bindAsTexture();
    }

    @Override
    public int getColorTextureId() {
        if (this.vulkanmod$offscreen())
            return super.getColorTextureId();
        return Renderer.getInstance().getMainPass().getColorAttachmentGlId();
    }

    @Override
    public int getDepthTextureId() {
        if (this.vulkanmod$offscreen())
            return super.getDepthTextureId();
        return Renderer.getInstance().getMainPass().getDepthAttachmentGlId();
    }
}
