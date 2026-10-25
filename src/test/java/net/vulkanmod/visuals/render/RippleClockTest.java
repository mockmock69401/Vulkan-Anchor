package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P7e-3 design 3.3 (revision 2.4: three octaves): the flow phase advances with the game time. */
class RippleClockTest {
    /** The phase from start after dt seconds at speed factor f, as RippleClock adds it. */
    private static double[] flowed(double[] start, double dt, float f) {
        double[] p = new double[RippleMath.OCTAVES * 2];
        for (int o = 0; o < RippleMath.OCTAVES; o++) {
            p[o * 2] = RippleMath.wrap(start[o * 2] + RippleMath.FLOW[o][0] * f * dt);
            p[o * 2 + 1] = RippleMath.wrap(start[o * 2 + 1] + RippleMath.FLOW[o][1] * f * dt);
        }
        return p;
    }

    @Test
    void theFirstFrameOnlyStarts() {
        RippleClock c = new RippleClock();
        c.advance(1000, 1);
        assertArrayEquals(new double[6], c.phase());
    }

    @Test
    void thePhaseFollowsTheFlow() {
        RippleClock c = new RippleClock();
        c.advance(10, 1);
        c.advance(10.5, 1);
        assertArrayEquals(flowed(new double[6], 0.5, 1), c.phase(), 1e-9);
    }

    /** Review focus (first plan) 3: rain doubles the speed from then on; the phase so far is kept. */
    @Test
    void aFasterFlowKeepsThePhaseContinuous() {
        RippleClock c = new RippleClock();
        c.advance(0, 1);
        c.advance(0.5, 1);
        c.advance(1.0, 2);
        assertArrayEquals(flowed(flowed(new double[6], 0.5, 1), 0.5, 2), c.phase(), 1e-9);
    }

    /** Revision 2.5: speed 0 holds the ripples still. */
    @Test
    void speedZeroHoldsThemStill() {
        RippleClock c = new RippleClock();
        c.advance(0, 1);
        c.advance(0.5, 1);
        double[] before = c.phase();
        c.advance(1.0, 0);
        assertArrayEquals(before, c.phase(), 1e-12);
    }

    /** Review focus (first plan) 2: a time command, a dimension change or a pause leaves the ripples where they were. */
    @Test
    void aJumpInTimeDoesNotMoveTheRipples() {
        RippleClock c = new RippleClock();
        c.advance(0, 1);
        c.advance(0.5, 1);
        double[] before = c.phase();
        c.advance(5000, 1);
        assertArrayEquals(before, c.phase());
        c.advance(100, 1);
        assertArrayEquals(before, c.phase(), "backwards");
        c.advance(100, 1);
        assertArrayEquals(before, c.phase(), "paused");
        c.advance(100.5, 1);
        assertTrue(c.phase()[0] > before[0], "moves on from the new time");
    }

    @Test
    void thePhaseWraps() {
        RippleClock c = new RippleClock();
        double t = 0;
        c.advance(t, 1);
        for (int i = 0; i < 30000; i++) c.advance(t += 1, 1000);
        for (double p : c.phase()) assertTrue(p >= 0 && p < RippleMath.PERIOD, "" + p);
    }
}
