package net.vulkanmod.vulkan.queue;

import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;

public class GraphicsQueue extends Queue {
    public static GraphicsQueue INSTANCE;

    /**
     * 2026-10-30 (audit): each thread's open recording, so another thread never takes over or ends the render thread's (the
     * deferral state was shared static fields, unsynchronised).
     */
    private static final ThreadLocal<CommandPool.CommandBuffer> CURRENT = new ThreadLocal<>();
    /**
     * 2026-10-27 (Society Sunlit Valley: FTB Chunks re-uploads its 240x240 minimap as 225 16x16 pieces several times a second,
     * each its own submit with a fence wait every 50, 5-10 ms frames while flying over new terrain): sub-image uploads made on the
     * render thread while a frame records share one command buffer, submitted before any other graphics-queue submit or fence
     * wait (the frame's own submit waits for transfers first), so the GPU still runs them before the frame's draws.
     */
    private static final ThreadLocal<Boolean> DEFERRED = ThreadLocal.withInitial(() -> false);

    public GraphicsQueue(MemoryStack stack, int familyIndex) {
        super(stack, familyIndex);
    }

    public void startRecording() {
        //an open deferred buffer becomes this recording's, submitted by its endRecordingAndSubmit
        if (CURRENT.get() != null && DEFERRED.get()) {
            DEFERRED.set(false);
            return;
        }
        CURRENT.set(beginCommands());
    }

    /** Before a sub-image upload: on the render thread while a frame records, opens the shared deferred buffer if none is open. */
    public void deferUploads(boolean frameRecording) {
        if (CURRENT.get() != null || !frameRecording || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())
            return;
        CURRENT.set(beginCommands());
        DEFERRED.set(true);
    }

    /** Submits the deferred uploads, if any were recorded on this thread. */
    public void flushDeferred() {
        if (DEFERRED.get() && CURRENT.get() != null)
            endRecordingAndSubmit();
    }

    @Override
    public synchronized long submitCommands(CommandPool.CommandBuffer commandBuffer) {
        if (commandBuffer == CURRENT.get()) {
            //the open recording itself (endRecordingAndSubmit, or a layout change that took it from getCommandBuffer)
            CURRENT.remove();
            DEFERRED.set(false);
        } else {
            flushDeferred();
        }
        return super.submitCommands(commandBuffer);
    }

    public void endRecordingAndSubmit() {
        CommandPool.CommandBuffer commandBuffer = CURRENT.get();
        if (commandBuffer == null)
            return;
        long fence = submitCommands(commandBuffer);
        Synchronization.INSTANCE.addCommandBuffer(commandBuffer);
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.add(net.vulkanmod.visuals.dev.FrameRecorder.GFX_SUBMITS, 1);

        CURRENT.remove();
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        CommandPool.CommandBuffer current = CURRENT.get();
        return current != null ? current : beginCommands();
    }

    public long endIfNeeded(CommandPool.CommandBuffer commandBuffer) {
        if (commandBuffer == CURRENT.get()) {
            return VK_NULL_HANDLE;
        } else {
            if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.add(net.vulkanmod.visuals.dev.FrameRecorder.GFX_SUBMITS, 1);
            return submitCommands(commandBuffer);
        }
    }

}

