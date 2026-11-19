package net.vulkanmod.vulkan.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkGetPipelineCacheData;

/**
 * 2026-11-19 (Tensura, a modded mob's first sighting): the pipeline cache on disk (config/vulkanmod_pipeline_cache.bin). A pipeline the
 * driver has never built takes 5-26 ms on the render thread (a mob's first new render state is a frame of that); measured on an NVIDIA
 * card, the next session builds it in about 0.6 ms from the driver's own shader cache and 0.1 ms from this one. The cache was written only
 * when the game closed normally, so a crash or a killed game lost every pipeline the session had built. Now it is also written on a
 * background thread once pipeline creation has settled (SETTLE_NANOS after the last new one), through a temporary file so a game killed
 * mid-write keeps the last good file.
 * vkGetPipelineCacheData may run beside pipeline creation: the cache is not created externally synchronized.
 */
public final class PipelineCacheFile {
    static final Path PATH = Path.of("config", "vulkanmod_pipeline_cache.bin");
    static final long SETTLE_NANOS = 10_000_000_000L;

    private static final AtomicInteger UNSAVED = new AtomicInteger();
    private static volatile long lastCreated;
    private static Thread saving;

    private PipelineCacheFile() {
    }

    /** A pipeline was built through the cache (any thread). */
    static void created() {
        lastCreated = System.nanoTime();
        UNSAVED.incrementAndGet();
    }

    /** Whether to write now: pipelines built since the last write, none for SETTLE_NANOS, and no write still running. */
    static boolean due(int unsaved, long now, long lastCreated, boolean writing) {
        return unsaved > 0 && !writing && now - lastCreated >= SETTLE_NANOS;
    }

    /** Once a frame on the render thread: starts a background write of the cache when due. */
    public static void tick(VkDevice device, long cache) {
        Thread running = saving;
        if (!due(UNSAVED.get(), System.nanoTime(), lastCreated, running != null && running.isAlive())) return;
        UNSAVED.set(0);
        Thread t = new Thread(() -> write(device, cache), "VulkanMod pipeline cache writer");
        t.setDaemon(true);
        saving = t;
        t.start();
    }

    /** Before the cache is destroyed: waits for a background write. False when it is still running (the cache must then be kept). */
    static boolean awaitWrite() {
        Thread t = saving;
        if (t == null) return true;
        try {
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /** Writes the cache's data to PATH (through PATH.tmp). */
    static void write(VkDevice device, long cache) {
        ByteBuffer data = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(device, cache, size, null) != VK_SUCCESS || size.get(0) <= 0) return;
            data = MemoryUtil.memAlloc((int) size.get(0));
            if (vkGetPipelineCacheData(device, cache, size, data) != VK_SUCCESS) return;
            byte[] bytes = new byte[(int) size.get(0)];
            data.get(0, bytes);
            Files.createDirectories(PATH.getParent());
            Path tmp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("[VulkanMod] Failed to write pipeline cache: " + e.getMessage());
        } finally {
            if (data != null) MemoryUtil.memFree(data);
        }
    }
}
