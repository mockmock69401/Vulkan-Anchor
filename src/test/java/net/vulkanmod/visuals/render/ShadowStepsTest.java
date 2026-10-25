package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Sun-step crossfade (2026-10-16): two maps per cascade, the current step's and the next one's, mixed by the sun's progress. */
class ShadowStepsTest {
    private static final boolean[] BOTH = {true, true}, NONE = {false, false};

    @Test
    void stepsQuantiseTheTimeOfDay() {
        float t = (float) (1234.25 * ShadowSteps.STEP);
        assertEquals(1234, ShadowSteps.step(t));
        assertEquals(0.25f, ShadowSteps.progress(t), 1e-3f);
        assertEquals(1234 * ShadowSteps.STEP, ShadowSteps.timeOf(1234), 1e-7);
        assertEquals(0.05 / 360, ShadowSteps.STEP, 1e-12, "0.05 degrees a step");
        //A game tick turns the sun 1/24000 of a day: a step is a few ticks
        assertTrue(ShadowSteps.STEP * 24000 > 3 && ShadowSteps.STEP * 24000 < 4);
    }

    @Test
    void theMissingMapIsDrawnFirstThenTheNext() {
        //Nothing yet: step k into slot 0
        assertEquals(new ShadowSteps.Draw(0, 7), ShadowSteps.plan(7, new long[]{0, 0}, NONE, new long[]{0, 0}, false));
        //k there: k + 1 into the other slot
        assertEquals(new ShadowSteps.Draw(1, 8), ShadowSteps.plan(7, new long[]{7, 0}, new boolean[]{true, false}, new long[]{5, 0}, false));
        //Both there: nothing, or on a refresh frame the older map at its own step
        assertNull(ShadowSteps.plan(7, new long[]{7, 8}, BOTH, new long[]{5, 6}, false));
        assertEquals(new ShadowSteps.Draw(0, 7), ShadowSteps.plan(7, new long[]{7, 8}, BOTH, new long[]{5, 6}, true));
        assertEquals(new ShadowSteps.Draw(1, 8), ShadowSteps.plan(7, new long[]{7, 8}, BOTH, new long[]{9, 6}, true));
    }

    @Test
    void whenTheSunEntersTheNextStepOneDrawFollows() {
        //k moved from 7 to 8: slot 1 (8) is now the current map, slot 0 (7) is replaced by 9
        long[] steps = {7, 8};
        ShadowSteps.Draw d = ShadowSteps.plan(8, steps, BOTH, new long[]{5, 6}, false);
        assertEquals(new ShadowSteps.Draw(0, 9), d);
        steps[d.slot()] = d.step();
        assertEquals(new ShadowSteps.Read(1, 0, 0.4f), ShadowSteps.read(8, 0.4f, steps, BOTH));
        //Before that draw ran (or if it was dropped) the current map alone is read
        assertEquals(new ShadowSteps.Read(1, -1, 0), ShadowSteps.read(8, 0.4f, new long[]{7, 8}, BOTH));
    }

    @Test
    void readsFallBackToAnyValidMap() {
        assertEquals(new ShadowSteps.Read(0, 1, 0.75f), ShadowSteps.read(3, 0.75f, new long[]{3, 4}, BOTH));
        assertEquals(new ShadowSteps.Read(1, -1, 0), ShadowSteps.read(3, 0.75f, new long[]{9, 4}, new boolean[]{false, true}));
        //A time jump: neither step matches, the valid map still beats no shadows
        assertEquals(new ShadowSteps.Read(0, -1, 0), ShadowSteps.read(30, 0.5f, new long[]{3, 4}, new boolean[]{true, false}));
        assertNull(ShadowSteps.read(3, 0.5f, new long[]{3, 4}, NONE));
        //An invalid map of the right step is not read (camera moved away, dropped frame)
        assertEquals(new ShadowSteps.Read(1, -1, 0), ShadowSteps.read(3, 0.5f, new long[]{3, 4}, new boolean[]{false, true}));
    }
}
