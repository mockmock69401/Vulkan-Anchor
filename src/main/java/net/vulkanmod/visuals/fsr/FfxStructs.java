package net.vulkanmod.visuals.fsr;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * FSR design 4.2: the ffx API descriptor structs written by hand. The SIZE_ and OFF_ constants are the MSVC x64
 * layouts printed by tools/fsr_layout_probe.c (tools/fsr_layout_probe.txt); FfxStructsTest compares them to it.
 * Every struct starts with a header {uint64 type; ptr pNext}; writers return the struct address, zeroed by
 * the stack allocation so unset fields are zero. Pointer-typed members take raw addresses; C bool is one byte.
 */
public final class FfxStructs {
    public static final int FORMAT_R8G8B8A8_UNORM = 10;
    public static final int FORMAT_B8G8R8A8_UNORM = 14;
    public static final int FORMAT_R16G16_FLOAT = 18;
    public static final int FORMAT_R32_FLOAT = 28;

    public static final int FG_DEPTH_INVERTED = 1 << 3;
    public static final int FG_DEPTH_INFINITE = 1 << 4;
    public static final int FG_DEBUG_CHECKING = 1 << 6;

    /** FfxApiDispatchFrameGenerationFlags (ffx_framegeneration.h): the debug drawings of configure's and prepare's flags. */
    public static final int FG_DRAW_DEBUG_TEAR_LINES = 1 << 0;
    public static final int FG_DRAW_DEBUG_VIEW = 1 << 2;
    public static final int FG_DRAW_DEBUG_PACING_LINES = 1 << 4;

    /** FfxApiResourceType, FfxApiResourceUsage and FfxApiResourceStates values (ffx_api_types.h). */
    public static final int RESOURCE_TYPE_TEXTURE2D = 2;
    public static final int RESOURCE_USAGE_READ_ONLY = 0;
    public static final int RESOURCE_STATE_COMPUTE_READ = 1 << 2;
    public static final int RESOURCE_STATE_PIXEL_COMPUTE_READ = (1 << 3) | (1 << 2);

    /** FfxApiMsgType and the global debug levels (ffx_api.h). */
    public static final int MESSAGE_TYPE_ERROR = 0;
    public static final int DEBUG_LEVEL_WARNINGS = 2;

    private static final long TYPE_GLOBAL_DEBUG = 0x1L;
    private static final long TYPE_BACKEND_VK = 0x3L;
    private static final long TYPE_CREATE_FRAME_GENERATION = 0x20001L;
    private static final long TYPE_CONFIGURE_FRAME_GENERATION = 0x20002L;
    private static final long TYPE_DISPATCH_FRAME_GENERATION = 0x20003L;
    private static final long TYPE_PREPARE = 0x20004L;
    private static final long TYPE_CREATE_HUDLESS = 0x20009L;
    private static final long TYPE_CAMERA_INFO = 0x2000aL;
    private static final long TYPE_CREATE_SWAPCHAIN_VK = 0x40001L;
    private static final long TYPE_CREATE_SWAPCHAIN_MODE_VK = 0x40010L;
    private static final long TYPE_QUERY_COMMAND_LIST = 0x40003L;
    private static final long TYPE_QUERY_TEXTURE = 0x40004L;
    private static final long TYPE_QUERY_FUNCTIONS = 0x40005L;
    private static final long TYPE_WAIT_FOR_PRESENTS = 0x40007L;

    /** One VkQueueInfoFFXAPI: the queue handle, its family and an optional submit function pointer. */
    public record QueueInfo(long queue, int family, long submitFunc) {}

    // Layouts printed by tools/fsr_layout_probe.c; regenerate from tools/fsr_layout_probe.txt, do not edit by hand.
    public static final int SIZE_FFX_CREATE_BACKEND_VK_DESC = 40;
    public static final int OFF_FFX_CREATE_BACKEND_VK_DESC_HEADER = 0;
    public static final int OFF_FFX_CREATE_BACKEND_VK_DESC_VK_DEVICE = 16;
    public static final int OFF_FFX_CREATE_BACKEND_VK_DESC_VK_PHYSICAL_DEVICE = 24;
    public static final int OFF_FFX_CREATE_BACKEND_VK_DESC_VK_DEVICE_PROC_ADDR = 32;

    public static final int SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION = 40;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_HEADER = 0;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_FLAGS = 16;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_DISPLAY_SIZE = 20;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_MAX_RENDER_SIZE = 28;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_BACK_BUFFER_FORMAT = 36;

