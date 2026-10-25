package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.Test;

import static net.vulkanmod.visuals.dh.DhPresence.State.ABSENT;
import static net.vulkanmod.visuals.dh.DhPresence.State.NO_RENDER_API;
import static net.vulkanmod.visuals.dh.DhPresence.State.RENDER_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DhPresenceTest {
    @Test
    void absentWhenNotLoaded() {
        assertEquals(ABSENT, DhPresence.classify(false, false));
        assertEquals(ABSENT, DhPresence.classify(false, true));
    }

    @Test
    void oldDhHasNoRenderApi() {
        assertEquals(NO_RENDER_API, DhPresence.classify(true, false));
    }

    @Test
    void newDhHasRenderApi() {
        assertEquals(RENDER_API, DhPresence.classify(true, true));
    }

    @Test
    void notActiveUntilMarked() {
        assertFalse(DhPresence.isActive());
    }
}
