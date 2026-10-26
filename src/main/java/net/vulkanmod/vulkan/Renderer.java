package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.compat.observer.GuiRenderTrace;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.mixin.window.WindowAccessor;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.optimization.AdaptiveChunkUploadBudget;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.VUtil;
import net.vulkanmod.vulkan.util.VkResult;
import net.vulkanmod.visuals.framegen.FrameGen;
import net.vulkanmod.visuals.framegen.FramePacer;
import net.vulkanmod.visuals.vk.VkFrames;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.mojang.blaze3d.platform.GlConst.GL_COLOR_BUFFER_BIT;
import static com.mojang.blaze3d.platform.GlConst.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;
import static net.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class Renderer {
    private static Renderer INSTANCE;

    private static VkDevice device;

    /** Volatile: the frame generation pacer thread schedules an update when its present finds the swapchain out of date. */
    private static volatile boolean swapChainUpdate = false;
    public static boolean skipRendering = false;
    private static boolean scissorEnabled = false;

    public static void initRenderer() {
        INSTANCE = new Renderer();
        INSTANCE.init();
    }

    public static Renderer getInstance() {
        return INSTANCE;
    }

    public static Drawer getDrawer() {
        return INSTANCE.drawer;
    }

    public static int getCurrentFrame() {
        return currentFrame;
    }

    public static int getCurrentImage() {
        return imageIndex;
    }

    private final Set<Pipeline> usedPipelines = new ObjectOpenHashSet<>();
    private Pipeline boundPipeline;
    private long boundPipelineHandle;

    private final float[] appliedBlendConstants = new float[4];
    private boolean blendConstantsApplied;
    private int appliedStencilRef;
    private int appliedStencilCompareMask;
    private int appliedStencilWriteMask;
    private boolean stencilStateApplied;

    private Drawer drawer;

    private int framesNum;
    private int imagesNum;
    private List<VkCommandBuffer> commandBuffers;
    private ArrayList<Long> imageAvailableSemaphores;
    private ArrayList<Long> renderFinishedSemaphores;
    private ArrayList<Long> inFlightFences;

    private Framebuffer boundFramebuffer;
    private RenderPass boundRenderPass;
    private int viewportLogicalWidth = 0;
    private int viewportLogicalHeight = 0;

    private static int currentFrame = 0;
    private static int imageIndex;
    private static int lastReset = -1;
    private VkCommandBuffer currentCmdBuffer;
    private boolean recordingCmds = false;
    /** This frame is recorded for the frame generation pacer (FrameGen.paced, latched in beginFrame): no acquire, no present. */
    private boolean pacedFrame = false;

    MainPass mainPass = DefaultMainPass.create();

    private final List<Runnable> onResizeCallbacks = new ObjectArrayList<>();

    public Renderer() {
        device = Vulkan.getVkDevice();
        framesNum = Initializer.CONFIG.frameQueueSize;
        imagesNum = getSwapChain().getImagesNum();
    }

    public static void setLineWidth(float width) {
        if (INSTANCE.boundFramebuffer == null) {
            return;
        }
        vkCmdSetLineWidth(INSTANCE.currentCmdBuffer, width);
    }

    private void init() {
        MemoryManager.createInstance(Renderer.getFramesNum());
        Vulkan.createStagingBuffers();

        drawer = new Drawer();
        drawer.createResources(framesNum);

        Uniforms.setupDefaultUniforms();
        PipelineManager.init();
        UploadManager.createInstance();

        allocateCommandBuffers();
        createSyncObjects();
    }

    private void allocateCommandBuffers() {
        if (commandBuffers != null) {
            commandBuffers.forEach(commandBuffer -> vkFreeCommandBuffers(device, Vulkan.getCommandPool(), commandBuffer));
        }

        commandBuffers = new ArrayList<>(framesNum);

        try (MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(framesNum);

            PointerBuffer pCommandBuffers = stack.mallocPointer(framesNum);

            int vkResult = vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers);
            if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers: %s".formatted(VkResult.decode(vkResult)));
            }

            for (int i = 0; i < framesNum; i++) {
                commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private void createSyncObjects() {
        imageAvailableSemaphores = new ArrayList<>(framesNum);
        renderFinishedSemaphores = new ArrayList<>(framesNum);
        inFlightFences = new ArrayList<>(framesNum);

        try (MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
            LongBuffer pRenderFinishedSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for (int i = 0; i < framesNum; i++) {

                if (vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                        || vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS
                        || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {

                    throw new RuntimeException("Failed to create synchronization objects for the frame: " + i);
                }

                imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
                renderFinishedSemaphores.add(pRenderFinishedSemaphore.get(0));
                inFlightFences.add(pFence.get(0));

            }

        }
    }

    public void beginFrame() {
        net.vulkanmod.render.profiling.FpsOverlay.frame();
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.mark(net.vulkanmod.visuals.dev.FrameRecorder.M_BEGIN_START);
        //FG design 7: a changed setting or a pacer restart is applied before the frame latches the paced state (no-op while off)
        FrameGen.beginFrame();
        //FSR design 4.4: turning FSR on or off is applied by the same recreation
        net.vulkanmod.visuals.fsr.FsrSwapchain.beginFrame();
        if (swapChainUpdate) {
            recreateSwapChain();
            swapChainUpdate = false;

            if (getSwapChain().getWidth() == 0 && getSwapChain().getHeight() == 0) {
                skipRendering = true;
                Minecraft.getInstance().noRender = true;
            } else {
                skipRendering = false;
                Minecraft.getInstance().noRender = false;
            }
        }

        if (skipRendering || recordingCmds)
            return;

        //a rendered frame: SpriteActivity counts what this frame draws
        net.vulkanmod.render.texture.SpriteActivity.nextFrame();

        //FG design 3.1: a paced frame leaves acquire and present to the pacer thread
        pacedFrame = FrameGen.paced();

        long fenceStart = net.vulkanmod.visuals.dev.FrameRecorder.ON ? System.nanoTime() : 0;
        vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);
        //FSR design 4.4 (final review I2): FSR's backend keeps the views of the resources a frame gives it for FFX_MAX_QUEUED_FRAMES (4)
        // of its GPU jobs, two per real frame: while FSR's swapchain is live, at most two real frames are in flight
        if (framesNum > 2 && net.vulkanmod.visuals.fsr.FsrSwapchain.live()) {
            vkWaitForFences(device, inFlightFences.get((currentFrame + framesNum - 2) % framesNum), true, VUtil.UINT64_MAX);
        }
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.time(net.vulkanmod.visuals.dev.FrameRecorder.FENCE, fenceStart);
        VkFrames.onFenceWaited(currentFrame);

        MemoryManager.getInstance().initFrame(currentFrame);
        drawer.setCurrentFrame(currentFrame);

        resetDescriptors();

        currentCmdBuffer = commandBuffers.get(currentFrame);
        vkResetCommandBuffer(currentCmdBuffer, 0);

        try (MemoryStack stack = stackPush()) {
            if (pacedFrame) {
                this.beginRenderPass(stack);
                VkFrames.onRecordingStarted(currentFrame);
                if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.mark(net.vulkanmod.visuals.dev.FrameRecorder.M_BEGIN_END);
                return;
            }

            IntBuffer pImageIndex = stack.mallocInt(1);

            long acquireStart = net.vulkanmod.visuals.dev.FrameRecorder.ON ? System.nanoTime() : 0;
            int vkResult = net.vulkanmod.vulkan.framebuffer.SwapchainFunctions.current().acquire(device.address(), Vulkan.getSwapChain().getId(), VUtil.UINT64_MAX,
                    imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex);
            if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.time(net.vulkanmod.visuals.dev.FrameRecorder.ACQUIRE, acquireStart);
            //FSR design 4.4: FSR's acquire codes make the swapchain again (an error turns FSR off first) instead of ending the game
            if (net.vulkanmod.visuals.fsr.FsrSwapchain.live()) vkResult = fsrResult(vkResult, net.vulkanmod.visuals.fsr.FsrResults.acquire(vkResult), "acquire");

            if (vkResult == VK_SUBOPTIMAL_KHR || vkResult == VK_ERROR_OUT_OF_DATE_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                skipRendering = true;
                beginFrame();

                return;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Cannot acquire next swap chain image: %s".formatted(VkResult.decode(vkResult)));
            }

            imageIndex = pImageIndex.get(0);

            this.beginRenderPass(stack);
            VkFrames.onRecordingStarted(currentFrame);
        }
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.mark(net.vulkanmod.visuals.dev.FrameRecorder.M_BEGIN_END);
    }

    private void beginRenderPass(MemoryStack stack) {
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

        VkCommandBuffer commandBuffer = currentCmdBuffer;

        int vkResult = vkBeginCommandBuffer(commandBuffer, beginInfo);
        if (vkResult != VK_SUCCESS) {
            throw new RuntimeException("Failed to begin recording command buffer: %s".formatted(VkResult.decode(vkResult)));
        }

        recordingCmds = true;
        VkFrames.onFrameStart(getCommandBuffer(), currentFrame);
        mainPass.begin(commandBuffer, stack);

        resetDynamicState(commandBuffer);
    }

    public void endFrame() {
        if (skipRendering || !recordingCmds)
            return;

        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.mark(net.vulkanmod.visuals.dev.FrameRecorder.M_END_START);
        mainPass.end(currentCmdBuffer);

        submitFrame();
        recordingCmds = false;
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.mark(net.vulkanmod.visuals.dev.FrameRecorder.M_END_END);

        if (net.vulkanmod.compat.observer.CompatProfiler.ENABLED || Initializer.CONFIG.adaptiveChunkUploads) {
            long duration = System.nanoTime() - net.vulkanmod.compat.observer.CompatProfiler.cpuFrameStart;
            float cpuRenderTimeMs = duration * 0.000001f;
            if (Initializer.CONFIG.adaptiveChunkUploads) {
                AdaptiveChunkUploadBudget.recordFrameTimeMs(cpuRenderTimeMs);
            }
            if (!net.vulkanmod.compat.observer.CompatProfiler.ENABLED) {
                return;
            }
            net.vulkanmod.compat.observer.CompatProfiler.recordFrame(cpuRenderTimeMs);
            net.vulkanmod.compat.observer.CompatProfiler.resetFrameCounters();
        }
    }

    private void submitFrame() {
        //a paced frame does not touch the swapchain, so a pending recreation does not drop it (beginFrame recreates next)
        if (pacedFrame) {
            submitPaced();
            FrameGen.handOff();
            return;
        }

        if (swapChainUpdate)
            return;

        try (MemoryStack stack = stackPush()) {
            int vkResult;

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));

            submitInfo.pSignalSemaphores(stack.longs(renderFinishedSemaphores.get(currentFrame)));

            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            //FSR design 4.4 (final review I2): while FSR's swapchain is live, FSR submits this frame's generation and composition to the
            // game queue inside its present; the frame's fence is signalled after the present instead (an empty submit), so waiting on
            // it also waits for FSR's GPU work on this frame's resources
            boolean fsrFence = net.vulkanmod.visuals.fsr.FsrSwapchain.live();

            vkResetFences(device, inFlightFences.get(currentFrame));

            Synchronization.INSTANCE.waitFences();

            vkResult = net.vulkanmod.vulkan.queue.QueueLock.submit(DeviceManager.getGraphicsQueue().queue(), submitInfo, fsrFence ? VK_NULL_HANDLE : inFlightFences.get(currentFrame));
            VkFrames.onSubmitted();
            if (vkResult != VK_SUCCESS) {
                vkResetFences(device, inFlightFences.get(currentFrame));
                throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores.get(currentFrame)));

            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(Vulkan.getSwapChain().getId()));

            presentInfo.pImageIndices(stack.ints(imageIndex));

            //FSR hang (2026-10-25): FSR must not get this frame's interpolation before the presenter thread freed its output
            if (fsrFence) net.vulkanmod.visuals.fsr.FsrSwapchain.beforePresent();
            long presentStart = net.vulkanmod.visuals.dev.FrameRecorder.ON ? System.nanoTime() : 0;
            try {
                vkResult = net.vulkanmod.vulkan.queue.QueueLock.present(DeviceManager.getPresentQueue().queue(), presentInfo);
            } finally {
                if (fsrFence) signalAfterFsr(inFlightFences.get(currentFrame));
            }
            if (net.vulkanmod.visuals.dev.FrameRecorder.ON) {
                net.vulkanmod.visuals.dev.FrameRecorder.time(net.vulkanmod.visuals.dev.FrameRecorder.PRESENT, presentStart);
                FrameGen.directPresented(presentStart);
            }
            //FSR design 4.4: FSR's present codes; a skipped present is no error, an error turns FSR off and makes the swapchain again
            if (net.vulkanmod.visuals.fsr.FsrSwapchain.live()) vkResult = fsrResult(vkResult, net.vulkanmod.visuals.fsr.FsrResults.present(vkResult), "present");

            if (vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                return;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present rendered frame: %s".formatted(VkResult.decode(vkResult)));
            }

            currentFrame = (currentFrame + 1) % framesNum;
        }
    }

    /** An empty submit to the game queue: fence is signalled once everything submitted before it, FSR's present work included, is done. */
    private static void signalAfterFsr(long fence) {
        int vkResult = net.vulkanmod.vulkan.queue.QueueLock.submit(DeviceManager.getGraphicsQueue().queue(), 0, VK_NULL_HANDLE, fence);
        if (vkResult != VK_SUCCESS) {
            throw new RuntimeException("Failed to signal the frame fence after FSR's present: %s".formatted(VkResult.decode(vkResult)));
        }
    }

    /** The VkResult the code after FSR's acquire or present acts on: VK_SUCCESS for OK, VK_ERROR_OUT_OF_DATE_KHR otherwise. */
    private static int fsrResult(int vkResult, net.vulkanmod.visuals.fsr.FsrResults.Outcome outcome, String what) {
        switch (outcome) {
            case OK:
                return VK_SUCCESS;
            case FAIL:
                net.vulkanmod.visuals.fsr.FsrSupport.markFailed("FSR's " + what + " returned " + VkResult.decode(vkResult), null);
                return VK_ERROR_OUT_OF_DATE_KHR;
            default:
                return VK_ERROR_OUT_OF_DATE_KHR;
        }
    }

    /**
     * FG design 3.1: the paced submit. It waits on the pacer timeline until the pacer has finished reading this frame's capture slot
     * (SlotLedger.readValue: such a value is always submitted already) and signals the next render timeline value, which the pacer's
     * reads of this frame wait on. No image-available wait, no present.
     */
    private void submitPaced() {
        FramePacer pacer = FrameGen.pacer();
        try (MemoryStack stack = stackPush()) {
            long readValue = pacer.ledger().readValue(FrameGen.slot());
            long renderValue = pacer.nextRenderValue();

            VkTimelineSemaphoreSubmitInfo timeline = VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreValueCount(1).pWaitSemaphoreValues(stack.longs(readValue))
                    .signalSemaphoreValueCount(1).pSignalSemaphoreValues(stack.longs(renderValue));
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default().pNext(timeline)
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(pacer.pacerTimeline()))
                    .pWaitDstStageMask(stack.ints(FramePacer.RENDER_WAIT_STAGES))
                    .pSignalSemaphores(stack.longs(pacer.renderTimeline()))
                    .pCommandBuffers(stack.pointers(currentCmdBuffer));

            vkResetFences(device, inFlightFences.get(currentFrame));

            Synchronization.INSTANCE.waitFences();

            int vkResult = net.vulkanmod.vulkan.queue.QueueLock.submit(DeviceManager.getGraphicsQueue().queue(), submitInfo, inFlightFences.get(currentFrame));
            VkFrames.onSubmitted();
            if (vkResult != VK_SUCCESS) {
                vkResetFences(device, inFlightFences.get(currentFrame));
                throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }

            currentFrame = (currentFrame + 1) % framesNum;
        }
    }

    public void flushCmds() {
        if (!this.recordingCmds)
            return;

        try (MemoryStack stack = stackPush()) {
            int vkResult;

            this.endRenderPass(currentCmdBuffer);
            vkEndCommandBuffer(currentCmdBuffer);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            if (pacedFrame) {
                //the flushed commands may already write this frame's capture slot: wait for the pacer's reads of it as submitFrame does
                FramePacer pacer = FrameGen.pacer();
                submitInfo.pNext(VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                                .waitSemaphoreValueCount(1).pWaitSemaphoreValues(stack.longs(pacer.ledger().readValue(FrameGen.slot()))))
                        .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(pacer.pacerTimeline()))
                        .pWaitDstStageMask(stack.ints(FramePacer.RENDER_WAIT_STAGES));
            }

            vkResetFences(device, inFlightFences.get(currentFrame));

            Synchronization.INSTANCE.waitFences();

            if ((vkResult = net.vulkanmod.vulkan.queue.QueueLock.submit(DeviceManager.getGraphicsQueue().queue(), submitInfo, inFlightFences.get(currentFrame))) != VK_SUCCESS) {
                vkResetFences(device, inFlightFences.get(currentFrame));
                throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }

            vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);

            this.beginRenderPass(stack);
        }
    }

    public void endRenderPass() {
        endRenderPass(currentCmdBuffer);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        if (skipRendering || this.boundFramebuffer == null)
            return;

        if (!DYNAMIC_RENDERING)
            this.boundRenderPass.endRenderPass(currentCmdBuffer);
        else
            KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);

        this.boundRenderPass = null;
        this.boundFramebuffer = null;
        clearViewportScale();

        GlFramebuffer.resetBoundFramebuffer();
    }

    public boolean beginRendering(RenderPass renderPass, Framebuffer framebuffer) {
        if (skipRendering || !recordingCmds)
            return false;

        if (this.boundFramebuffer != framebuffer) {
            this.endRenderPass(currentCmdBuffer);

            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(currentCmdBuffer, renderPass, stack);
            }

            this.boundFramebuffer = framebuffer;
        }
        return true;
    }

    public void preInitFrame() {
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.frameStart();
        if (net.vulkanmod.compat.observer.CompatProfiler.ENABLED || Initializer.CONFIG.adaptiveChunkUploads) {
            net.vulkanmod.compat.observer.CompatProfiler.cpuFrameStart = System.nanoTime();
        }

        if (lastReset == currentFrame) {
            Synchronization.INSTANCE.waitFences();
        }
        lastReset = currentFrame;

        drawer.resetBuffers(currentFrame);

        Vulkan.getStagingBuffer().reset();

        long uploadStart = net.vulkanmod.visuals.dev.FrameRecorder.ON ? System.nanoTime() : 0;
        WorldRenderer.getInstance().uploadSections();
        UploadManager.INSTANCE.submitUploads();
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) {
            net.vulkanmod.visuals.dev.FrameRecorder.time(net.vulkanmod.visuals.dev.FrameRecorder.UPLOAD, uploadStart);
            net.vulkanmod.visuals.dev.FrameRecorder.mark(net.vulkanmod.visuals.dev.FrameRecorder.M_PRE_END);
        }
    }

    public void addUsedPipeline(Pipeline pipeline) {
        usedPipelines.add(pipeline);
    }

    public void removeUsedPipeline(Pipeline pipeline) {
        usedPipelines.remove(pipeline);
    }

    private void resetDescriptors() {
        for (Pipeline pipeline : usedPipelines) {
            pipeline.resetDescriptorPool(currentFrame);
        }

        usedPipelines.clear();
        boundPipeline = null;
        boundPipelineHandle = 0;

                                                                                     
                                                
        blendConstantsApplied = false;
        stencilStateApplied = false;
    }

    void waitForSwapChain() {
        //a paced frame acquired nothing: its image-available semaphore is never signalled
        if (pacedFrame)
            return;

        vkResetFences(device, inFlightFences.get(currentFrame));

        try (MemoryStack stack = MemoryStack.stackPush()) {

            VkSubmitInfo info = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));

            net.vulkanmod.vulkan.queue.QueueLock.submit(DeviceManager.getGraphicsQueue().queue(), info, inFlightFences.get(currentFrame));
            vkWaitForFences(device, inFlightFences.get(currentFrame), true, -1);
        }
    }

    @SuppressWarnings("UnreachableCode")
    private void recreateSwapChain() {
        //FG design 3.1 point 6: the pacer stops and drains first; it acquires and presents on the swapchain being replaced
        FrameGen.beforeSwapchainRecreate();
        Synchronization.INSTANCE.waitFences();
        //Vulkan.waitIdle waits for FSR's presents first while FSR's swapchain is live
        Vulkan.waitIdle();
        VkFrames.onDeviceIdle();

        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));

        //FSR design 4.4: also starts, stops or resizes FSR's frame-generation swapchain (device idle)
        net.vulkanmod.visuals.fsr.FsrSwapchain.recreate(Vulkan.getSwapChain());

        destroySyncObjects();

        int newFramesNum = Initializer.CONFIG.frameQueueSize;
        imagesNum = getSwapChain().getImagesNum();

        if (framesNum != newFramesNum) {
            UploadManager.INSTANCE.submitUploads();

            framesNum = newFramesNum;
            MemoryManager.createInstance(newFramesNum);
            createStagingBuffers();
            allocateCommandBuffers();

            Pipeline.recreateDescriptorSets(framesNum);

            drawer.createResources(framesNum);
        }

        createSyncObjects();

        this.onResizeCallbacks.forEach(Runnable::run);
        ((WindowAccessor) (Object) Minecraft.getInstance().getWindow()).getEventHandler().resizeDisplay();

        currentFrame = 0;

        FrameGen.afterSwapchainRecreate();
    }

    public void cleanUpResources() {
        destroySyncObjects();

        drawer.cleanUpResources();

        PipelineManager.destroyPipelines();
        VTextureSelector.getWhiteTexture().free();
    }

    private void destroySyncObjects() {
        for (int i = 0; i < framesNum; ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
            vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
        }
    }

    public void setBoundFramebuffer(Framebuffer framebuffer) {
        this.boundFramebuffer = framebuffer;
    }

    public void setBoundRenderPass(RenderPass boundRenderPass) {
        this.boundRenderPass = boundRenderPass;
    }

    public RenderPass getBoundRenderPass() {
        return boundRenderPass;
    }

    public void setMainPass(MainPass mainPass) {
        this.mainPass = mainPass;
    }

    public MainPass getMainPass() {
        return this.mainPass;
    }

    public void addOnResizeCallback(Runnable runnable) {
        this.onResizeCallbacks.add(runnable);
    }

    public void bindGraphicsPipeline(GraphicsPipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;

        PipelineState currentState = PipelineState.getCurrentPipelineState(boundRenderPass);
        final long handle = pipeline.getHandle(currentState);

        if (boundPipelineHandle != handle) {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
            boundPipelineHandle = handle;
            boundPipeline = pipeline;
            addUsedPipeline(pipeline);
        }

        float blendR = VRenderSystem.blendColor.get(0);
        float blendG = VRenderSystem.blendColor.get(1);
        float blendB = VRenderSystem.blendColor.get(2);
        float blendA = VRenderSystem.blendColor.get(3);
        if (!blendConstantsApplied
                || blendR != appliedBlendConstants[0]
                || blendG != appliedBlendConstants[1]
                || blendB != appliedBlendConstants[2]
                || blendA != appliedBlendConstants[3]) {
            vkCmdSetBlendConstants(commandBuffer, VRenderSystem.blendColor);
            appliedBlendConstants[0] = blendR;
            appliedBlendConstants[1] = blendG;
            appliedBlendConstants[2] = blendB;
            appliedBlendConstants[3] = blendA;
            blendConstantsApplied = true;
        }

        if (currentState.stencilTestEnabled()
                && boundRenderPass != null
                && VulkanImage.hasStencilComponent(boundRenderPass.getFramebuffer().getDepthFormat())) {
            int stencilRef = VRenderSystem.stencilRef;
            int stencilCompareMask = VRenderSystem.stencilFuncMask;
            int stencilWriteMask = VRenderSystem.stencilWriteMask;
            if (!stencilStateApplied
                    || stencilRef != appliedStencilRef
                    || stencilCompareMask != appliedStencilCompareMask
                    || stencilWriteMask != appliedStencilWriteMask) {
                vkCmdSetStencilReference(commandBuffer, VK_STENCIL_FACE_FRONT_AND_BACK, stencilRef);
                vkCmdSetStencilCompareMask(commandBuffer, VK_STENCIL_FACE_FRONT_AND_BACK, stencilCompareMask);
                vkCmdSetStencilWriteMask(commandBuffer, VK_STENCIL_FACE_FRONT_AND_BACK, stencilWriteMask);
                appliedStencilRef = stencilRef;
                appliedStencilCompareMask = stencilCompareMask;
                appliedStencilWriteMask = stencilWriteMask;
                stencilStateApplied = true;
            }
        }
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;
        if (GuiRenderTrace.isActive()) {
            boolean hasColorModulator = pipeline.getBuffers().stream()
                    .flatMap(ubo -> ubo.getUniforms().stream())
                    .anyMatch(uniform -> "ColorModulator".equals(uniform.getName()));
            GuiRenderTrace.logUboUpload("pipeline=" + pipeline.name
                    + " ColorModulator=" + hasColorModulator
                    + " alpha=" + VRenderSystem.getShaderColor().getFloat(12));
        }
        pipeline.bindDescriptorSets(commandBuffer, currentFrame);
    }

    public void pushConstants(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;

        PushConstants pushConstants = pipeline.getPushConstants();

        try (MemoryStack stack = stackPush()) {
            ByteBuffer buffer = stack.malloc(pushConstants.getSize());
            long ptr = MemoryUtil.memAddress0(buffer);
            pushConstants.update(ptr);

            nvkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants.getSize(), ptr);
        }

    }

    public Pipeline getBoundPipeline() {
        return boundPipeline;
    }

    /** The framebuffer the current render pass draws into, or null outside one (endRenderPass clears it). */
    public Framebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    /** Forgets the cached pipeline binding, for code that bound its own pipeline behind this renderer's back. */
    public void clearBoundPipeline() {
        boundPipeline = null;
        boundPipelineHandle = 0;
    }

    private static void resetDynamicState(VkCommandBuffer commandBuffer) {
        vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);

        vkCmdSetLineWidth(commandBuffer, 1.0F);
    }

    public static void setDepthBias(float units, float factor) {
        VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

        vkCmdSetDepthBias(commandBuffer, units, 0.0f, factor);
    }

    public static void clearAttachments(int v) {
        Framebuffer framebuffer = Renderer.getInstance().boundFramebuffer;
        if (framebuffer == null)
            return;

        clearAttachments(v, framebuffer.getWidth(), framebuffer.getHeight());
    }

    public static void clearAttachments(int v, int width, int height) {
        if (skipRendering)
            return;

        try (MemoryStack stack = stackPush()) {
            Framebuffer framebuffer = INSTANCE.boundFramebuffer;
            if (framebuffer == null)
                return;

            VkClearValue colorValue = VkClearValue.calloc(stack);
            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.calloc(stack);
            depthValue.depthStencil().set(VRenderSystem.clearDepthValue, VRenderSystem.clearStencilValue);

            boolean clearColor = (v & GL_COLOR_BUFFER_BIT) != 0;
            boolean clearDepth = (v & GL_DEPTH_BUFFER_BIT) != 0;
            boolean clearStencil = (v & GL_STENCIL_BUFFER_BIT) != 0
                    && VulkanImage.hasStencilComponent(framebuffer.getDepthFormat());
            boolean clearDepthStencil = clearDepth || clearStencil;

            int attachmentsCount = (clearColor ? 1 : 0) + (clearDepthStencil ? 1 : 0);
            if (attachmentsCount == 0)
                return;

            final VkClearAttachment.Buffer pAttachments = VkClearAttachment.malloc(attachmentsCount, stack);
            int attachmentIndex = 0;

            if (clearColor) {
                VkClearAttachment clearColorAttachment = pAttachments.get(attachmentIndex++);
                clearColorAttachment.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearColorAttachment.colorAttachment(0);
                clearColorAttachment.clearValue(colorValue);
            }

            if (clearDepthStencil) {
                int aspectMask = 0;
                if (clearDepth)
                    aspectMask |= VK_IMAGE_ASPECT_DEPTH_BIT;
                if (clearStencil)
                    aspectMask |= VK_IMAGE_ASPECT_STENCIL_BIT;

                VkClearAttachment clearDepthAttachment = pAttachments.get(attachmentIndex);
                clearDepthAttachment.aspectMask(aspectMask);
                clearDepthAttachment.colorAttachment(0);
                clearDepthAttachment.clearValue(depthValue);
            }

            VkRect2D renderArea = VkRect2D.malloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(width, height);

            VkClearRect.Buffer pRect = VkClearRect.malloc(1, stack);
            pRect.rect(renderArea);
            pRect.baseArrayLayer(0);
            pRect.layerCount(1);

            vkCmdClearAttachments(INSTANCE.currentCmdBuffer, pAttachments, pRect);
        }
    }

    public static void setInvertedViewport(int x, int y, int width, int height) {
        setViewport(x, y + height, width, -height);
    }

    public static void setViewport(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            setViewport(x, y, width, height, stack);
        }
    }

    public static void setViewport(int x, int y, int width, int height, MemoryStack stack) {
        if (!INSTANCE.recordingCmds)
            return;

        Framebuffer framebuffer = INSTANCE.boundFramebuffer;
        if (INSTANCE.shouldScaleViewportToBoundFramebuffer(framebuffer)) {
            x = INSTANCE.scaleViewportToBoundFramebuffer(x, INSTANCE.viewportLogicalWidth, framebuffer.getWidth());
            y = INSTANCE.scaleViewportToBoundFramebuffer(y, INSTANCE.viewportLogicalHeight, framebuffer.getHeight());
            width = INSTANCE.scaleViewportToBoundFramebuffer(width, INSTANCE.viewportLogicalWidth, framebuffer.getWidth());
            height = INSTANCE.scaleViewportToBoundFramebuffer(height, INSTANCE.viewportLogicalHeight, framebuffer.getHeight());
        }

        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(x);
        viewport.y(height + y);
        viewport.width(width);
        viewport.height(-height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        vkCmdSetViewport(INSTANCE.currentCmdBuffer, 0, viewport);
    }

    public static void setViewportScale(int logicalWidth, int logicalHeight) {
        INSTANCE.viewportLogicalWidth = logicalWidth;
        INSTANCE.viewportLogicalHeight = logicalHeight;
    }

    public static void clearViewportScale() {
        if (INSTANCE == null) {
            return;
        }

        INSTANCE.viewportLogicalWidth = 0;
        INSTANCE.viewportLogicalHeight = 0;
    }

    private boolean shouldScaleViewportToBoundFramebuffer(Framebuffer framebuffer) {
        return framebuffer != null
                && this.viewportLogicalWidth > 0
                && this.viewportLogicalHeight > 0
                && (framebuffer.getWidth() != this.viewportLogicalWidth
                || framebuffer.getHeight() != this.viewportLogicalHeight);
    }

    private int scaleViewportToBoundFramebuffer(int value, int logicalSize, int framebufferSize) {
        return Math.round(value * (framebufferSize / (float) logicalSize));
    }

    public static void resetViewport() {
        try (MemoryStack stack = stackPush()) {
            int width = getSwapChain().getWidth();
            int height = getSwapChain().getHeight();

            VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
            viewport.x(0.0f);
            viewport.y(height);
            viewport.width(width);
            viewport.height(-height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);

            vkCmdSetViewport(INSTANCE.currentCmdBuffer, 0, viewport);
        }
    }

    private static final int[] SCISSOR_BOX = new int[4];

    public static void setScissor(int x, int y, int width, int height) {

        SCISSOR_BOX[0] = x;
        SCISSOR_BOX[1] = y;
        SCISSOR_BOX[2] = width;
        SCISSOR_BOX[3] = height;

        if (INSTANCE == null || !INSTANCE.recordingCmds || INSTANCE.boundFramebuffer == null)
            return;

        try (MemoryStack stack = stackPush()) {
            int framebufferWidth = INSTANCE.boundFramebuffer.getWidth();
            int framebufferHeight = INSTANCE.boundFramebuffer.getHeight();

            int left = Math.max(0, x);
            int bottom = Math.max(0, y);
            int right = Math.min(framebufferWidth, x + Math.max(0, width));
            int top = Math.min(framebufferHeight, y + Math.max(0, height));
            int scissorWidth = Math.max(0, right - left);
            int scissorHeight = Math.max(0, top - bottom);

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(left, framebufferHeight - top);
            scissor.extent().set(scissorWidth, scissorHeight);

            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    public static void resetScissor() {
        if (INSTANCE == null || !INSTANCE.recordingCmds || INSTANCE.boundFramebuffer == null)
            return;

        try (MemoryStack stack = stackPush()) {
            VkRect2D.Buffer scissor = INSTANCE.boundFramebuffer.scissor(stack);
            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
            scissorEnabled = false;
        }
    }

    public static void setScissorEnabled(boolean enabled) {
        scissorEnabled = enabled;
        if (!enabled) {
            resetScissor();
        }
    }

    public static boolean isScissorEnabled() {
        return scissorEnabled;
    }

    public static int getScissorBox(int component) {
        return (component >= 0 && component < SCISSOR_BOX.length) ? SCISSOR_BOX[component] : 0;
    }

    public static void pushDebugSection(String s) {
        if (Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            try (MemoryStack stack = stackPush()) {
                VkDebugUtilsLabelEXT markerInfo = VkDebugUtilsLabelEXT.calloc(stack);
                markerInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT);
                ByteBuffer string = stack.UTF8(s);
                markerInfo.pLabelName(string);
                vkCmdBeginDebugUtilsLabelEXT(commandBuffer, markerInfo);
            }
        }
    }

    public static void popDebugSection() {
        if (Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            vkCmdEndDebugUtilsLabelEXT(commandBuffer);
        }
    }

    public static void popPushDebugSection(String s) {
        popDebugSection();
        pushDebugSection(s);
    }

    public static int getFramesNum() {
        return INSTANCE.framesNum;
    }

    public static VkCommandBuffer getCommandBuffer() {
        return INSTANCE.currentCmdBuffer;
    }

    public static boolean isRecording() {
        return INSTANCE.recordingCmds;
    }

    public static void scheduleSwapChainUpdate() {
        swapChainUpdate = true;
    }
}

