package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P7l design 3.1, 3.7: light changes from the client's light engine. */
class LightUpdatesTest {
    @AfterEach
    void off() {
        LightUpdates.collect(false);
    }

    @Test
    void onlyWhileCollecting() {
        LightUpdates.collect(false);
        LightUpdates.changed(1, 2, 1);
        assertEquals(0, LightUpdates.pending());
        LightUpdates.collect(true);
        LightUpdates.changed(1, 2, 1);
        assertEquals(1, LightUpdates.pending());
        LightSections s = new LightSections();
        s.reset(LightVolumeMath.layout(2, 0, 4));
        s.centre(0, 0, 0, false, (x, z) -> false);
        LightUpdates.drainInto(s);
        assertEquals(0, LightUpdates.pending());
        assertEquals(1, s.queued());
    }

    private static LightSections centred() {
        LightSections s = new LightSections();
        s.reset(LightVolumeMath.layout(2, 0, 4));
        s.centre(0, 0, 0, false, (x, z) -> false);
        return s;
    }

    /**
     * 2026-10-15 (user report: a flash when a placed torch went out while holding one): with Reforged's meshes the volume takes a
     * light change only once the section's new mesh is up, so both show the same light; a section never rebuilt after HOLD_NANOS.
     */
    @Test
    void aHeldChangeWaitsForTheSectionsMesh() {
        LightUpdates.collect(true);
        LightUpdates.holdForMeshes(true);
        LightSections s = centred();
        LightUpdates.changed(1, 2, 1, 1000);
        LightUpdates.drainInto(s, 2000);
        assertEquals(0, s.queued(), "vanilla still shows the old light");
        assertEquals(1, LightUpdates.held());
        LightUpdates.meshUploaded(1, 2, 0, 3000);//another section
        LightUpdates.meshUploaded(1, 2, 1, 500);//a mesh from before the change
        LightUpdates.drainInto(s, 4000);
        assertEquals(0, s.queued());
        LightUpdates.meshUploaded(1, 2, 1, 5000);
        LightUpdates.drainInto(s, 6000);
        assertEquals(1, s.queued(), "the new mesh is up");
        assertEquals(0, LightUpdates.held());
    }

    @Test
    void aSectionNeverRebuiltIsTakenAfterTheHold() {
        LightUpdates.collect(true);
        LightUpdates.holdForMeshes(true);
        LightSections s = centred();
        LightUpdates.changed(1, 2, 1, 0);
        LightUpdates.drainInto(s, LightUpdates.HOLD_NANOS);
        assertEquals(0, s.queued());
        LightUpdates.drainInto(s, LightUpdates.HOLD_NANOS + 1);
        assertEquals(1, s.queued());
    }

    @Test
    void withoutReforgedsMeshesAChangeIsTakenAtOnce() {
        LightUpdates.collect(true);
        LightUpdates.holdForMeshes(false);
        LightSections s = centred();
        LightUpdates.changed(1, 2, 1, 0);
        LightUpdates.meshUploaded(1, 2, 1, 0);
        LightUpdates.drainInto(s, 1);
        assertEquals(1, s.queued());
        assertEquals(0, LightUpdates.held());
    }

    /** Review focus 3: past CAPACITY the changes are lost, so every slot is forgotten and the next rescan uploads it again. */
    @Test
    void anOverflowForgetsEverySlot() {
        LightVolumeMath.Layout layout = LightVolumeMath.layout(2, 0, 4);
        LightSections s = new LightSections();
        s.reset(layout);
        s.centre(0, 0, 0, false, (x, z) -> true);
        for (int[] p : s.next(1000)) s.uploaded(p[0], p[1], p[2]);
        LightUpdates.collect(true);
        for (int i = 0; i <= LightUpdates.CAPACITY; i++) LightUpdates.changed(0, 0, 0);
        assertEquals(LightUpdates.CAPACITY, LightUpdates.pending());
        LightUpdates.drainInto(s);
        assertEquals(0, s.ready());
        s.centre(0, 0, 0, true, (x, z) -> true);
        assertEquals(100, s.queued());
    }
}
