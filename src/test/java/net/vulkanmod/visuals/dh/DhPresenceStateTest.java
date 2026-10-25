package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.dh.DhPresence.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which DH the fork draws: the render API (DH 3.3.5), DH 3.3.0 to 3.3.4's internal render definition, or none. */
class DhPresenceStateTest {
    @Test
    void theRenderApiWinsThenTheLegacyRenderDefinition() {
        assertEquals(State.ABSENT, DhPresence.classify(false, true, true));
        assertEquals(State.RENDER_API, DhPresence.classify(true, true, false));
        //DH 3.3.5 has no internal definition; were both there, the render API is the one DH honours
        assertEquals(State.RENDER_API, DhPresence.classify(true, true, true));
        assertEquals(State.LEGACY_RENDER_DEF, DhPresence.classify(true, false, true));
        assertEquals(State.NO_RENDER_API, DhPresence.classify(true, false, false));
        assertEquals(State.NO_RENDER_API, DhPresence.classify(true, false));
    }

    @Test
    void lodsAreDrawnWithTheRenderApiAndWithALegacyDh() {
        assertTrue(DhPresence.drawsLods(State.RENDER_API));
        assertTrue(DhPresence.drawsLods(State.LEGACY_RENDER_DEF));
        assertFalse(DhPresence.drawsLods(State.NO_RENDER_API));
        assertFalse(DhPresence.drawsLods(State.ABSENT));
    }
}
