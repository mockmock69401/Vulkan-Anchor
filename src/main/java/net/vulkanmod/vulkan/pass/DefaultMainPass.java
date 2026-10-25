package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.RenderScale;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.MainDepthSnapshot;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.DrawUtil;
import net.vulkanmod.visuals.framegen.FrameGen;
import net.vulkanmod.visuals.framegen.FrameGenTargets;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class DefaultMainPass implements MainPass {

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private RenderTarget mainTarget;
    private final SwapChain swapChain;
    private Framebuffer mainFramebuffer;
    private MainPassTargets<Target, Passes> targets;

    private RenderPass mainRenderPass;
    private RenderPass auxRenderPass;
    private RenderPass presentRenderPass;

    private boolean renderScaleResolvedThisFrame;

    /**
     * FG design 3.1: this frame is recorded for the frame generation pacer (latched in begin). The world then draws into the offscreen
     * framebuffer (the render-scale one, at FrameGenTargets.worldSize), the hand and GUI into the UI layer, and end() neither blits nor
     * transitions the swapchain image: the pacer composes and presents.
     */
    private boolean paced;
    /** The UI layer of capture slot uiSlot is the active target, from beginUiLayer until end. */
    private boolean uiLayer;
    private int uiSlot;
    /**
     * Per capture slot: the UI framebuffer the passes and GL id below were made for (rebuilt when FrameGenTargets replaces it).
     *
     * One main + aux pass pair per slot, not one shared pair (final review I3, checked): Reforged's RenderPass.beginRenderPass
     * transitions the attachments of the framebuffer the pass was built with, not of the one begun, and endRenderPass records the final
     * layout on that same framebuffer. A pair shared by both slots would therefore transition (and mis-track) the other slot's UI image
     * while the pacer may be sampling it. The cost of the pairs is bounded instead: FrameGenTargets keeps the UI framebuffers for as long
     * as the swapchain size stays (a world-size rebuild keeps them, so the passes and the GUI pipelines compiled for them stay too), and
     * releaseUiLayer drops them when that size changes or frame generation turns off. Pipelines keyed by a released pass stay in the
     * pipeline caches until the next shader reload, as for the main passes after a render-scale change.
     */
    private final Framebuffer[] uiFramebuffers = new Framebuffer[FrameGenTargets.SLOTS];
    private final RenderPass[] uiMainPasses = new RenderPass[FrameGenTargets.SLOTS];
    private final RenderPass[] uiAuxPasses = new RenderPass[FrameGenTargets.SLOTS];
    private final int[] uiColorGlIds = {-1, -1};

    DefaultMainPass() {
        this.mainTarget = Minecraft.getInstance().getMainRenderTarget();
        this.swapChain = Vulkan.getSwapChain();
        this.mainFramebuffer = this.swapChain;

        this.targets = createTargets();
        try {
            this.presentRenderPass = createPresentRenderPass();
        } catch (RuntimeException | Error failure) {
            suppress(failure, this.targets::close);
            throw failure;
        }
        selectTarget();
    }

    private MainPassTargets<Target, Passes> createTargets() {
        return new MainPassTargets<>(new Target(this.swapChain, -1, -1), new MainPassTargets.Backend<>() {
            public Target createScaled(int width, int height, int scale) { return createScaledTarget(width, height); }
            public Passes createPasses(Target target) { return createRenderPasses(target.framebuffer()); }
            public void retireScaled(Target target) { disposeScaledTarget(target); }
            public void retirePasses(Passes passes) { retireAll(passes.main()::cleanUp, passes.load()::cleanUp); }
        });
    }

    private Passes createRenderPasses(Framebuffer framebuffer) {
        RenderPass.Builder builder = RenderPass.builder(framebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);

        RenderPass main = builder.build();

        try {
            builder = RenderPass.builder(framebuffer);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
            builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
            builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            return new Passes(main, builder.build());
        } catch (RuntimeException | Error failure) {
            suppress(failure, main::cleanUp);
            throw failure;
        }
    }


    public boolean isMainRenderPass(RenderPass pass) {
        return pass != null && (pass == this.mainRenderPass || pass == this.auxRenderPass);
    }

    private RenderPass createPresentRenderPass() {
        RenderPass.Builder builder = RenderPass.builder(this.swapChain);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);

        return builder.build();
    }

    private record Target(Framebuffer framebuffer, int colorGlId, int depthGlId) {}
    private record Passes(RenderPass main, RenderPass load) {}

    private void selectTarget() {
        this.mainFramebuffer = this.targets.target().framebuffer();
        this.mainRenderPass = this.targets.passes().main();
        this.auxRenderPass = this.targets.passes().load();
    }

    @Override
    public RenderPass resumeRenderPass(RenderPass interrupted) {
        Passes nativePasses = this.targets.swapchainPasses();
        if (interrupted == this.presentRenderPass || interrupted == nativePasses.main()
                || interrupted == nativePasses.load()) {
            return nativePasses.load();
        }
        Passes worldPasses = this.targets.retainedScaledPasses();
        if (worldPasses != null && (interrupted == worldPasses.main() || interrupted == worldPasses.load())) {
            return worldPasses.load();
        }
        return interrupted;
    }


    public Framebuffer getRetainedWorldFramebuffer() {
        Passes retained = this.targets != null ? this.targets.retainedScaledPasses() : null;
        return retained != null ? retained.load().getFramebuffer() : null;
    }

    private void ensureMainFramebuffer() {
        int scale = RenderScale.clamp(Initializer.CONFIG.renderScale);
        if (this.paced) {
            //the world is always offscreen for the pacer; the render scale applies in the world with no screen open
            Minecraft minecraft = Minecraft.getInstance();
            int[] size = FrameGenTargets.worldSize(this.swapChain.getWidth(), this.swapChain.getHeight(), scale,
                    minecraft.level != null && minecraft.screen == null);
            try {
                this.targets.select(true, true, size[0], size[1], scale);
            } finally {
                selectTarget();
            }
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        boolean retainScaled = RenderScale.isScaled(scale) && this.swapChain.getWidth() > 0
                && this.swapChain.getHeight() > 0 && minecraft.level != null;
        boolean useScaledNow = retainScaled && !this.renderScaleResolvedThisFrame && minecraft.screen == null;
        try {
            this.targets.select(retainScaled, useScaledNow,
                    RenderScale.scaleDimension(this.swapChain.getWidth(), scale),
                    RenderScale.scaleDimension(this.swapChain.getHeight(), scale), scale);
        } finally {

            selectTarget();
        }
    }

    private Target createScaledTarget(int width, int height) {
        VulkanImage colorImage = null;
        VulkanImage depthImage = null;
        int color = -1;
        int depth = -1;
        try {
            colorImage = VulkanImage.builder(width, height).setFormat(Framebuffer.DEFAULT_FORMAT)
                    .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .setLinearFiltering(true).setClamp(true).createVulkanImage();
            requireImage(colorImage);
            depthImage = VulkanImage.createDepthImage(Vulkan.getDefaultDepthFormat(), width, height,
                    MainDepthSnapshot.depthAttachmentUsage(), false, true);
            requireImage(depthImage);
            Framebuffer framebuffer = Framebuffer.builder(colorImage, depthImage).setLinearFiltering(true).build();
            color = GlTexture.genTextureId();
            GlTexture.bindIdToImage(color, colorImage);
            depth = GlTexture.genTextureId();
            GlTexture.bindIdToImage(depth, depthImage);
            return new Target(framebuffer, color, depth);
        } catch (RuntimeException | Error failure) {
            rollbackTarget(failure, colorImage, depthImage, color, depth);
            throw failure;
        }
    }

    private static void requireImage(VulkanImage image) {
        if (image == null || image.getId() == 0 || image.getImageView() == 0)
            throw new IllegalStateException("Scaled main attachment allocation is unavailable");
    }

    private static void rollbackTarget(Throwable failure, VulkanImage colorImage, VulkanImage depthImage,
                                       int color, int depth) {
        if (color != -1) suppress(failure, () -> retireOwnedAlias(color));
        if (depth != -1) suppress(failure, () -> retireOwnedAlias(depth));
        if (colorImage != null) suppress(failure, colorImage::free);
        if (depthImage != null) suppress(failure, depthImage::free);
    }

    private void disposeScaledTarget(Target target) {
        retireAll(GlFramebuffer::invalidateBorrowedAttachments,
                target.framebuffer()::cleanUp,
                () -> retireOwnedAlias(target.colorGlId()),
                () -> retireOwnedAlias(target.depthGlId()));
    }


    static void retireOwnedAlias(int id) {
        if (GlTexture.getTexture(id) == null) return;
        GlTexture.setVulkanImage(id, null);
        GlTexture.glDeleteTextures(id);
    }

    private static void suppress(Throwable primary, Runnable release) {
        try { release.run(); }
        catch (RuntimeException | Error cleanup) { if (cleanup != primary) primary.addSuppressed(cleanup); }
    }

    private static void retireAll(Runnable... releases) {
        Throwable failure = null;
        for (Runnable release : releases) {
            try { release.run(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }


    @Override public void onResize() {
        if (this.targets == null) throw new IllegalStateException("Main pass is closed");
        MainPassTargets<Target, Passes> replacement = createTargets();
        RenderPass replacementPresent;
        try {
            replacementPresent = createPresentRenderPass();
        } catch (RuntimeException | Error failure) {
            suppress(failure, replacement::close);
            throw failure;
        }
        MainPassTargets<Target, Passes> previous = this.targets;
        RenderPass previousPresent = this.presentRenderPass;
        this.targets = replacement;
        this.presentRenderPass = replacementPresent;
        this.renderScaleResolvedThisFrame = false;
        selectTarget();
        retireAll(previous::close, previousPresent::cleanUp);
    }

    @Override public void cleanUp() {
        MainPassTargets<Target, Passes> previous = this.targets;
        if (previous == null) return;
        RenderPass previousPresent = this.presentRenderPass;
        this.targets = null;
        this.presentRenderPass = null;
        this.mainRenderPass = null;
        this.auxRenderPass = null;
        this.mainFramebuffer = null;
        retireAll(previous::close, previousPresent::cleanUp);
    }

    private boolean isUsingScaledFramebuffer() {
        return this.targets.isScaled();
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.renderScaleResolvedThisFrame = false;
        this.paced = FrameGen.paced();
        this.uiLayer = false;
        ensureMainFramebuffer();

        VulkanImage colorAttachment = this.mainFramebuffer.getColorAttachment();
        if (this.mainFramebuffer == this.swapChain) {
            swapchainImageToAttachment(stack, commandBuffer, colorAttachment);
        } else {
            colorAttachment.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        this.mainFramebuffer.beginRenderPass(commandBuffer, this.mainRenderPass, stack);

        if (isUsingScaledFramebuffer()) {
            Renderer.setViewportScale(this.swapChain.getWidth(), this.swapChain.getHeight());
        } else {
            Renderer.clearViewportScale();
        }

        Renderer.setViewport(0, 0, this.swapChain.getWidth(), this.swapChain.getHeight());
        Renderer.applyScissor();
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);

        if (this.paced) {
            //no blit, no PRESENT_SRC: the pacer composes the world and UI images into the swapchain. The slot's UI image is left
            // readable for compose even on a frame that did not draw it (its old contents are then ignored: uiValid 0)
            FrameGen.endFrame(commandBuffer, this.mainFramebuffer.getColorAttachment());
            FrameGenTargets targets = FrameGen.targets();
            if (targets != null && targets.ready()) {
                targets.uiToShaderRead(commandBuffer, this.uiLayer ? this.uiSlot : FrameGen.slot());
            }
            this.uiLayer = false;
            endCommandBuffer(commandBuffer);
            return;
        }

        try(MemoryStack stack = MemoryStack.stackPush()) {
            SwapChain swapChain = Vulkan.getSwapChain();

            if (isUsingScaledFramebuffer()) {
                resolveScaledFramebufferToSwapchain(commandBuffer, false);
            }

            if (net.vulkanmod.visuals.fsr.FsrSwapchain.live()) {
                //FSR design 4.5: FSR's swapchain takes its frames in SHADER_READ_ONLY_OPTIMAL (it copies and generates from them), and
                // FSR is told this frame (configure, prepare, generation) before the submit
                VulkanImage image = swapChain.getColorAttachment();
                if (image.getCurrentLayout() == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                    //FSR reads it in later submissions (its passthrough copy, the generation compute, the composition), each ordered
                    // after this submit by FSR's semaphores; the stages and accesses named are those reads
                    VulkanImage.transitionLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
                } else {
                    image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                net.vulkanmod.visuals.fsr.FsrFrame.endFrame(commandBuffer);
            } else {
                swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        endCommandBuffer(commandBuffer);
    }

    private static void endCommandBuffer(VkCommandBuffer commandBuffer) {
        int result = vkEndCommandBuffer(commandBuffer);
        if(result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    @Override
    public boolean paced() {
        return this.paced;
    }

    @Override
    public RenderPass presentRenderPass() {
        return this.presentRenderPass;
    }

    /**
     * FG design 4: the hand and GUI of a paced frame draw into the UI layer of capture slot slot (swapchain size, own depth), cleared to
     * transparent black and depth 1. rebindMainTarget, bindAsTexture and the GL ids then work on it until end(). Render thread.
     */
    @Override
    public void beginUiLayer(int slot) {
        if (!this.paced) {
            return;
        }

        Framebuffer ui = FrameGen.targets().ui(slot);
        ensureUiPasses(slot, ui);

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.clearViewportScale();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ui.beginRenderPass(commandBuffer, this.uiMainPasses[slot], stack);

            boolean stencil = VulkanImage.hasStencilComponent(ui.getDepthFormat());
            VkClearAttachment.Buffer clears = VkClearAttachment.calloc(2, stack);
            clears.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0).clearValue().color()
                    .float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f);
            clears.get(1).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT | (stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0)).clearValue().depthStencil().set(1f, 0);
            VkClearRect.Buffer rect = VkClearRect.calloc(1, stack).baseArrayLayer(0).layerCount(1);
            rect.rect().offset().set(0, 0);
            rect.rect().extent().set(ui.getWidth(), ui.getHeight());
            vkCmdClearAttachments(commandBuffer, clears, rect);
        }

        this.uiLayer = true;
        this.uiSlot = slot;
    }

    /** The UI layer's main (contents discarded, cleared after begin) and aux (LOAD) passes and GL id, once per UI framebuffer. */
    private void ensureUiPasses(int slot, Framebuffer ui) {
        if (this.uiFramebuffers[slot] == ui) {
            return;
        }

        if (this.uiMainPasses[slot] != null) {
            this.uiMainPasses[slot].cleanUp();
            this.uiAuxPasses[slot].cleanUp();
        }

        RenderPass.Builder builder = RenderPass.builder(ui);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        this.uiMainPasses[slot] = builder.build();

        builder = RenderPass.builder(ui);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        this.uiAuxPasses[slot] = builder.build();

        if (this.uiColorGlIds[slot] == -1) {
            this.uiColorGlIds[slot] = GlTexture.genTextureId();
        }
        GlTexture.bindIdToImage(this.uiColorGlIds[slot], ui.getColorAttachment());
        this.uiFramebuffers[slot] = ui;
    }

    /**
     * The UI framebuffers are being freed or replaced (frame generation turned off, or a new swapchain size): the UI passes are cleaned
     * up and the UI GL ids unbound, as disposeScaledFramebuffer does, so nothing here points at a freed image (final review M6). Render
     * thread, outside a frame.
     */
    @Override
    public void releaseUiLayer() {
        for (int slot = 0; slot < FrameGenTargets.SLOTS; slot++) {
            if (this.uiMainPasses[slot] != null) {
                this.uiMainPasses[slot].cleanUp();
                this.uiAuxPasses[slot].cleanUp();
            }
            this.uiMainPasses[slot] = null;
            this.uiAuxPasses[slot] = null;
            this.uiFramebuffers[slot] = null;

            if (this.uiColorGlIds[slot] != -1) {
                GlTexture.setVulkanImage(this.uiColorGlIds[slot], null);
                this.uiColorGlIds[slot] = -1;
            }
        }
        this.uiLayer = false;
    }

    @Override
    public boolean isUiLayerPass(RenderPass pass) {
        if (pass == null) {
            return false;
        }

        for (int slot = 0; slot < FrameGenTargets.SLOTS; slot++) {
            if (pass == this.uiMainPasses[slot] || pass == this.uiAuxPasses[slot]) {
                return true;
            }
        }
        return false;
    }

    private boolean isUiPass(RenderPass pass) {
        return pass != null && (pass == this.uiMainPasses[this.uiSlot] || pass == this.uiAuxPasses[this.uiSlot]);
    }

    /** runTick binds the main target before the frame begins: a paced frame rebinds its active target only inside a frame. */
    @Override
    public void mainTargetBindWrite() {
        if (this.paced && Renderer.isRecording()) {
            rebindMainTarget();
        }
    }

    @Override
    public void resolveRenderScaleForGui() {
        //a paced frame keeps the GUI off the swapchain: it draws into the UI layer, or into the world framebuffer when there is none
        if (this.paced) {
            return;
        }

        if (!isUsingScaledFramebuffer() || this.renderScaleResolvedThisFrame) {
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        resolveScaledFramebufferToSwapchain(commandBuffer, true);
        this.renderScaleResolvedThisFrame = true;
        this.targets.resolveForGui();
        selectTarget();
    }

    /**
     * The swapchain image to COLOR_ATTACHMENT_OPTIMAL before the frame draws into it. While FSR's swapchain is live the image comes
     * back in SHADER_READ_ONLY_OPTIMAL (the hand-over in end), and FSR signals the acquire semaphore only once its own reads of it
     * (passthrough copy, generation, composition) are done. The submit waits that semaphore at COLOR_ATTACHMENT_OUTPUT, so the
     * transition must start in that stage to chain with it (from SHADER_READ_ONLY the generic switch would start at FRAGMENT_SHADER,
     * outside the wait). The old contents are not kept: from UNDEFINED, as the pass discards them (DONT_CARE) or the blit overwrites
     * them. With FSR off the generic transition is unchanged (PRESENT_SRC from BOTTOM_OF_PIPE).
     */
    private static void swapchainImageToAttachment(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image) {
        if (net.vulkanmod.visuals.fsr.FsrSwapchain.live() && image.getCurrentLayout() == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            image.setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            VulkanImage.transitionLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            return;
        }
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    }

    private void resolveScaledFramebufferToSwapchain(VkCommandBuffer commandBuffer, boolean keepRendering) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            swapchainImageToAttachment(stack, commandBuffer, this.swapChain.getColorAttachment());
            this.swapChain.beginRenderPass(commandBuffer, this.presentRenderPass, stack);
            Renderer.clearViewportScale();
            Renderer.resetViewport();
            Renderer.resetScissor();
            VTextureSelector.bindTexture(this.mainFramebuffer.getColorAttachment());
            DrawUtil.blitRenderScaleToScreen();

            if (!keepRendering) {
                Renderer.getInstance().endRenderPass(commandBuffer);
            }
        }
    }

    public void rebindMainTarget() {
        if (this.uiLayer) {
            rebindUiLayer();
            return;
        }

        ensureMainFramebuffer();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if(boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);

        try(MemoryStack stack = MemoryStack.stackPush()) {
            this.mainFramebuffer.beginRenderPass(commandBuffer, this.auxRenderPass, stack);
            if (isUsingScaledFramebuffer()) {
                Renderer.setViewportScale(this.swapChain.getWidth(), this.swapChain.getHeight());
            } else {
                Renderer.clearViewportScale();
            }
            Renderer.setViewport(0, 0, this.swapChain.getWidth(), this.swapChain.getHeight());
        }

    }

    private void rebindUiLayer() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if (isUiPass(Renderer.getInstance().getBoundRenderPass()))
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.clearViewportScale();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.uiFramebuffers[this.uiSlot].beginRenderPass(commandBuffer, this.uiAuxPasses[this.uiSlot], stack);
        }
    }

    @Override
    public void bindAsTexture() {
        if (this.uiLayer) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            if (isUiPass(Renderer.getInstance().getBoundRenderPass()))
                Renderer.getInstance().endRenderPass(commandBuffer);

            VulkanImage colour = this.uiFramebuffers[this.uiSlot].getColorAttachment();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                colour.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            VTextureSelector.bindTexture(colour);
            return;
        }

        ensureMainFramebuffer();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if(boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(this.mainFramebuffer.getColorAttachment());
    }

    public int getColorAttachmentGlId() {
        if (this.uiLayer) {
            return this.uiColorGlIds[this.uiSlot];
        }

        ensureMainFramebuffer();

        if (isUsingScaledFramebuffer()) {
            return this.targets.target().colorGlId();
        }

        return Vulkan.getSwapChain().getColorAttachmentGlId();
    }

    @Override
    public VulkanImage getColorAttachment() {
        ensureMainFramebuffer();
        return this.mainFramebuffer != null ? this.mainFramebuffer.getColorAttachment() : null;
    }

    @Override
    public VulkanImage getDepthAttachment() {
        ensureMainFramebuffer();
        return this.mainFramebuffer != null ? this.mainFramebuffer.getDepthAttachment() : null;
    }

    @Override
    public int getDepthAttachmentGlId() {
        ensureMainFramebuffer();

        if (isUsingScaledFramebuffer()) {
            return this.targets.target().depthGlId();
        }

        return Vulkan.getSwapChain().getDepthAttachmentGlId();
    }
}
