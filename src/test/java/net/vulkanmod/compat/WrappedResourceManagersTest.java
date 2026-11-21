package net.vulkanmod.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Seeing through resource manager wrappers to the manager Continuity knows (plain stand-ins, no Minecraft classes). */
class WrappedResourceManagersTest {
    interface Manager {
    }

    /** The game's own manager: the only one with an answer. */
    static final class Real implements Manager {
        final String handler;

        Real(String handler) {
            this.handler = handler;
        }
    }

    /** Blueprint's RemoldedResourceManager: a private field holds the real manager. */
    static final class Wrapper implements Manager {
        private final Manager manager;
        private final String unrelated = "not a manager";

        Wrapper(Manager manager) {
            this.manager = manager;
        }
    }

    /** The reloadable manager: holds the (wrapped) active manager in its field. */
    static class Reloadable implements Manager {
        private Manager resources;
    }

    /** A wrapper that points back at itself through a pair. */
    static final class Loop implements Manager {
        Manager other;
    }

    private static Object lookup(Object m) {
        return m instanceof Real r ? r.handler : null;
    }

    @Test
    void findsTheRealManagerInsideAWrapper() {
        assertEquals("handler", WrappedResourceManagers.findThroughWrappers(new Wrapper(new Real("handler")), Manager.class,
                WrappedResourceManagersTest::lookup));
    }

    @Test
    void findsItTwoWrappersDeep() {
        Reloadable reloadable = new Reloadable();
        reloadable.resources = new Wrapper(new Real("deep"));
        assertEquals("deep", WrappedResourceManagers.findThroughWrappers(reloadable, Manager.class, WrappedResourceManagersTest::lookup));
    }

    @Test
    void nothingInsideMeansNoAnswer() {
        assertNull(WrappedResourceManagers.findThroughWrappers(new Reloadable(), Manager.class, WrappedResourceManagersTest::lookup));
        assertNull(WrappedResourceManagers.findThroughWrappers(new Real("self"), Manager.class, WrappedResourceManagersTest::lookup));
    }

    @Test
    void aCycleEnds() {
        Loop a = new Loop();
        Loop b = new Loop();
        a.other = b;
        b.other = a;
        assertNull(WrappedResourceManagers.findThroughWrappers(a, Manager.class, WrappedResourceManagersTest::lookup));
    }
}
