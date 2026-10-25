package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The core frame loop asks FrameGen which path to take (FG design 3.1); with frame generation off it acquires and presents as before. */
class PacedPathWiringTest {
    private static final String RENDERER = "net/vulkanmod/vulkan/Renderer";

    @Test void beginFrameAsksFrameGenBeforeAcquiring() throws Exception {
        List<String> calls = Bytecode.calls(RENDERER, "beginFrame");
        assertTrue(calls.contains("FrameGen.paced"), calls.toString());
        //acquire goes through SwapchainFunctions (Vulkan's, or FSR's replacement while its swapchain is live)
        assertTrue(calls.contains("SwapchainFunctions.acquire"), calls.toString());
        assertTrue(Bytecode.inOrder(calls, List.of("FrameGen.paced", "SwapchainFunctions.acquire")), calls.toString());
    }

    @Test void submitFrameHandsOffOrPresents() throws Exception {
        List<String> calls = Bytecode.calls(RENDERER, "submitFrame");
        assertTrue(calls.contains("FrameGen.handOff"), calls.toString());
        assertTrue(calls.contains("QueueLock.present"), calls.toString());
    }

    @Test void recreateStopsThePacerFirst() throws Exception {
        List<String> calls = Bytecode.calls(RENDERER, "recreateSwapChain");
        assertTrue(Bytecode.inOrder(calls, List.of("FrameGen.beforeSwapchainRecreate", "Vulkan.waitIdle")), calls.toString());
        assertTrue(Bytecode.inOrder(calls, List.of("Vulkan.waitIdle", "FrameGen.afterSwapchainRecreate")), calls.toString());
        //FSR design 4.4: FSR starts or stops inside the same recreation, after the device wait
        assertTrue(Bytecode.inOrder(calls, List.of("Vulkan.waitIdle", "FsrSwapchain.recreate", "FrameGen.afterSwapchainRecreate")),
                calls.toString());
    }

    @Test void fsrFramesSignalTheirFenceAfterThePresent() throws Exception {
        //FSR design 4.4 (final review I2): FSR's GPU work for the frame is submitted inside its present; the frame fence comes after it
        List<String> calls = Bytecode.calls(RENDERER, "submitFrame");
        assertTrue(Bytecode.inOrder(calls, List.of("FsrSwapchain.live", "QueueLock.submit", "QueueLock.present", "Renderer.signalAfterFsr")),
                calls.toString());
        //and with FSR live at most two real frames are in flight
        assertTrue(Bytecode.inOrder(Bytecode.calls(RENDERER, "beginFrame"), List.of("VK10.vkWaitForFences", "FsrSwapchain.live",
                "VK10.vkWaitForFences")), Bytecode.calls(RENDERER, "beginFrame").toString());
    }

    @Test void fsrPresentWaitsForTheInterpolationInputFirst() throws Exception {
        //FSR hang (2026-10-25): FSR's interpolation must never reach the game queue before the presenter freed its output
        List<String> calls = Bytecode.calls(RENDERER, "submitFrame");
        assertTrue(Bytecode.inOrder(calls, List.of("QueueLock.submit", "FsrSwapchain.beforePresent", "QueueLock.present")), calls.toString());
    }

    @Test void deviceWaitWaitsForFsrsPresentsFirst() throws Exception {
        //FSR design 4.4: every Vulkan.waitIdle (the recreation's included) ends FSR's presents before the device wait
        List<String> calls = Bytecode.calls("net/vulkanmod/vulkan/Vulkan", "waitIdle");
        assertTrue(Bytecode.inOrder(calls, List.of("FsrSwapchain.waitForPresents", "QueueLock.deviceWaitIdle")), calls.toString());
    }
}
