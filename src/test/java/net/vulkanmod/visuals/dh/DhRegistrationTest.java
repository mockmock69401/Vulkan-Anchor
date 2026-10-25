package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhRegistrationTest {
    @AfterEach
    void reset() {
        //DhPresenceTest.notActiveUntilMarked reads the same static
        DhPresence.markActive(false);
        DhInternals.resetForTest();
        DhVertexBuffer.closeAllNow();
    }

    /** What install hands DH: each listener's event type, in order. */
    private record Bound(List<Class<?>> types, List<Object> listeners) implements DhRegistration.Binder {
        Bound() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void bind(Class<? extends IDhApiEvent> type, IDhApiEvent listener) {
            this.types.add(type);
            this.listeners.add(listener);
        }

        <T> T listener(Class<T> type) {
            return type.cast(this.listeners.get(this.types.indexOf(type)));
        }
    }

    @Test
    void anAcceptedDefinitionTurnsDhOn() {
        try (LogCapture log = LogCapture.visuals()) {
            assertTrue(DhRegistration.onResult(true, ""));
            assertTrue(DhPresence.isActive());
            assertEquals(0, log.warnings().size());
        }
    }

    @Test
    void aRefusedDefinitionLeavesDhOffWithOneWarning() {
        try (LogCapture log = LogCapture.visuals()) {
            assertFalse(DhRegistration.onResult(false, "x"));
            assertFalse(DhPresence.isActive());
            assertEquals(1, log.warnings().size());
            assertTrue(log.warnings().get(0).contains("x"), log.warnings().get(0));
        }
    }

    /**
     * C1: DH unloads a client level 30 s after it was left (every dimension change; ClientLevelWrapper's inactivity cleanup on DH's
     * timer thread) and closes that level's buffers itself, so no level unload frees anything: only the world exit does. Its listener
     * is bound first, so a failed bind leaves DH without the init listener rather than without the free.
     */
    @Test
    void theWorldUnloadIsBoundFirstAndNoLevelUnloadIsBound() {
        Bound bound = new Bound();
        DhRegistration.install(bound);
        assertEquals(List.of(DhApiWorldUnloadEvent.class, DhApiAfterDhInitEvent.class), bound.types());
        for (Object l : bound.listeners()) assertFalse(l instanceof DhApiLevelUnloadEvent, "a level unload frees nothing: " + l);
    }

    @Test
    void aLevelUnloadLeavesEveryBufferDrawable() throws Exception {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer kept = new DhVertexBuffer("t", gpu);
        kept.uploadVertexBuffer(DhVertexBufferTest.vertices(8), 8);
        DhRegistration.install(new Bound());
        DhVertexBuffer.closeHandedOver();
        assertNotEquals(0, kept.handle());
        assertEquals(2, kept.quadCount());
        for (var inner : Bytecode.read("net/vulkanmod/visuals/dh/DhRegistration").innerClasses) {
            if (!inner.name.startsWith("net/vulkanmod/visuals/dh/DhRegistration$")) continue;
            assertFalse(Bytecode.read(inner.name).superName.endsWith("DhApiLevelUnloadEvent"), "a level-unload listener: " + inner.name);
        }
    }

    @Test
    void aWorldUnloadFreesEveryBufferOnTheRenderThread() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer a = new DhVertexBuffer("a", gpu), b = new DhVertexBuffer("b", gpu);
        a.uploadVertexBuffer(DhVertexBufferTest.vertices(8), 8);
        b.uploadVertexBuffer(DhVertexBufferTest.vertices(4), 4);
        Bound bound = new Bound();
        DhRegistration.install(bound);
        bound.listener(DhApiWorldUnloadEvent.class).onWorldUnload(new DhApiEventParam<>(new DhApiWorldUnloadEvent.EventParam()));
        assertNotEquals(0, a.handle(), "handed to the render thread, not closed on DH's thread");
        DhVertexBuffer.closeHandedOver();
        assertEquals(0, a.handle());
        assertEquals(0, b.handle());
        assertEquals(2, gpu.retired.size());
    }

    @Test
    void aFailedWorldUnloadBindLeavesDhOff() {
        List<Class<?>> types = new ArrayList<>();
        try (LogCapture log = LogCapture.visuals()) {
            DhRegistration.install((type, listener) -> {
                types.add(type);
                throw new NoSuchMethodError("DhApiWorldUnloadEvent.onWorldUnload");
            });
            assertEquals(List.of(DhApiWorldUnloadEvent.class), types, "the init listener is never bound");
            assertFalse(DhPresence.isActive());
            assertEquals(1, log.warnings().size());
        }
    }

    /** The binding goes through DH's event injector and is listed in the activation's steps. */
    @Test
    void installBindsThroughDhsInjectorAndActivationHandsTheFreesToTheRenderThread() throws Exception {
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/dh/DhRegistration", "install").contains("IDhApiEventInjector.bind"),
                Bytecode.calls("net/vulkanmod/visuals/dh/DhRegistration", "install").toString());
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/dh/DhRegistration", "activateSteps").contains("DhVertexBuffer.installHandOver"));
    }

    @Test
    void aChangedDhEventApiWarnsInsteadOfFailingStartup() {
        try (LogCapture log = LogCapture.visuals()) {
            DhRegistration.install((type, listener) -> {
                throw new NoSuchMethodError("DhApiAfterDhInitEvent.afterDistantHorizonsInit");
            });
            assertFalse(DhPresence.isActive());
            assertEquals(1, log.warnings().size());
            assertTrue(log.warnings().get(0).contains("NoSuchMethodError"), log.warnings().get(0));
        }
    }

    @Test
    void aFailedActivationGivesTheFrameBackToVulkanMod() {
        try (LogCapture log = LogCapture.visuals()) {
            assertTrue(DhRegistration.onResult(true, ""));
            DhRegistration.activate(() -> {
                throw new IllegalStateException("no frames");
            });
            assertFalse(DhPresence.isActive(), "the guard draws vanilla again and blocks DH's hook");
            assertTrue(DhRegistration.disabled());
            assertEquals(1, log.warnings().size());
        }
    }
}
