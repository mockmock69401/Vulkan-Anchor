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

    /** 2026-10-30 (audit): a sliced refresh starts only when it ends before the step changes (the change redraws that slot whole). */
    @Test
    void aRefreshStartsOnlyWhenItEndsBeforeTheStepChanges() {
        float step = (float) ShadowSteps.STEP;
        float start = ShadowSteps.timeOf(100) + step * 0.01f;
        //the vanilla day: 24000 ticks, 20 ticks a second; at 60 fps a step lasts about 10 frames
        float perFrame = 1f / 24000f * 20f / 60f;
        assertTrue(ShadowSteps.refreshFits(start, 0f, 8), "time standing still");
        assertTrue(ShadowSteps.refreshFits(start, perFrame / 2, 8), "about 20 frames left");
        assertFalse(ShadowSteps.refreshFits(ShadowSteps.timeOf(100) + step * 0.5f, perFrame, 8), "about 5 frames left");
        assertFalse(ShadowSteps.refreshFits(start, perFrame * 1.25f, 8), "48 fps: never");
        assertEquals(0.02f, ShadowSteps.advance(0.99f, 0.01f), 1e-6f);
        assertEquals(0f, ShadowSteps.advance(0.5f, 0.4f));
    }
}
