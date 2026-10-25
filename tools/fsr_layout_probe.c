/* Prints the MSVC x64 size of every ffx struct the Java writers use and the offset of each member. */
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <vulkan/vulkan.h>

/* ffx_api_vk.h names these structs without the "struct" keyword, which only C++ accepts; */
/* the typedefs let the headers compile as C with the same layouts. */
typedef struct VkQueueInfoFFXAPI VkQueueInfoFFXAPI;
typedef struct FfxApiResource FfxApiResource;
typedef struct FfxApiResourceDescription FfxApiResourceDescription;

#include "ffx_api/ffx_api.h"
#include "ffx_api/ffx_framegeneration.h"
#include "ffx_api/vk/ffx_api_vk.h"

#define SZ(t) printf("%s %u\n", #t, (unsigned) sizeof(struct t))
#define OFF(t, f) printf("%s.%s %u\n", #t, #f, (unsigned) offsetof(struct t, f))

int main(void) {
    SZ(ffxCreateBackendVKDesc);
    OFF(ffxCreateBackendVKDesc, header);
    OFF(ffxCreateBackendVKDesc, vkDevice);
    OFF(ffxCreateBackendVKDesc, vkPhysicalDevice);
    OFF(ffxCreateBackendVKDesc, vkDeviceProcAddr);
    SZ(ffxCreateContextDescFrameGeneration);
    OFF(ffxCreateContextDescFrameGeneration, header);
    OFF(ffxCreateContextDescFrameGeneration, flags);
    OFF(ffxCreateContextDescFrameGeneration, displaySize);
    OFF(ffxCreateContextDescFrameGeneration, maxRenderSize);
    OFF(ffxCreateContextDescFrameGeneration, backBufferFormat);
    SZ(ffxCreateContextDescFrameGenerationHudless);
    OFF(ffxCreateContextDescFrameGenerationHudless, header);
    OFF(ffxCreateContextDescFrameGenerationHudless, hudlessBackBufferFormat);
    SZ(ffxCreateContextDescFrameGenerationSwapChainVK);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, header);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, physicalDevice);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, device);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, swapchain);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, allocator);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, createInfo);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, gameQueue);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, asyncComputeQueue);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, presentQueue);
    OFF(ffxCreateContextDescFrameGenerationSwapChainVK, imageAcquireQueue);
    SZ(ffxCreateContextDescFrameGenerationSwapChainModeVK);
    OFF(ffxCreateContextDescFrameGenerationSwapChainModeVK, header);
    OFF(ffxCreateContextDescFrameGenerationSwapChainModeVK, composeOnPresentQueue);
    SZ(VkQueueInfoFFXAPI);
    OFF(VkQueueInfoFFXAPI, queue);
    OFF(VkQueueInfoFFXAPI, familyIndex);
    OFF(VkQueueInfoFFXAPI, submitFunc);
    SZ(FfxApiResource);
    OFF(FfxApiResource, resource);
    OFF(FfxApiResource, description);
    OFF(FfxApiResource, state);
    SZ(FfxApiResourceDescription);
    OFF(FfxApiResourceDescription, type);
    OFF(FfxApiResourceDescription, format);
    OFF(FfxApiResourceDescription, width);
    OFF(FfxApiResourceDescription, height);
    OFF(FfxApiResourceDescription, depth);
    OFF(FfxApiResourceDescription, mipCount);
    OFF(FfxApiResourceDescription, flags);
    OFF(FfxApiResourceDescription, usage);
    SZ(ffxConfigureDescFrameGeneration);
    OFF(ffxConfigureDescFrameGeneration, header);
    OFF(ffxConfigureDescFrameGeneration, swapChain);
    OFF(ffxConfigureDescFrameGeneration, presentCallback);
    OFF(ffxConfigureDescFrameGeneration, presentCallbackUserContext);
    OFF(ffxConfigureDescFrameGeneration, frameGenerationCallback);
    OFF(ffxConfigureDescFrameGeneration, frameGenerationCallbackUserContext);
    OFF(ffxConfigureDescFrameGeneration, frameGenerationEnabled);
    OFF(ffxConfigureDescFrameGeneration, allowAsyncWorkloads);
    OFF(ffxConfigureDescFrameGeneration, HUDLessColor);
    OFF(ffxConfigureDescFrameGeneration, flags);
    OFF(ffxConfigureDescFrameGeneration, onlyPresentGenerated);
    OFF(ffxConfigureDescFrameGeneration, generationRect);
    OFF(ffxConfigureDescFrameGeneration, frameID);
    SZ(ffxDispatchDescFrameGenerationPrepare);
    OFF(ffxDispatchDescFrameGenerationPrepare, header);
    OFF(ffxDispatchDescFrameGenerationPrepare, frameID);
    OFF(ffxDispatchDescFrameGenerationPrepare, flags);
    OFF(ffxDispatchDescFrameGenerationPrepare, commandList);
    OFF(ffxDispatchDescFrameGenerationPrepare, renderSize);
    OFF(ffxDispatchDescFrameGenerationPrepare, jitterOffset);
    OFF(ffxDispatchDescFrameGenerationPrepare, motionVectorScale);
    OFF(ffxDispatchDescFrameGenerationPrepare, frameTimeDelta);
    OFF(ffxDispatchDescFrameGenerationPrepare, unused_reset);
    OFF(ffxDispatchDescFrameGenerationPrepare, cameraNear);
    OFF(ffxDispatchDescFrameGenerationPrepare, cameraFar);
    OFF(ffxDispatchDescFrameGenerationPrepare, cameraFovAngleVertical);
    OFF(ffxDispatchDescFrameGenerationPrepare, viewSpaceToMetersFactor);
    OFF(ffxDispatchDescFrameGenerationPrepare, depth);
    OFF(ffxDispatchDescFrameGenerationPrepare, motionVectors);
    SZ(ffxDispatchDescFrameGenerationPrepareCameraInfo);
    OFF(ffxDispatchDescFrameGenerationPrepareCameraInfo, header);
    OFF(ffxDispatchDescFrameGenerationPrepareCameraInfo, cameraPosition);
    OFF(ffxDispatchDescFrameGenerationPrepareCameraInfo, cameraUp);
    OFF(ffxDispatchDescFrameGenerationPrepareCameraInfo, cameraRight);
    OFF(ffxDispatchDescFrameGenerationPrepareCameraInfo, cameraForward);
    SZ(ffxQueryDescSwapchainReplacementFunctionsVK);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, header);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, pOutCreateSwapchainFFXAPI);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, pOutDestroySwapchainFFXAPI);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, pOutGetSwapchainImagesKHR);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, pOutAcquireNextImageKHR);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, pOutQueuePresentKHR);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, pOutSetHdrMetadataEXT);
    OFF(ffxQueryDescSwapchainReplacementFunctionsVK, pOutGetLastPresentCountFFXAPI);
    SZ(ffxDispatchDescFrameGenerationSwapChainWaitForPresentsVK);
    OFF(ffxDispatchDescFrameGenerationSwapChainWaitForPresentsVK, header);
    SZ(ffxDispatchDescFrameGeneration);
    OFF(ffxDispatchDescFrameGeneration, header);
    OFF(ffxDispatchDescFrameGeneration, commandList);
    OFF(ffxDispatchDescFrameGeneration, presentColor);
    OFF(ffxDispatchDescFrameGeneration, outputs);
    OFF(ffxDispatchDescFrameGeneration, numGeneratedFrames);
    OFF(ffxDispatchDescFrameGeneration, reset);
    OFF(ffxDispatchDescFrameGeneration, backbufferTransferFunction);
    OFF(ffxDispatchDescFrameGeneration, minMaxLuminance);
    OFF(ffxDispatchDescFrameGeneration, generationRect);
    OFF(ffxDispatchDescFrameGeneration, frameID);
    SZ(ffxQueryDescFrameGenerationSwapChainInterpolationCommandListVK);
    OFF(ffxQueryDescFrameGenerationSwapChainInterpolationCommandListVK, header);
    OFF(ffxQueryDescFrameGenerationSwapChainInterpolationCommandListVK, pOutCommandList);
    SZ(ffxQueryDescFrameGenerationSwapChainInterpolationTextureVK);
    OFF(ffxQueryDescFrameGenerationSwapChainInterpolationTextureVK, header);
    OFF(ffxQueryDescFrameGenerationSwapChainInterpolationTextureVK, pOutTexture);
    return 0;
}
