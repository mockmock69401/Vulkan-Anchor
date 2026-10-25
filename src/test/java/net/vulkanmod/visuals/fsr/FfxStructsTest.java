package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.lwjgl.system.MemoryUtil.*;

/** FSR design 4.2: the hand-written ffx struct layouts against the MSVC probe output. */
class FfxStructsTest {
    private static final Path PROBE = Path.of("tools", "fsr_layout_probe.txt");

    /** ffxCreateBackendVKDesc.vkDevice -> OFF_FFX_CREATE_BACKEND_VK_DESC_VK_DEVICE, VkQueueInfoFFXAPI -> SIZE_VK_QUEUE_INFO_FFXAPI. */
    private static String constantName(String probeName) {
        int dot = probeName.indexOf('.');
        String prefix = dot < 0 ? "SIZE_" : "OFF_";
        return prefix + snake(probeName.substring(0, dot < 0 ? probeName.length() : dot))
                + (dot < 0 ? "" : "_" + snake(probeName.substring(dot + 1)));
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toUpperCase();
    }

    @Test void layoutsMatchTheProbe() throws IOException, ReflectiveOperationException {
        List<String> lines = Files.readAllLines(PROBE).stream().map(String::strip).filter(l -> !l.isEmpty()).toList();
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            String[] parts = line.split(" ");
            assertEquals(2, parts.length, line);
            Field f;
            try {
                f = FfxStructs.class.getField(constantName(parts[0]));
            } catch (NoSuchFieldException e) {
                throw new AssertionError("no constant " + constantName(parts[0]) + " for probe line " + line, e);
            }
            assertEquals(Integer.parseInt(parts[1]), f.getInt(null), parts[0]);
        }
    }

    @Test void writersPutValuesAtTheProbedOffsets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long cfg = FfxStructs.configureFrameGeneration(stack, 0xABCDL, true, false, 0, 7, 1920, 1080, 0x1122334455667788L);
            assertEquals(0x20002L, memGetLong(cfg));
            assertEquals(0L, memGetLong(cfg + 8));
            assertEquals(0xABCDL, memGetAddress(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_SWAP_CHAIN));
            assertEquals(1, memGetByte(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_GENERATION_ENABLED));
            assertEquals(0, memGetByte(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_ALLOW_ASYNC_WORKLOADS));
            assertEquals(0L, memGetLong(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_HUD_LESS_COLOR));
            assertEquals(7, memGetInt(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FLAGS));
            assertEquals(0, memGetByte(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_ONLY_PRESENT_GENERATED));
            assertEquals(1920, memGetInt(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_GENERATION_RECT + 8));
            assertEquals(1080, memGetInt(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_GENERATION_RECT + 12));
            assertEquals(0x1122334455667788L, memGetLong(cfg + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_ID));

            long depth = FfxStructs.resource(stack, 0xD0L, 2, FfxStructs.FORMAT_R32_FLOAT, 2560, 1440, 1, 1, 0, 0, 4);
            long mv = FfxStructs.resource(stack, 0xE0L, 2, FfxStructs.FORMAT_R16G16_FLOAT, 2560, 1440, 1, 1, 0, 0, 4);
            long prep = FfxStructs.prepare(stack, 0x99L, 42L, 0, 0xC0DEL, 2560, 1440, 2560f, 1440f, 16.6f, 0.05f, 1024f,
                    1.2f, 1f, depth, mv);
            assertEquals(0x20004L, memGetLong(prep));
            assertEquals(0x99L, memGetLong(prep + 8));
            assertEquals(42L, memGetLong(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_ID));
            assertEquals(0xC0DEL, memGetAddress(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_COMMAND_LIST));
            assertEquals(2560, memGetInt(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_RENDER_SIZE));
            assertEquals(1440, memGetInt(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_RENDER_SIZE + 4));
            assertEquals(0f, memGetFloat(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_JITTER_OFFSET));
            assertEquals(1440f, memGetFloat(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTOR_SCALE + 4));
            assertEquals(16.6f, memGetFloat(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_TIME_DELTA));
            assertEquals(1024f, memGetFloat(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_CAMERA_FAR));
            assertEquals(0xD0L, memGetAddress(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_DEPTH));
            assertEquals(FfxStructs.FORMAT_R32_FLOAT, memGetInt(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_DEPTH
                    + FfxStructs.OFF_FFX_API_RESOURCE_DESCRIPTION + FfxStructs.OFF_FFX_API_RESOURCE_DESCRIPTION_FORMAT));
            assertEquals(0xE0L, memGetAddress(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTORS));
            assertEquals(4, memGetInt(prep + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_MOTION_VECTORS
                    + FfxStructs.OFF_FFX_API_RESOURCE_STATE));

            long gen = FfxStructs.dispatchFrameGeneration(stack, 0xC1L, depth, mv, true, 2560, 1440, 5L);
            assertEquals(0x20003L, memGetLong(gen));
            assertEquals(0xD0L, memGetAddress(gen + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PRESENT_COLOR));
            assertEquals(0xE0L, memGetAddress(gen + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_OUTPUTS));
            assertEquals(0L, memGetAddress(gen + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_OUTPUTS + FfxStructs.SIZE_FFX_API_RESOURCE));
            assertEquals(1, memGetInt(gen + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_NUM_GENERATED_FRAMES));
            assertEquals(1, memGetByte(gen + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_RESET));
            assertEquals(5L, memGetLong(gen + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_FRAME_ID));
        }
    }

    @Test void swapchainModeChainsIntoTheSwapchainDesc() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            //FFX_API_CREATE_CONTEXT_DESC_TYPE_FGSWAPCHAIN_MODE_VK with composeOnPresentQueue = false: compose on the game queue
            long mode = FfxStructs.swapchainMode(stack, 0L, false);
            assertEquals(0x40010L, memGetLong(mode));
            assertEquals(0L, memGetLong(mode + 8));
            assertEquals(0, memGetByte(mode + FfxStructs.OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_MODE_VK_COMPOSE_ON_PRESENT_QUEUE));
            assertEquals(1, memGetByte(FfxStructs.swapchainMode(stack, 0L, true)
                    + FfxStructs.OFF_FFX_CREATE_CONTEXT_DESC_FRAME_GENERATION_SWAP_CHAIN_MODE_VK_COMPOSE_ON_PRESENT_QUEUE));
            //the DLL walks the swapchain desc's pNext for it
            long chained = FfxStructs.swapchainMode(stack, 0x1234L, false);
            assertEquals(0x1234L, memGetAddress(chained + 8));
        }
    }
}
