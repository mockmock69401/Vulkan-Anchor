package net.vulkanmod.visuals.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SunMathTest {
    @Test
    void sunFollowsTheSkyRotation() {
        Vector3f noon = SunMath.sunDirection(0);
        assertEquals(0f, noon.x, 1e-6f);
        assertEquals(1f, noon.y, 1e-6f);
        Vector3f sunset = SunMath.sunDirection(0.25f);
        assertEquals(-1f, sunset.x, 1e-6f);//sets in the west
        assertEquals(0f, sunset.y, 1e-6f);
        assertEquals(-1f, SunMath.sunDirection(0.5f).y, 1e-6f);
    }

    @Test
    void dayFactorFadesWithTheSunAndTheRain() {
        assertEquals(1f, SunMath.dayFactor(1, 0));
        assertEquals(0f, SunMath.dayFactor(-1, 0));//midnight
        float dawn = SunMath.dayFactor(0.1f, 0);
        assertTrue(dawn > 0 && dawn < 1, Float.toString(dawn));
        assertEquals(0f, SunMath.dayFactor(1, 1));
    }
}