    public static final int SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_HUDLESS = 24;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_HUDLESS_HEADER = 0;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_HUDLESS_HUDLESS_BACK_BUFFER_FORMAT = 16;

    public static final int SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_MODE_VK = 24;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_MODE_VK_HEADER = 0;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_MODE_VK_COMPOSE_ON_PRESENT_QUEUE = 16;

    public static final int SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK = 248;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_HEADER = 0;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_PHYSICAL_DEVICE = 16;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_DEVICE = 24;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_SWAPCHAIN = 32;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_ALLOCATOR = 40;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_CREATE_INFO = 48;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_GAME_QUEUE = 152;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_ASYNC_COMPUTE_QUEUE = 176;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_PRESENT_QUEUE = 200;
    public static final int OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_IMAGE_ACQUIRE_QUEUE = 224;

    public static final int SIZE_VK_QUEUE_INFO_FFXAPI = 24;
    public static final int OFF_VK_QUEUE_INFO_FFXAPI_QUEUE = 0;
    public static final int OFF_VK_QUEUE_INFO_FFXAPI_FAMILY_INDEX = 8;
    public static final int OFF_VK_QUEUE_INFO_FFXAPI_SUBMIT_FUNC = 16;

    public static final int SIZE_FFX_API_RESOURCE = 48;
    public static final int OFF_FFX_API_RESOURCE_RESOURCE = 0;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION = 8;
    public static final int OFF_FFX_API_RESOURCE_STATE = 40;

    public static final int SIZE_FFX_API_RESOURCE_DESCRIPTION = 32;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_TYPE = 0;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_FORMAT = 4;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_WIDTH = 8;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_HEIGHT = 12;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_DEPTH = 16;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_MIP_COUNT = 20;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_FLAGS = 24;
    public static final int OFF_FFX_API_RESOURCE_DESCRIPTION_USAGE = 28;

    public static final int SIZE_FFX_CONFIGURE_DESC_FRAME_GENERATION = 144;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_HEADER = 0;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_SWAP_CHAIN = 16;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_PRESENT_CALLBACK = 24;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_PRESENT_CALLBACK_USER_CONTEXT = 32;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_GENERATION_CALLBACK = 40;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_GENERATION_CALLBACK_USER_CONTEXT = 48;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_GENERATION_ENABLED = 56;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_ALLOW_ASYNC_WORKLOADS = 57;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_HUD_LESS_COLOR = 64;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FLAGS = 112;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_ONLY_PRESENT_GENERATED = 116;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_GENERATION_RECT = 120;
    public static final int OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_ID = 136;

    public static final int SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE = 184;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_HEADER = 0;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_ID = 16;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FLAGS = 24;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_COMMAND_LIST = 32;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_RENDER_SIZE = 40;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_JITTER_OFFSET = 48;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTOR_SCALE = 56;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_TIME_DELTA = 64;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_UNUSED_RESET = 68;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_NEAR = 72;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_FAR = 76;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_FOV_ANGLE_VERTICAL = 80;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_VIEW_SPACE_TO_METERS_FACTOR = 84;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_DEPTH = 88;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTORS = 136;

    public static final int SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO = 64;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_HEADER = 0;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_POSITION = 16;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_UP = 28;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_RIGHT = 40;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_FORWARD = 52;

    public static final int SIZE_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK = 72;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_HEADER = 0;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_CREATE_SWAPCHAIN_FFXAPI = 16;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_DESTROY_SWAPCHAIN_FFXAPI = 24;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_GET_SWAPCHAIN_IMAGES_KHR = 32;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_ACQUIRE_NEXT_IMAGE_KHR = 40;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_QUEUE_PRESENT_KHR = 48;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_SET_HDR_METADATA_EXT = 56;
    public static final int OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_GET_LAST_PRESENT_COUNT_FFXAPI = 64;

    public static final int SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION_SWAP_CHAIN_WAIT_FOR_PRESENTS_VK = 16;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_SWAP_CHAIN_WAIT_FOR_PRESENTS_VK_HEADER = 0;

    public static final int SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION = 312;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_HEADER = 0;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_COMMAND_LIST = 16;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PRESENT_COLOR = 24;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_OUTPUTS = 72;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_NUM_GENERATED_FRAMES = 264;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_RESET = 268;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_BACKBUFFER_TRANSFER_FUNCTION = 272;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_MIN_MAX_LUMINANCE = 276;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_GENERATION_RECT = 284;
    public static final int OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_FRAME_ID = 304;

    public static final int SIZE_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_COMMAND_LIST_VK = 24;
    public static final int OFF_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_COMMAND_LIST_VK_HEADER = 0;
    public static final int OFF_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_COMMAND_LIST_VK_P_OUT_COMMAND_LIST = 16;

