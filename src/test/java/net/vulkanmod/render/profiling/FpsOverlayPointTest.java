package net.vulkanmod.render.profiling;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-22: the overlay counts frames at NeoForge's RenderFrameEvent.Pre, after the frame's fence and swapchain waits, as Anchor
 * FPS does; not at the top of Renderer.beginFrame, before them, where the VSync queue's waits counted as slow frames.
 */
class FpsOverlayPointTest {
    @Test
    void framesAreCountedAtTheRenderTickNotInBeginFrame() throws Exception {
        List<String> begin = Bytecode.calls("net/vulkanmod/vulkan/Renderer", "beginFrame");
        assertEquals(0, Bytecode.count(begin, "FpsOverlay.frame"), begin.toString());
        //install registers the GUI drawing and the frame count (a lambda each)
        List<String> install = Bytecode.calls("net/vulkanmod/render/profiling/FpsOverlay", "install");
        assertEquals(2, Bytecode.count(install, "IEventBus.addListener"), install.toString());
        String lambdas = String.join(" ", Bytecode.read("net/vulkanmod/render/profiling/FpsOverlay").methods.stream()
                .filter(m -> m.name.startsWith("lambda$install")).map(m -> m.desc).toList());
        assertTrue(lambdas.contains("RenderFrameEvent$Pre"), lambdas);
    }
}
