package net.vulkanmod.visuals.render;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-21 (CTE2: a trunk's north or south face flickered while the sun moved, and stood still with the day cycle off): vanilla's
 * sun turns in the x-y plane, so a north or south face's N.L is 0 but for rounding. Its sun lookup must not follow that sign.
 */
class GrazingFaceTest {
    @Test
    void theSunNeverLeavesTheXyPlane() {
        for (float t = 0; t < 1; t += 0.01f) {
            assertEquals(0, SunMath.sunDirection(t).z, 0);
        }
    }

    @Test
    void aNorthOrSouthFaceIsLookedUpWhateverTheRoundingSays() {
        //The view-space dot of a south face with the sun, through a camera rotation, as lighting.comp gets it
        Matrix3f view = new Matrix3f().rotateX(0.03f).rotateY((float) Math.toRadians(131.5));
        Vector3f south = view.transform(new Vector3f(0, 0, 1));
        boolean sawPositive = false, sawNegative = false;
        for (float t = 0.04f; t < 0.2f; t += 0.0001f) {
            Vector3f sun = view.transform(SunMath.sunDirection(t));
            float nl = south.dot(sun);
            if (nl > 0) sawPositive = true;
            if (nl < 0) sawNegative = true;
            assertEquals(0, LightingMath.grazingNDotL(nl), 0, "grazed at t " + t);
            float facing = LightingMath.relativeFacing(LightingMath.grazingNDotL(nl), 0.5f, true);
            assertEquals(0, facing, 0);
            assertTrue(LightingMath.looksUp(facing, nl), "looked up at t " + t);
        }
        //The rounding really changes sign as the sun moves (the flicker's cause)
        assertTrue(sawPositive && sawNegative, "rounding signs " + sawPositive + " " + sawNegative);
    }

    @Test
    void facesTurnedToOrAwayFromTheSunAreUnchanged() {
        assertEquals(0.3f, LightingMath.grazingNDotL(0.3f));
        assertEquals(-0.3f, LightingMath.grazingNDotL(-0.3f));
        assertFalse(LightingMath.looksUp(0, -0.3f));
        assertTrue(LightingMath.looksUp(0.5f, 0.3f));
    }
}