    public static final int SIZE_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_TEXTURE_VK = 24;
    public static final int OFF_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_TEXTURE_VK_HEADER = 0;
    public static final int OFF_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_TEXTURE_VK_P_OUT_TEXTURE = 16;

    // ffxConfigureDescGlobalDebug1 {header; ffxApiMessage fpMessage; uint32_t debugLevel}: not in the probe (a pointer after the header)
    public static final int SIZE_FFX_CONFIGURE_DESC_GLOBAL_DEBUG1 = 32;
    public static final int OFF_FFX_CONFIGURE_DESC_GLOBAL_DEBUG1_FP_MESSAGE = 16;
    public static final int OFF_FFX_CONFIGURE_DESC_GLOBAL_DEBUG1_DEBUG_LEVEL = 24;

    private FfxStructs() {}

    private static long alloc(MemoryStack stack, int size, long type, long pNext) {
        long p = stack.ncalloc(8, 1, size);
        memPutLong(p, type);
        memPutLong(p + 8, pNext);
        return p;
    }

    /** Copies one FfxApiResource by value into a struct member. */
    public static void putResource(long dst, long src) {
        memCopy(src, dst, SIZE_FFX_API_RESOURCE);
    }

    private static void putRect(long dst, int width, int height) {
        memPutInt(dst, 0);
        memPutInt(dst + 4, 0);
        memPutInt(dst + 8, width);
        memPutInt(dst + 12, height);
    }

    private static void putVec3(long dst, float[] v) {
        for (int i = 0; i < 3; i++) {
            memPutFloat(dst + 4L * i, v[i]);
        }
    }

    private static void putQueue(long dst, QueueInfo q) {
        memPutAddress(dst + OFF_VK_QUEUE_INFO_FFXAPI_QUEUE, q.queue());
        memPutInt(dst + OFF_VK_QUEUE_INFO_FFXAPI_FAMILY_INDEX, q.family());
        memPutAddress(dst + OFF_VK_QUEUE_INFO_FFXAPI_SUBMIT_FUNC, q.submitFunc());
    }

    public static long backendVk(MemoryStack stack, VkDevice device, VkPhysicalDevice physicalDevice) {
        long p = alloc(stack, SIZE_FFX_CREATE_BACKEND_VK_DESC, TYPE_BACKEND_VK, 0);
        memPutAddress(p + OFF_FFX_CREATE_BACKEND_VK_DESC_VK_DEVICE, device.address());
        memPutAddress(p + OFF_FFX_CREATE_BACKEND_VK_DESC_VK_PHYSICAL_DEVICE, physicalDevice.address());
        memPutAddress(p + OFF_FFX_CREATE_BACKEND_VK_DESC_VK_DEVICE_PROC_ADDR,
                VK.getFunctionProvider().getFunctionAddress("vkGetDeviceProcAddr"));
        return p;
    }

    public static long createFrameGeneration(MemoryStack stack, long pNext, int flags, int displayW, int displayH,
                                             int maxRenderW, int maxRenderH, int backBufferFormat) {
        long p = alloc(stack, SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION, TYPE_CREATE_FRAME_GENERATION, pNext);
        memPutInt(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_FLAGS, flags);
        memPutInt(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_DISPLAY_SIZE, displayW);
        memPutInt(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_DISPLAY_SIZE + 4, displayH);
        memPutInt(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_MAX_RENDER_SIZE, maxRenderW);
        memPutInt(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_MAX_RENDER_SIZE + 4, maxRenderH);
        memPutInt(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_BACK_BUFFER_FORMAT, backBufferFormat);
        return p;
    }

    public static long createHudless(MemoryStack stack, long pNext, int format) {
        long p = alloc(stack, SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_HUDLESS, TYPE_CREATE_HUDLESS, pNext);
        memPutInt(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_HUDLESS_HUDLESS_BACK_BUFFER_FORMAT, format);
        return p;
    }

    /**
     * ffxCreateContextDescFrameGenerationSwapChainModeVK, chained into the swapchain desc: false composes on the game queue and
     * leaves FSR's present queue only the copy into the real swapchain image (FrameInterpolationSwapchainVK's copyAndPresent mode),
     * whatever the present queue's family can do.
     */
    public static long swapchainMode(MemoryStack stack, long pNext, boolean composeOnPresentQueue) {
        long p = alloc(stack, SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_MODE_VK, TYPE_CREATE_SWAPCHAIN_MODE_VK, pNext);
        memPutByte(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_MODE_VK_COMPOSE_ON_PRESENT_QUEUE, (byte) (composeOnPresentQueue ? 1 : 0));
        return p;
    }

