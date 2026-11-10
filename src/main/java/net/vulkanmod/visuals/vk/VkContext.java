package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.VisualsLog;
import java.util.function.LongSupplier;
import org.lwjgl.vulkan.EXTMemoryBudget;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceMaintenance3Properties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import java.util.Set;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.mixin.visuals.LightTextureAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;

/** Every place the visuals' Vulkan backend touches VulkanMod-Reforged; keep Reforged coupling here. */
public final class VkContext {
    private VkContext() {
    }

    public static VkDevice device() {
        return DeviceManager.vkDevice;
    }

    public static long allocator() {
        return Vulkan.getAllocator();
    }

    public static VkCommandBuffer frameCommands() {
        return Renderer.getCommandBuffer();
    }

    public static int framesInFlight() {
        return Renderer.getFramesNum();
    }

    /** What Shader.Builder.compile would define on this machine: IS_INTEL from the Vulkan device vendor, IS_WINDOWS from the OS. */
    public static ShaderCatalog.Platform shaderPlatform() {
        return new ShaderCatalog.Platform(DeviceManager.deviceProperties.vendorID() == INTEL_VENDOR_ID, System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
    }

    private static final int INTEL_VENDOR_ID = 0x8086;

    /** Largest storage buffer binding range on this device, in bytes. */
    public static long maxStorageBufferRange() {
        return DeviceManager.deviceProperties.limits().maxStorageBufferRange() & 0xFFFFFFFFL;
    }

    public static int maxImageDimension2D() {
        return DeviceManager.deviceProperties.limits().maxImageDimension2D();
    }

    public static long maxDrawIndirectCount() {
        return DeviceManager.deviceProperties.limits().maxDrawIndirectCount() & 0xFFFFFFFFL;
    }

    /** P7f: nanoseconds per timestamp tick. */
    public static float timestampPeriod() {
        return DeviceManager.deviceProperties.limits().timestampPeriod();
    }

    /** P7f: valid timestamp bits on Reforged's graphics queue family (0: no timestamps there). */
    public static int graphicsTimestampBits() {
        int family = net.vulkanmod.vulkan.queue.Queue.getQueueFamilies().graphicsFamily;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, count, null);
            var props = org.lwjgl.vulkan.VkQueueFamilyProperties.malloc(count.get(0), stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, count, props);
            return family >= 0 && family < props.capacity() ? props.get(family).timestampValidBits() : 0;
        }
    }

    /**
     * Waits until the GPU finished everything submitted (resource teardown). While FSR's swapchain is live only VulkanMod's own
     * queues are waited for (FSR design 4.4): the visuals give FSR's queues nothing, and a device wait would make FSR wait for its
     * presents in the middle of a frame.
     */
    public static void waitIdle() {
        if (net.vulkanmod.visuals.fsr.FsrSwapchain.live()) {
            Vulkan.waitOwnQueuesIdle();
            return;
        }
        Vulkan.waitIdle();
    }

    /** Compute and transfer work cannot be recorded inside a render pass. */
    public static void endMainPass() {
        Renderer.getInstance().endRenderPass();
    }

    /** Restarts the main pass with LOAD, keeping what was drawn this frame. */
    public static void resumeMainPass() {
        Renderer.getInstance().getMainPass().rebindMainTarget();
    }

    public static long boundRenderPassHandle() {
        RenderPass pass = Renderer.getInstance().getBoundRenderPass();
        return pass == null ? 0L : pass.getId();
    }

    /** Compatibility of the bound pass (its framebuffer's colour and depth formats); 0 without one. */
    public static long boundRenderPassKey() {
        RenderPass pass = Renderer.getInstance().getBoundRenderPass();
        if (pass == null) return 0L;
        Framebuffer framebuffer = pass.getFramebuffer();
        return PerRenderPassCache.compatibilityKey(framebuffer.getFormat(), framebuffer.getDepthFormat());
    }

    public static void invalidatePipelineCache() {
        Renderer.getInstance().clearBoundPipeline();
    }

    /**
     * 2026-11-10 (session 7): SPIR-V by spirvKey, compiled once a game run. The visuals renderer is rebuilt with the world renderer
     * (render distance, graphics settings, F3+A) and compiled every program again each time, inside that frame: with the shader
     * sessions' larger composite, clouds, aurora and weather, a render distance change stalled about 0.8 s longer than before them.
     * The same source gives the same bytes, so a rebuild now reuses them. Each entry is a copy that is never freed (a few MB at most:
     * one per program and stage).
     */
    private static final java.util.Map<String, ByteBuffer> SPIRV = new java.util.HashMap<>();

    /** SPIR-V bytes; the returned buffer stays valid for the game run (shader modules are created right after). */
    public static synchronized ByteBuffer spirv(String name, String source, SPIRVUtils.ShaderKind kind) {
        String key = spirvKey(name, source, kind.name());
        ByteBuffer cached = SPIRV.get(key);
        if (cached == null) {
            ByteBuffer code = SPIRVUtils.compileShader(name, source, kind).bytecode();
            cached = org.lwjgl.system.MemoryUtil.memAlloc(code.remaining());
            cached.put(code.duplicate()).flip();
            SPIRV.put(key, cached);
        }
        return cached.duplicate();
    }

    /** Session 7: the SPIR-V cache's key: the stage kind, the name and a SHA-256 of the whole source (defines included). */
    static String spirvKey(String name, String source, String kind) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return kind + ' ' + name + ' ' + java.util.HexFormat.of().formatHex(hash) + ' ' + source.length();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reforged's main framebuffer as the visuals uses it; callers pass it back here and never see Reforged types. */
    public static final class MainTarget {
        private final VulkanImage depth, colour;
        private final int width, height;

        private MainTarget(VulkanImage depth, VulkanImage colour, int width, int height) {
            this.depth = depth;
            this.colour = colour;
            this.width = width;
            this.height = height;
        }

        public int width() {
            return this.width;
        }

        public int height() {
            return this.height;
        }

        /** Reforged replaces the depth image object on resize; views of it are cached by this identity. */
        public Object identity() {
            return this.depth;
        }
    }

    /** The framebuffer Reforged's current render pass draws into, or null; endMainPass forgets it, so read it before. */
    public static MainTarget captureMainTarget() {
        Framebuffer framebuffer = Renderer.getInstance().getBoundFramebuffer();
        if (framebuffer == null || framebuffer.getDepthAttachment() == null || framebuffer.getColorAttachment() == null) return null;
        return new MainTarget(framebuffer.getDepthAttachment(), framebuffer.getColorAttachment(), framebuffer.getWidth(), framebuffer.getHeight());
    }

    /** Outside a render pass: the main depth becomes sampleable; Reforged's next begin of the main pass moves it back. */
    public static void mainDepthToShaderRead(VkCommandBuffer cmd, MainTarget target) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.depth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    /** Reforged is recording this frame's commands (not skipping it, as while the window is minimised). */
    public static boolean recordingFrame() {
        return !Renderer.skipRendering && Renderer.isRecording();
    }

    /** Outside a render pass: the main colour becomes sampleable (the post-process copy reads it). */
    public static void mainColourToShaderRead(VkCommandBuffer cmd, MainTarget target) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.colour.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    /** Outside a render pass: the main colour back to an attachment, as Reforged's aux pass (resumeMainPass) expects it. */
    public static void mainColourToAttachment(VkCommandBuffer cmd, MainTarget target) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.colour.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
    }

    /** Reforged's own view of the main colour (a colour image, sampleable as it is). */
    public static long mainColourView(MainTarget target) {
        return target.colour.getImageView();
    }

    /** A depth-only view of the main depth image (MainTarget.identity()); a depth/stencil view cannot be sampled. */
    public static long createMainDepthView(Object identity) {
        VulkanImage image = (VulkanImage) identity;
        return VkImage2D.createView(image.getId(), image.format, VK_IMAGE_ASPECT_DEPTH_BIT, 1);
    }

    /** Outside a render pass: vanilla's lightmap (LightTexture's DynamicTexture) in SHADER_READ_ONLY_OPTIMAL; returns its view. */
    public static long lightmapView(VkCommandBuffer cmd) {
        DynamicTexture texture = ((LightTextureAccessor) Minecraft.getInstance().gameRenderer.lightTexture()).visuals$getLightTexture();
        VulkanImage image = GlTexture.getTexture(texture.getId()).getVulkanImage();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.readOnlyLayout(stack, cmd);
        }
        return image.getImageView();
    }

    /**
     * Vanilla's lightmap view without a layout change, so it can be read inside a render pass (the frame's FrameContext);
     * lightmapView(cmd) outside the pass makes the same view readable.
     */
    public static long lightmapImageView() {
        DynamicTexture texture = ((LightTextureAccessor) Minecraft.getInstance().gameRenderer.lightTexture()).visuals$getLightTexture();
        return GlTexture.getTexture(texture.getId()).getVulkanImage().getImageView();
    }

    /** The depth format Reforged gives its own framebuffers (attachment and sampled). */
    public static int depthFormat() {
        return Vulkan.getDefaultDepthFormat();
    }

    /** The largest device-local memory heap: SSAO's AUTO mode picks by dedicated VRAM (Capabilities.totalDedicatedMemory on GL). */
    public static long deviceLocalMemoryBytes() {
        VkPhysicalDeviceMemoryProperties props = DeviceManager.memoryProperties;
        long[] sizes = new long[props.memoryHeapCount()];
        int[] flags = new int[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = props.memoryHeaps(i).size();
            flags[i] = props.memoryHeaps(i).flags();
        }
        return largestDeviceLocal(sizes, flags);
    }

    /** Free memory of the largest device-local heap (VK_EXT_memory_budget, a physical device query), or -1 when unknown. */
    public static long freeDeviceLocalMemoryBytes() {
        return guardedQuery(VkContext::queryFreeDeviceLocalMemoryBytes);
    }

    static long guardedQuery(LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Vulkan: free VRAM query failed (" + e + "), sizing the geometry pool from the heap size");
            return -1;
        }
    }

    private static long queryFreeDeviceLocalMemoryBytes() {
        if (!DeviceManager.device.getUnsupportedExtensions(Set.of(EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME)).isEmpty()) return -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryBudgetPropertiesEXT budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceMemoryProperties2 props = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budget);
            VK11.vkGetPhysicalDeviceMemoryProperties2(DeviceManager.physicalDevice, props);
            int count = props.memoryProperties().memoryHeapCount();
            long[] sizes = new long[count], budgets = new long[count], usages = new long[count];
            int[] flags = new int[count];
            for (int i = 0; i < count; i++) {
                sizes[i] = props.memoryProperties().memoryHeaps(i).size();
                flags[i] = props.memoryProperties().memoryHeaps(i).flags();
                budgets[i] = budget.heapBudget(i);
                usages[i] = budget.heapUsage(i);
            }
            return freeOfLargestDeviceLocal(sizes, flags, budgets, usages);
        }
    }

    static long freeOfLargestDeviceLocal(long[] heapSizes, int[] heapFlags, long[] budgets, long[] usages) {
        int best = -1;
        for (int i = 0; i < heapSizes.length; i++) {
            if ((heapFlags[i] & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 && (best < 0 || heapSizes[i] > heapSizes[best])) best = i;
        }
        return best < 0 ? -1 : Math.max(0, budgets[best] - usages[best]);
    }

    /** The largest single allocation the device accepts (Vulkan 1.1 maintenance3). */
    public static long maxMemoryAllocationSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMaintenance3Properties m3 = VkPhysicalDeviceMaintenance3Properties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(m3);
            VK11.vkGetPhysicalDeviceProperties2(DeviceManager.physicalDevice, props);
            return m3.maxMemoryAllocationSize();
        }
    }

    public static boolean isDiscreteGpu() {
        return isDiscrete(DeviceManager.deviceProperties.deviceType());
    }

    static boolean isDiscrete(int deviceType) {
        return deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
    }

    static long largestDeviceLocal(long[] heapSizes, int[] heapFlags) {
        long best = 0;
        for (int i = 0; i < heapSizes.length; i++) {
            if ((heapFlags[i] & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) best = Math.max(best, heapSizes[i]);
        }
        return best;
    }
}
