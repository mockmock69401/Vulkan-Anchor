package net.vulkanmod.vulkan.device;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.compat.gl.GlPatchState;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.vk.VkFeatureChain;
import net.vulkanmod.visuals.vk.VkFeaturePlan;
import net.vulkanmod.visuals.dh.DhPresence;
import net.vulkanmod.visuals.fsr.FsrQueuePlan;
import net.vulkanmod.visuals.fsr.FsrSupport;
import net.vulkanmod.visuals.vk.VkFeatures;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.MainDepthSnapshot;
import net.vulkanmod.vulkan.queue.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.queue.Queue.findQueueFamilies;
import static net.vulkanmod.vulkan.util.VUtil.asPointerBuffer;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public abstract class DeviceManager {
    public static List<Device> availableDevices;
    public static List<Device> suitableDevices;

    public static VkPhysicalDevice physicalDevice;
    public static VkDevice vkDevice;

    public static Device device;
    private static boolean tessellationEnabled;
    private static boolean geometryShaderEnabled;

    public static VkPhysicalDeviceProperties deviceProperties;
    public static VkPhysicalDeviceMemoryProperties memoryProperties;

    public static SurfaceProperties surfaceProperties;

    static GraphicsQueue graphicsQueue;
    static PresentQueue presentQueue;
    static TransferQueue transferQueue;
    static ComputeQueue computeQueue;

    static Optional<FsrQueues> fsrQueues = Optional.empty();

    public static void init(VkInstance instance) {
        try {
            DeviceManager.getSuitableDevices(instance);

            if (availableDevices.isEmpty()) {
                throw new RuntimeException("No Vulkan-capable GPU was detected.\n"
                        + net.vulkanmod.config.Platform.vulkanDriverHint());
            }
            if (suitableDevices.isEmpty()) {
                throw new RuntimeException("Found " + availableDevices.size()
                        + " GPU(s), but none support the required Vulkan extensions."
                        + getAvailableDevicesInfo());
            }

            DeviceManager.pickPhysicalDevice();
            DeviceManager.createLogicalDevice();
        } catch (Exception e) {
            Initializer.LOGGER.info(getAvailableDevicesInfo());
            throw new RuntimeException(e);
        }
    }

    static List<Device> getAvailableDevices(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            List<Device> devices = new ObjectArrayList<>();

            IntBuffer deviceCount = stack.ints(0);

            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                return List.of();
            }

            PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);

            VkPhysicalDevice currentDevice;

            for (int i = 0; i < ppPhysicalDevices.capacity(); i++) {
                currentDevice = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                Device device = new Device(currentDevice);
                devices.add(device);
            }

            return devices;
        }
    }

    static void getSuitableDevices(VkInstance instance) {
        availableDevices = getAvailableDevices(instance);

        List<Device> devices = new ObjectArrayList<>();
        for (Device device : availableDevices) {
            if (isDeviceSuitable(device.physicalDevice)) {
                devices.add(device);
            }
        }

        suitableDevices = devices;
    }

    public static void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {

            int deviceIdx = Initializer.CONFIG.device;
            if (deviceIdx >= 0 && deviceIdx < suitableDevices.size())
                DeviceManager.device = suitableDevices.get(deviceIdx);
            else {
                DeviceManager.device = autoPickDevice();
                Initializer.CONFIG.device = -1;
            }

            physicalDevice = DeviceManager.device.physicalDevice;

            deviceProperties = device.properties;

            memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

            surfaceProperties = querySurfaceProperties(physicalDevice, stack);

            Initializer.LOGGER.info(
                    "Selected Vulkan device: {} ({}) driver {} Vulkan {} indirectDrawSupported={} fastIndirectDraw={}",
                    device.deviceName,
                    device.vendorIdString,
                    device.driverVersion,
                    device.vkVersion,
                    device.isDrawIndirectSupported(),
                    supportsFastIndirectDraw()
            );
        }
    }

    public static boolean supportsFastIndirectDraw() {
        return device != null && device.isDrawIndirectSupported() && !device.isIntel();
    }

    public static boolean isGeometryShaderEnabled() {
        return geometryShaderEnabled && vkDevice != null;
    }

    public static boolean isTessellationEnabled() {
        return tessellationEnabled && vkDevice != null;
    }

    public static int maxTessellationPatchSize() {
        return isTessellationEnabled() && deviceProperties != null
                ? deviceProperties.limits().maxTessellationPatchSize() : 0;
    }

    static Device autoPickDevice() {
        ArrayList<Device> integratedGPUs = new ArrayList<>();
        ArrayList<Device> otherDevices = new ArrayList<>();

        boolean flag = false;

        Device currentDevice = null;
        for (Device device : suitableDevices) {
            currentDevice = device;

            int deviceType = device.properties.deviceType();
            if (deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                flag = true;
                break;
            } else if (deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
                integratedGPUs.add(device);
            else
                otherDevices.add(device);
        }

        if (!flag) {
            if (!integratedGPUs.isEmpty())
                currentDevice = integratedGPUs.get(0);
            else if (!otherDevices.isEmpty())
                currentDevice = otherDevices.get(0);
            else {
                throw new IllegalStateException("Failed to find a suitable GPU");
            }
        }

        return currentDevice;
    }

    public static void createLogicalDevice() {
        tessellationEnabled = false;
        geometryShaderEnabled = false;
        GlPatchState.configureLimit(0);
        try (MemoryStack stack = stackPush()) {

            net.vulkanmod.vulkan.queue.Queue.QueueFamilyIndices indices = findQueueFamilies(physicalDevice);

            int[] uniqueQueueFamilies = indices.unique();

            //FSR design 4.2: FSR's queues and features only when FSR is possible; otherwise one queue per family, as before
            FsrSupport.probe(physicalDevice);
            Optional<FsrQueuePlan> fsrPlan = planFsrQueuesSafely(uniqueQueueFamilies, stack);
            FsrFeatures fsrFeatures = fsrPlan.isPresent() ? fsrFeaturesSafely(stack) : FsrFeatures.NONE;
            if (fsrPlan.isPresent() && !fsrFeatures.storageImageWithoutFormat()) {
                //AMD's shaders declare storage image reads and writes without a format: without both, no FSR queues or features
                FsrSupport.featuresReady(false);
                VisualsLog.info("Visuals FSR: shaderStorageImageReadWithoutFormat/WriteWithoutFormat missing -> " + FsrSupport.current());
                fsrPlan = Optional.empty();
                fsrFeatures = FsrFeatures.NONE;
            }
            int[] queueCounts = FsrQueuePlan.queueCounts(fsrPlan, uniqueQueueFamilies);
            int[] queueFamilies = familiesToCreate(uniqueQueueFamilies, queueCounts);

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(queueFamilies.length, stack);

            for (int i = 0; i < queueFamilies.length; i++) {
                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(queueFamilies[i]);
                queueCreateInfo.pQueuePriorities(queuePriorities(stack, queueCounts[queueFamilies[i]]));
            }

            VkPhysicalDeviceVulkan11Features deviceVulkan11Features = VkPhysicalDeviceVulkan11Features.calloc(stack);
            deviceVulkan11Features.sType$Default();
            deviceVulkan11Features.shaderDrawParameters(device.isDrawIndirectSupported());

            VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack);
            deviceFeatures.sType$Default();
            deviceFeatures.features().samplerAnisotropy(device.availableFeatures.features().samplerAnisotropy());
            deviceFeatures.features().logicOp(device.availableFeatures.features().logicOp());
            deviceFeatures.features().tessellationShader(device.availableFeatures.features().tessellationShader());
            deviceFeatures.features().geometryShader(device.availableFeatures.features().geometryShader());

            deviceFeatures.features().multiDrawIndirect(device.isDrawIndirectSupported());

            if (device.availableFeatures.features().wideLines()) {
                deviceFeatures.features().wideLines(true);
                VRenderSystem.canSetLineWidth = true;
            }

            VkFeaturePlan visualsPlan = visualsFeaturePlan(stack, fsrFeatures);
            enableVisualsCoreFeatures(visualsPlan, deviceFeatures.features());
            if (fsrFeatures.storageImageWithoutFormat()) {
                deviceFeatures.features().shaderStorageImageReadWithoutFormat(true);
                deviceFeatures.features().shaderStorageImageWriteWithoutFormat(true);
            }
            if (VkFeatures.depthBounds()) {
                deviceFeatures.features().depthBounds(true);
            }
            //Reforged sets it only when its own indirect draws are on; the visuals' LOD terrain shader needs it for gl_BaseInstance
            if (visualsPlan.shaderDrawParameters()) {
                deviceVulkan11Features.shaderDrawParameters(true);
            }
            VkPhysicalDeviceVulkan12Features visualsFeatures12 = null;
            if (visualsPlan.drawIndirectCount() || visualsPlan.timelineSemaphore() || fsrFeatures.anyVulkan12()) {
                visualsFeatures12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
                if (visualsPlan.drawIndirectCount()) {
                    visualsFeatures12.drawIndirectCount(true);
                }
                if (visualsPlan.timelineSemaphore()) {
                    visualsFeatures12.timelineSemaphore(true);
                }
                if (fsrFeatures.shaderFloat16()) {
                    visualsFeatures12.shaderFloat16(true);
                }
                if (fsrFeatures.storageBufferArrayNonUniformIndexing()) {
                    visualsFeatures12.shaderStorageBufferArrayNonUniformIndexing(true);
                }
            }

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(deviceFeatures.features());
            VkFeatureChain.attach(createInfo, deviceVulkan11Features, visualsFeatures12);
            //typed pNext prepends, so these go in front of the 1.2/1.1 structs
            if (fsrFeatures.subgroupExtension()) {
                createInfo.pNext(VkPhysicalDeviceSubgroupSizeControlFeatures.calloc(stack).sType$Default()
                        .subgroupSizeControl(fsrFeatures.subgroupSizeControl())
                        .computeFullSubgroups(fsrFeatures.computeFullSubgroups()));
            }
            if (fsrFeatures.synchronization2()) {
                createInfo.pNext(VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default().synchronization2(true));
            }

            if (Vulkan.DYNAMIC_RENDERING) {
                VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicRenderingFeaturesKHR = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack);
                dynamicRenderingFeaturesKHR.sType$Default();
                dynamicRenderingFeaturesKHR.dynamicRendering(true);

                deviceVulkan11Features.pNext(dynamicRenderingFeaturesKHR.address());

            }

            java.util.Set<String> deviceExtensions = new java.util.HashSet<>(Vulkan.REQUIRED_EXTENSION);
            if (device.isPortabilitySubset()) {
                deviceExtensions.add(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
            }
            if (fsrFeatures.subgroupExtension()) {
                deviceExtensions.add(VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME);
            }
            if (fsrFeatures.synchronization2()) {
                deviceExtensions.add(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
            }
            if (fsrPlan.isPresent()) {
                deviceExtensions.addAll(fsrBackendExtensions());
            }
            createInfo.ppEnabledExtensionNames(asPointerBuffer(deviceExtensions));

            createInfo.ppEnabledLayerNames(Vulkan.ENABLE_VALIDATION_LAYERS ? asPointerBuffer(Vulkan.VALIDATION_LAYERS) : null);

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);

            int res = vkCreateDevice(physicalDevice, createInfo, null, pDevice);
            Vulkan.checkResult(res, "Failed to create logical device");

            vkDevice = new VkDevice(pDevice.get(0), physicalDevice, createInfo, VK_API_VERSION_1_2);
            tessellationEnabled = deviceFeatures.features().tessellationShader();
            geometryShaderEnabled = deviceFeatures.features().geometryShader();
            GlPatchState.configureLimit(maxTessellationPatchSize());

            graphicsQueue = new GraphicsQueue(stack, indices.graphicsFamily);
            transferQueue = new TransferQueue(stack, indices.transferFamily);
            presentQueue = new PresentQueue(stack, indices.presentFamily);
            computeQueue = new ComputeQueue(stack, indices.computeFamily);

            //FSR's game queue is VulkanMod's graphics queue (index 0)
            fsrQueues = fsrPlan.map(p -> new FsrQueues(graphicsQueue.queue(), indices.graphicsFamily,
                    reservedQueue(stack, p.presentFamily(), p.presentIndex()), p.presentFamily(),
                    reservedQueue(stack, p.acquireFamily(), p.acquireIndex()), p.acquireFamily(),
                    p.computeFamily() < 0 ? null : reservedQueue(stack, p.computeFamily(), p.computeIndex()), p.computeFamily()));
        }
    }

    /** {@link #planFsrQueues} that never throws: device creation must not fail because of FSR (FSR is then impossible, QUEUES). */
    private static Optional<FsrQueuePlan> planFsrQueuesSafely(int[] usedFamilies, MemoryStack stack) {
        try {
            return planFsrQueues(usedFamilies, stack);
        } catch (RuntimeException | LinkageError e) {
            FsrSupport.queuesPlanned(false);
            VisualsLog.warn("Visuals FSR: planning FSR's queues failed -> " + FsrSupport.current(), e);
            return Optional.empty();
        }
    }

    /** {@link #fsrFeatures} that never throws: on a failure FSR gets no features, which makes it impossible (FEATURES). */
    private static FsrFeatures fsrFeaturesSafely(MemoryStack stack) {
        try {
            return fsrFeatures(stack);
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals FSR: reading the GPU's features for FSR failed", e);
            return FsrFeatures.NONE;
        }
    }

    /**
     * FSR design 4.2: plans FSR's queues when everything but the queues allows FSR, and records in {@link FsrSupport} whether
     * they could be placed. Empty when FSR is impossible here or no spare queues were found.
     */
    private static Optional<FsrQueuePlan> planFsrQueues(int[] usedFamilies, MemoryStack stack) {
        FsrSupport.queuesPlanned(true);
        if (FsrSupport.current() != FsrSupport.Reason.OK) {
            FsrSupport.queuesPlanned(false);
            return Optional.empty();
        }

        IntBuffer familyCount = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(familyCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, families);

        int[] flags = new int[families.capacity()];
        int[] counts = new int[families.capacity()];
        boolean[] presentSupport = new boolean[families.capacity()];
        IntBuffer supported = stack.ints(VK_FALSE);
        for (int i = 0; i < families.capacity(); i++) {
            flags[i] = families.get(i).queueFlags();
            counts[i] = families.get(i).queueCount();
            //a failed query leaves no stale answer from the family before
            supported.put(0, VK_FALSE);
            int result = vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, i, Vulkan.getSurface(), supported);
            presentSupport[i] = result == VK_SUCCESS && supported.get(0) == VK_TRUE;
        }

        Optional<FsrQueuePlan> plan = FsrQueuePlan.plan(flags, counts, presentSupport, usedFamilies);
        FsrSupport.queuesPlanned(plan.isPresent());
        VisualsLog.info("Visuals FSR: queues " + plan.map(FsrQueuePlan::toString).orElse("none") + " -> " + FsrSupport.current());
        return plan;
    }

    /** VulkanMod's families in their order, then the families only FSR's queues use. */
    private static int[] familiesToCreate(int[] uniqueFamilies, int[] queueCounts) {
        int[] families = Arrays.copyOf(uniqueFamilies, Math.max(uniqueFamilies.length, queueCounts.length));
        int n = uniqueFamilies.length;
        for (int family = 0; family < queueCounts.length; family++) {
            final int f = family;
            if (queueCounts[family] > 0 && Arrays.stream(uniqueFamilies).noneMatch(u -> u == f)) {
                families[n++] = family;
            }
        }
        return Arrays.copyOf(families, n);
    }

    private static FloatBuffer queuePriorities(MemoryStack stack, int count) {
        FloatBuffer priorities = stack.mallocFloat(count);
        for (int i = 0; i < count; i++) {
            priorities.put(i, 1.0f);
        }
        return priorities;
    }

    /** One of FSR's reserved queues; it gets no command pool, FSR submits to it itself. */
    private static VkQueue reservedQueue(MemoryStack stack, int family, int index) {
        return new Queue(stack, family, index, false) {
        }.queue();
    }

    /**
     * FSR design 4.2: FidelityFX's Vulkan backend treats the features it finds supported as enabled, so with FSR possible
     * these are enabled wherever the GPU reports them. The extension structs are only asked for when the extension is listed.
     */
    private static FsrFeatures fsrFeatures(MemoryStack stack) {
        Set<String> extensions = Device.getAvailableExtensions(physicalDevice);
        VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
        long chain = f12.address();
        VkPhysicalDeviceSubgroupSizeControlFeatures subgroup = null;
        if (extensions.contains(VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME)) {
            subgroup = VkPhysicalDeviceSubgroupSizeControlFeatures.calloc(stack).sType$Default().pNext(chain);
            chain = subgroup.address();
        }
        VkPhysicalDeviceSynchronization2FeaturesKHR sync2 = null;
        if (extensions.contains(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME)) {
            sync2 = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default().pNext(chain);
            chain = sync2.address();
        }
        VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(chain);
        VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, f2);
        return new FsrFeatures(subgroup != null && subgroup.subgroupSizeControl(),
                subgroup != null && subgroup.computeFullSubgroups(), f12.shaderFloat16(),
                sync2 != null && sync2.synchronization2(), f12.shaderStorageBufferArrayNonUniformIndexing(),
                f2.features().shaderStorageImageReadWithoutFormat() && f2.features().shaderStorageImageWriteWithoutFormat());
    }

    /**
     * FSR design 4.2: extensions whose functions FidelityFX's Vulkan backend fetches by their extension names whenever the GPU
     * lists them (ffx_vk.cpp, v1.1.4: vkGetBufferMemoryRequirements2KHR when VK_KHR_dedicated_allocation is listed, the AMD
     * buffer markers). Not enabled, vkGetDeviceProcAddr gives NULL for them and creating a context calls address 0.
     */
    private static List<String> fsrBackendExtensions() {
        Set<String> available = Device.getAvailableExtensions(physicalDevice);
        List<String> enable = new ArrayList<>();
        if (available.contains(KHRDedicatedAllocation.VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME)
                && available.contains(KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME)) {
            enable.add(KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);
            enable.add(KHRDedicatedAllocation.VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME);
        }
        if (available.contains(AMDBufferMarker.VK_AMD_BUFFER_MARKER_EXTENSION_NAME)) {
            enable.add(AMDBufferMarker.VK_AMD_BUFFER_MARKER_EXTENSION_NAME);
        }
        VisualsLog.info("Visuals FSR: backend extensions " + enable);
        return enable;
    }

    /** storageImageWithoutFormat: shaderStorageImageReadWithoutFormat and shaderStorageImageWriteWithoutFormat, which FSR needs. */
    private record FsrFeatures(boolean subgroupSizeControl, boolean computeFullSubgroups, boolean shaderFloat16,
                               boolean synchronization2, boolean storageBufferArrayNonUniformIndexing,
                               boolean storageImageWithoutFormat) {
        static final FsrFeatures NONE = new FsrFeatures(false, false, false, false, false, false);

        boolean subgroupExtension() {
            return subgroupSizeControl || computeFullSubgroups;
        }

        boolean anyVulkan12() {
            return shaderFloat16 || storageBufferArrayNonUniformIndexing;
        }

        String log() {
            return this == NONE ? "" : " fsr: subgroupSizeControl=" + subgroupSizeControl + " computeFullSubgroups="
                    + computeFullSubgroups + " shaderFloat16=" + shaderFloat16 + " synchronization2=" + synchronization2
                    + " shaderStorageBufferArrayNonUniformIndexing=" + storageBufferArrayNonUniformIndexing
                    + " shaderStorageImageRead/WriteWithoutFormat=" + storageImageWithoutFormat;
        }
    }

    /**
     * FSR design 4.2: the game queue FSR is given (VulkanMod's graphics queue) and the queues reserved for it; compute is
     * null and computeFamily -1 without an async compute queue.
     */
    public record FsrQueues(VkQueue game, int gameFamily, VkQueue present, int presentFamily, VkQueue acquire,
                            int acquireFamily, VkQueue compute, int computeFamily) {
    }

    /**
     * Reforged enables only the features vanilla rendering needs. The visuals' Vulkan code also wants drawIndirectCount
     * (Vulkan 1.2), shaderInt64, drawIndirectFirstInstance, multiDrawIndirect, shaderDrawParameters (Vulkan 1.1,
     * gl_BaseInstance), the vertex/fragment store features (storage buffer writes from raster stages) and independentBlend
     * (a pass whose second attachment blends differently from the colour one). It turns on whichever the GPU has, never more,
     * so device creation cannot fail.
     */
    private static VkFeaturePlan visualsFeaturePlan(MemoryStack stack, FsrFeatures fsrFeatures) {
        VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
        VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default().pNext(f12.address());
        VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f11.address());
        VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, f2);
        VkFeaturePlan plan = VkFeaturePlan.of(f12.drawIndirectCount(), f2.features().shaderInt64(),
                f2.features().drawIndirectFirstInstance(), f2.features().multiDrawIndirect(),
                f2.features().fragmentStoresAndAtomics(), f2.features().vertexPipelineStoresAndAtomics(), f11.shaderDrawParameters(),
                f2.features().independentBlend(), f12.timelineSemaphore());
        VkFeatures.set(plan);
        //The LOD composite's depth bounds test, only with a LOD renderer; without the feature it falls back to the depth test alone
        boolean lodRenderer = DhPresence.drawsLods();
        VkFeatures.setDepthBounds(VkFeatures.enablesDepthBounds(f2.features().depthBounds(), lodRenderer));
        VisualsLog.info("Vulkan device features: drawIndirectCount=" + plan.drawIndirectCount()
                + " shaderInt64=" + plan.shaderInt64() + " drawIndirectFirstInstance=" + plan.drawIndirectFirstInstance()
                + " multiDrawIndirect=" + plan.multiDrawIndirect() + " fragmentStoresAndAtomics=" + plan.fragmentStoresAndAtomics()
                + " vertexPipelineStoresAndAtomics=" + plan.vertexPipelineStoresAndAtomics()
                + " shaderDrawParameters=" + plan.shaderDrawParameters() + " independentBlend=" + plan.independentBlend()
                + " timelineSemaphore=" + plan.timelineSemaphore() + " missing=" + plan.missing() + fsrFeatures.log());
        return plan;
    }

    private static void enableVisualsCoreFeatures(VkFeaturePlan plan, VkPhysicalDeviceFeatures features) {
        if (plan.shaderInt64()) {
            features.shaderInt64(true);
        }
        if (plan.drawIndirectFirstInstance()) {
            features.drawIndirectFirstInstance(true);
        }
        if (plan.multiDrawIndirect()) {
            features.multiDrawIndirect(true);
        }
        if (plan.fragmentStoresAndAtomics()) {
            features.fragmentStoresAndAtomics(true);
        }
        if (plan.vertexPipelineStoresAndAtomics()) {
            features.vertexPipelineStoresAndAtomics(true);
        }
        if (plan.independentBlend()) {
            features.independentBlend(true);
        }
    }

    private static PointerBuffer getRequiredExtensions() {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

        if (Vulkan.ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);

            extensions.put(glfwExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            return extensions.rewind();
        }

        return glfwExtensions;
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {
            Queue.QueueFamilyIndices indices = findQueueFamilies(device);

            boolean extensionsSupported = Device.getAvailableExtensions(device)
                    .containsAll(Vulkan.REQUIRED_EXTENSION);

            boolean swapChainAdequate = false;

            if (extensionsSupported) {
                SurfaceProperties surfaceProperties = querySurfaceProperties(device, stack);
                swapChainAdequate = surfaceProperties.formats.hasRemaining() && surfaceProperties.presentModes.hasRemaining();
            }

            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(device, supportedFeatures);
            boolean anisotropicFilterSupported = supportedFeatures.samplerAnisotropy();

            return indices.isSuitable() && extensionsSupported && swapChainAdequate;
        }
    }

    public static int findDepthFormat(boolean use24BitsDepthFormat) {

        int[] formats = use24BitsDepthFormat ? new int[]
                {VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT}
                : new int[]{VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D32_SFLOAT};

        return findSupportedFormat(
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT,
                MainDepthSnapshot.depthAttachmentUsage(),
                formats);
    }

    private static int findSupportedFormat(int tiling, int features, int usage, int... formatCandidates) {
        try (MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.calloc(stack);
            VkImageFormatProperties imageProps = VkImageFormatProperties.calloc(stack);

            for (int format : formatCandidates) {

                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);

                boolean supportedFeatures = tiling == VK_IMAGE_TILING_LINEAR
                        ? (props.linearTilingFeatures() & features) == features
                        : tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features;
                if (supportedFeatures) {
                    int result = vkGetPhysicalDeviceImageFormatProperties(physicalDevice, format, VK_IMAGE_TYPE_2D,
                            tiling, usage, 0, imageProps);
                    if (result == VK_SUCCESS && (imageProps.sampleCounts() & VK_SAMPLE_COUNT_1_BIT) != 0) {
                        return format;
                    }
                }

            }
        }

        throw new RuntimeException("Failed to find supported format");
    }

    public static String getAvailableDevicesInfo() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("\n");

        if (availableDevices == null) {
            stringBuilder.append("\tDevice Manager not initialized");
            return stringBuilder.toString();
        }

        if (availableDevices.isEmpty()) {
            stringBuilder.append("\tNo available device found");
        }

        for (Device device : availableDevices) {
            stringBuilder.append("\tDevice: %s\n".formatted(device.deviceName));

            stringBuilder.append("\t\tVulkan Version: %s\n".formatted(device.vkVersion));

            stringBuilder.append("\t\t");
            var unsupportedExtensions = device.getUnsupportedExtensions(Vulkan.REQUIRED_EXTENSION);
            if (unsupportedExtensions.isEmpty()) {
                stringBuilder.append("All required extensions are supported\n");
            } else {
                stringBuilder.append("Unsupported extension: %s\n".formatted(unsupportedExtensions));
            }
        }

        return stringBuilder.toString();
    }

    public static void destroy() {
        try {
            graphicsQueue.cleanUp();
            transferQueue.cleanUp();
            computeQueue.cleanUp();

            vkDestroyDevice(vkDevice, null);
        } finally {
            vkDevice = null;
            tessellationEnabled = false;
            geometryShaderEnabled = false;
            GlPatchState.configureLimit(0);
        }
    }

    public static GraphicsQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public static PresentQueue getPresentQueue() {
        return presentQueue;
    }

    public static TransferQueue getTransferQueue() {
        return transferQueue;
    }

    public static ComputeQueue getComputeQueue() {
        return computeQueue;
    }

    /** FSR design 4.2: FSR's queues; empty when FSR was impossible or its queues could not be placed at device creation. */
    public static Optional<FsrQueues> getFsrQueues() {
        return fsrQueues;
    }

    public static SurfaceProperties querySurfaceProperties(VkPhysicalDevice device, MemoryStack stack) {

        long surface = Vulkan.getSurface();
        SurfaceProperties details = new SurfaceProperties();

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer count = stack.ints(0);

        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
        }

        return details;
    }

    public static class SurfaceProperties {
        public VkSurfaceCapabilitiesKHR capabilities;
        public VkSurfaceFormatKHR.Buffer formats;
        public IntBuffer presentModes;
    }

}