    public static long createSwapchainVk(MemoryStack stack, long pNext, VkPhysicalDevice physicalDevice, VkDevice device,
                                         long pSwapchainHandle, VkSwapchainCreateInfoKHR info, QueueInfo game,
                                         QueueInfo async, QueueInfo present, QueueInfo acquire) {
        int infoSize = OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_GAME_QUEUE
                - OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_CREATE_INFO;
        if (infoSize != VkSwapchainCreateInfoKHR.SIZEOF) {
            throw new IllegalStateException("createInfo is " + infoSize + " bytes in the ffx header but "
                    + VkSwapchainCreateInfoKHR.SIZEOF + " in LWJGL");
        }
        long p = alloc(stack, SIZE_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK, TYPE_CREATE_SWAPCHAIN_VK, pNext);
        memPutAddress(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_PHYSICAL_DEVICE, physicalDevice.address());
        memPutAddress(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_DEVICE, device.address());
        memPutAddress(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_SWAPCHAIN, pSwapchainHandle);
        memCopy(info.address(), p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_CREATE_INFO,
                VkSwapchainCreateInfoKHR.SIZEOF);
        putQueue(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_GAME_QUEUE, game);
        putQueue(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_ASYNC_COMPUTE_QUEUE, async);
        putQueue(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_PRESENT_QUEUE, present);
        putQueue(p + OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_VK_IMAGE_ACQUIRE_QUEUE, acquire);
        return p;
    }

