package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P7d design 3.2: the camera's sky light, eased over about a second. */
class SkyExposureTest {
    @Test
    void oneTimeConstantCoversAbout63Percent() {
        assertEquals(1 - (float) Math.exp(-1), SkyExposure.next(0, 1, 1), 1e-6f);
        assertEquals(0.5f, SkyExposure.next(0.5f, 0.5f, 0.3f), 1e-6f);
    }

    @Test
    void theFirstUpdateSnaps() {
        SkyExposure e = new SkyExposure();
        e.update(0.2f, 1_000_000_000L);
        assertEquals(0.2f, e.value(), 1e-6f);
        e.update(1f, 2_000_000_000L);
        assertEquals(0.2f + 0.8f * (1 - (float) Math.exp(-1)), e.value(), 1e-5f);
    }

    /** Review focus 4. */
    @Test
    void aLongGapSnapsToTheTarget() {
        assertEquals(1f, SkyExposure.next(0, 1, SkyExposure.MAX_STEP));
        assertEquals(0.3f, SkyExposure.next(0.3f, 1, -2), 1e-6f);
        assertEquals(1f, SkyExposure.next(0, 7, 20));
    }

    /** Gate G7j round 6: under an island 130 blocks up the camera's sky light is 0 but the air above is open, which is not a cave. */
    @Test
    void openAirAboveCountsAsSky() {
        assertEquals(24, SkyExposure.OPEN_AIR_MIN);
        assertEquals(48, SkyExposure.OPEN_AIR_FULL);
        assertEquals(0f, SkyExposure.target(0, 3));
        assertEquals(0f, SkyExposure.target(0, SkyExposure.OPEN_AIR_MIN));
        assertEquals(0.5f, SkyExposure.target(0, 36), 1e-6f);
        assertEquals(1f, SkyExposure.target(0, SkyExposure.OPEN_AIR_FULL));
        assertEquals(1f, SkyExposure.target(0, 500));
        assertEquals(0.8f, SkyExposure.target(0.8f, 0), 1e-6f);
        assertEquals(0.8f, SkyExposure.target(0.8f, 30), 1e-6f);
        assertEquals(1f, SkyExposure.target(7, 0));
    }
}
