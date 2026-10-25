package dev.vulkanfix.compat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's GlFramebuffer.createAndBind() rebuilds the Vulkan framebuffer/render pass when an attachment changes and
 * ends the active pass if this framebuffer was bound, but never begins the new one. Mods that bind first and attach
 * later (Xaero's waypoint atlas) then draw with no render pass: NPE "state.renderPass is null". GL keeps both the
 * binding and the viewport across attachment changes, so vulkanfix restarts the pass and restores the viewport.
 */
class FramebufferRestartTest {
    @BeforeEach
    void reset() {
        ViewportTracker.resetForTest();
    }

    @Test
    void restartsOnlyForTheBoundFramebufferWhileRecordingWithoutItsPassActive() {
        assertTrue(FramebufferRestart.shouldRestart(true, true, true, false));
        assertFalse(FramebufferRestart.shouldRestart(false, true, true, false), "not bound");
        assertFalse(FramebufferRestart.shouldRestart(true, false, true, false), "no framebuffer built yet");
        assertFalse(FramebufferRestart.shouldRestart(true, true, false, false), "not recording a frame");
        assertFalse(FramebufferRestart.shouldRestart(true, true, true, true), "its pass is already active");
    }

    @Test
    void remembersTheLastViewportSetThroughGlStateManager() {
        assertNull(ViewportTracker.last());
        ViewportTracker.record(128, 64, 64, 64);
        ViewportTracker.record(192, 0, 64, 64);
        assertArrayEquals(new int[]{192, 0, 64, 64}, ViewportTracker.last());
    }

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"reforged.GlFramebufferMixin\""));
            assertTrue(json.contains("\"reforged.GlStateViewportMixin\""));
        }
        assertTrue(CompatGate.shouldApply("reforged.GlFramebufferMixin", Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply("reforged.GlFramebufferMixin", Set.<String>of()::contains));
    }
}