    /** An FfxApiResource for a Vulkan image; width, height and depth are the texture members of the unions. */
    public static long resource(MemoryStack stack, long vkImage, int type, int format, int width, int height,
                                int depth, int mips, int flags, int usage, int state) {
        long p = stack.ncalloc(8, 1, SIZE_FFX_API_RESOURCE);
        long d = p + OFF_FFX_API_RESOURCE_DESCRIPTION;
        memPutAddress(p + OFF_FFX_API_RESOURCE_RESOURCE, vkImage);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_TYPE, type);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_FORMAT, format);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_WIDTH, width);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_HEIGHT, height);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_DEPTH, depth);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_MIP_COUNT, mips);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_FLAGS, flags);
        memPutInt(d + OFF_FFX_API_RESOURCE_DESCRIPTION_USAGE, usage);
        memPutInt(p + OFF_FFX_API_RESOURCE_STATE, state);
        return p;
    }

    public static long configureFrameGeneration(MemoryStack stack, long swapchain, boolean enabled, boolean allowAsync,
                                                long hudLessResource, int flags, int rectW, int rectH, long frameId) {
        long p = alloc(stack, SIZE_FFX_CONFIGURE_DESC_FRAME_GENERATION, TYPE_CONFIGURE_FRAME_GENERATION, 0);
        memPutAddress(p + OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_SWAP_CHAIN, swapchain);
        memPutByte(p + OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_GENERATION_ENABLED, (byte) (enabled ? 1 : 0));
        memPutByte(p + OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_ALLOW_ASYNC_WORKLOADS, (byte) (allowAsync ? 1 : 0));
        if (hudLessResource != 0) {
            putResource(p + OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_HUD_LESS_COLOR, hudLessResource);
        }
        memPutInt(p + OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FLAGS, flags);
        putRect(p + OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_GENERATION_RECT, rectW, rectH);
        memPutLong(p + OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_ID, frameId);
        return p;
    }

    public static long prepare(MemoryStack stack, long pNext, long frameId, int flags, long cmd, int renderW, int renderH,
                               float mvScaleX, float mvScaleY, float frameTimeMs, float near, float far, float fovY,
                               float metersPerUnit, long depthResource, long mvResource) {
        long p = alloc(stack, SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE, TYPE_PREPARE, pNext);
        memPutLong(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_ID, frameId);
        memPutInt(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FLAGS, flags);
        memPutAddress(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_COMMAND_LIST, cmd);
        memPutInt(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_RENDER_SIZE, renderW);
        memPutInt(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_RENDER_SIZE + 4, renderH);
        memPutFloat(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTOR_SCALE, mvScaleX);
        memPutFloat(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTOR_SCALE + 4, mvScaleY);
        memPutFloat(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_TIME_DELTA, frameTimeMs);
        memPutFloat(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_NEAR, near);
        memPutFloat(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_FAR, far);
        memPutFloat(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_FOV_ANGLE_VERTICAL, fovY);
        memPutFloat(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_VIEW_SPACE_TO_METERS_FACTOR, metersPerUnit);
        putResource(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_DEPTH, depthResource);
        putResource(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTORS, mvResource);
        return p;
    }

    public static long cameraInfo(MemoryStack stack, float[] pos, float[] up, float[] right, float[] forward) {
        long p = alloc(stack, SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO, TYPE_CAMERA_INFO, 0);
        putVec3(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_POSITION, pos);
        putVec3(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_UP, up);
        putVec3(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_RIGHT, right);
        putVec3(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_INFO_CAMERA_FORWARD, forward);
        return p;
    }

    /** ffxConfigureDescGlobalDebug1: where FSR's debug checks report (fpMessage(uint32_t type, const wchar_t*)) and from which level. */
    public static long globalDebug(MemoryStack stack, long fpMessage, int level) {
        long p = alloc(stack, SIZE_FFX_CONFIGURE_DESC_GLOBAL_DEBUG1, TYPE_GLOBAL_DEBUG, 0);
        memPutAddress(p + OFF_FFX_CONFIGURE_DESC_GLOBAL_DEBUG1_FP_MESSAGE, fpMessage);
        memPutInt(p + OFF_FFX_CONFIGURE_DESC_GLOBAL_DEBUG1_DEBUG_LEVEL, level);
        return p;
    }

    public static long queryReplacementFunctions(MemoryStack stack) {
        return alloc(stack, SIZE_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK, TYPE_QUERY_FUNCTIONS, 0);
    }

    public static long createSwapchain(long addr) {
        return memGetAddress(addr + OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_CREATE_SWAPCHAIN_FFXAPI);
    }

    public static long destroySwapchain(long addr) {
        return memGetAddress(addr + OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_DESTROY_SWAPCHAIN_FFXAPI);
    }

    public static long getSwapchainImages(long addr) {
        return memGetAddress(addr + OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_GET_SWAPCHAIN_IMAGES_KHR);
    }

    public static long acquireNextImage(long addr) {
        return memGetAddress(addr + OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_ACQUIRE_NEXT_IMAGE_KHR);
    }

    public static long queuePresent(long addr) {
        return memGetAddress(addr + OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_QUEUE_PRESENT_KHR);
    }

    public static long lastPresentCount(long addr) {
        return memGetAddress(addr + OFF_FFX_QUERY_DESC_SWAPCHAIN_REPLACEMENT_FUNCTIONS_VK_P_OUT_GET_LAST_PRESENT_COUNT_FFXAPI);
    }

    public static long waitForPresents(MemoryStack stack) {
        return alloc(stack, SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION_SWAP_CHAIN_WAIT_FOR_PRESENTS_VK, TYPE_WAIT_FOR_PRESENTS, 0);
    }

    public static long queryInterpolationCommandList(MemoryStack stack, long pOutCommandBuffer) {
        long p = alloc(stack, SIZE_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_COMMAND_LIST_VK, TYPE_QUERY_COMMAND_LIST, 0);
        memPutAddress(p + OFF_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_COMMAND_LIST_VK_P_OUT_COMMAND_LIST, pOutCommandBuffer);
        return p;
    }

    public static long queryInterpolationTexture(MemoryStack stack, long pOutResource) {
        long p = alloc(stack, SIZE_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_TEXTURE_VK, TYPE_QUERY_TEXTURE, 0);
        memPutAddress(p + OFF_FFX_QUERY_DESC_FRAME_GENERATION_SWAP_CHAIN_INTERPOLATION_TEXTURE_VK_P_OUT_TEXTURE, pOutResource);
        return p;
    }

    public static long dispatchFrameGeneration(MemoryStack stack, long cmd, long presentColorResource, long outputResource,
                                               boolean reset, int rectW, int rectH, long frameId) {
        long p = alloc(stack, SIZE_FFX_DISPATCH_DESC_FRAME_GENERATION, TYPE_DISPATCH_FRAME_GENERATION, 0);
        memPutAddress(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_COMMAND_LIST, cmd);
        putResource(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PRESENT_COLOR, presentColorResource);
        putResource(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_OUTPUTS, outputResource);
        memPutInt(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_NUM_GENERATED_FRAMES, 1);
        memPutByte(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_RESET, (byte) (reset ? 1 : 0));
        memPutInt(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_BACKBUFFER_TRANSFER_FUNCTION, 0);
        putRect(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_GENERATION_RECT, rectW, rectH);
        memPutLong(p + OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_FRAME_ID, frameId);
        return p;
    }
}
