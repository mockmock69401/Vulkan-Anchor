package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-23: the wind's and the ripples' clock runs with real time, not the game time's bursts. */
class FlowClockTest {
    private static final long S = 1_000_000_000L;

    @Test
    void itCountsRealTimeAndStopsWhilePaused() {
        FlowClock c = new FlowClock();
        assertEquals(0, c.advance(5 * S, false), 0, "the first call only starts it");
        assertEquals(0.016, c.advance(5 * S + 16_000_000L, false), 1e-9);
        assertEquals(0.016, c.advance(6 * S, true), 1e-9, "paused");
        assertEquals(0.516, c.advance(6 * S + S / 2, false), 1e-9);
        //A hitch or a loading screen is not flow; backwards is not either
        assertEquals(0.516, c.advance(20 * S, false), 1e-9);
        assertEquals(0.516, c.advance(19 * S, false), 1e-9);
        assertEquals(0.516, c.seconds(), 1e-9);
    }

    @Test
    void theWindAndTheRipplesTakeIt() throws Exception {
        String renderer = new String(FlowClockTest.class.getResourceAsStream("/" + "net/vulkanmod/visuals/render/VisualsRenderer.class").readAllBytes(),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(renderer.contains("FlowClock"));
        assertFalse(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/net/vulkanmod/visuals/render/VisualsRenderer.java"))
                .contains("FoliageWind.update(RippleMath.seconds("), "not the game time");
    }
}
