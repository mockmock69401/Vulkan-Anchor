package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** DH 3.3.x's generic renderer and containers are stand-ins that hold nothing and answer defaults. */
class LegacyDhRenderersTest {
    enum Phase {NEW, READY, DONE}

    interface Sample {
        boolean flag();

        int count();

        String name();

        Phase getPhase();

        void setPhase(Phase p);

        Object thing();

        void run();
    }

    @Test
    void aStandInAnswersDefaultsAndKeepsAState() {
        Sample s = LegacyDhRenderers.noop(Sample.class);
        assertFalse(s.flag());
        assertEquals(0, s.count());
        assertEquals("", s.name());
        assertNull(s.thing());
        s.run();
        assertEquals(Phase.NEW, s.getPhase(), "an enum answers its first constant");
        s.setPhase(Phase.DONE);
        assertEquals(Phase.DONE, s.getPhase(), "a getter answers what its setter stored");
        Sample other = LegacyDhRenderers.noop(Sample.class);
        assertEquals(Phase.NEW, other.getPhase(), "each stand-in keeps its own state");
        assertNotEquals(s, other);
        assertEquals(s, s);
    }
}
