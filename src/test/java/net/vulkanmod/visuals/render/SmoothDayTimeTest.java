package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The shadows' time of day: between ticks while the day runs, never back under a lagging server's time packets. */
class SmoothDayTimeTest {
    private static final long FRAME = 5_000_000L;//200 fps

    /** DimensionType.timeOfDay in 1.20.1, for whole ticks. */
    private static float vanilla(long dayTime) {
        double d0 = dayTime / 24000.0 - 0.25;
        d0 -= Math.floor(d0);
        double d1 = 0.5 - Math.cos(d0 * Math.PI) / 2.0;
        return (float) (d0 * 2.0 + d1) / 3.0f;
    }

    @Test
    void wholeTicksMatchVanilla() {
        for (long t : new long[]{0, 1000, 6000, 12000, 18000, 23999, 24000, 1_000_123}) {
            assertEquals(vanilla(t), SmoothDayTime.timeOfDay(t), 1e-6f, "tick " + t);
        }
        float a = SmoothDayTime.timeOfDay(1500), b = SmoothDayTime.timeOfDay(1500.5), c = SmoothDayTime.timeOfDay(1501);
        assertTrue(a < b && b < c, "between ticks it moves on");
    }

    @Test
    void aStoppedDayOrFixedTimeKeepsVanilla() {
        SmoothDayTime s = new SmoothDayTime();
        assertEquals(0.3f, s.timeOfDay(0.3f, 1500, 0.5f, false, 0));
        assertTrue(Double.isNaN(s.clock()));
        assertEquals(SmoothDayTime.timeOfDay(1500.5), s.timeOfDay(0.3f, 1500, 0.5f, true, FRAME), 1e-7f, "running: the first frame is the client's time");
    }

    @Test
    void atTwentyTicksASecondItFollowsTheClientsTime() {
        SmoothDayTime s = new SmoothDayTime();
        double maxOffset = 0;
        for (int f = 0; f < 4000; f++) {//20 s
            double t = 1000 + f * FRAME / 1e9 * 20;
            s.timeOfDay(0, (long) Math.floor(t), (float) (t - Math.floor(t)), true, f * FRAME);
            if (f > 200) maxOffset = Math.max(maxOffset, Math.abs(s.clock() - t));
        }
        assertTrue(maxOffset < 0.01, "offset " + maxOffset);
    }

    @Test
    void aLaggingServersTimePacketsDoNotRunItBack() {
        //The client adds 20 ticks a second; every 2.3 s the server's packet sets it to the server's time, which moves 8.7 a second
        SmoothDayTime s = new SmoothDayTime();
        double server = 5000, client = 5000, last = Double.NEGATIVE_INFINITY, worst = 0, sinceSync = 0;
        double minSpeed = Double.MAX_VALUE, maxSpeed = 0;
        for (int f = 0; f < 24000; f++) {//120 s
            double dt = FRAME / 1e9;
            server += 8.7 * dt;
            client += 20 * dt;
            sinceSync += dt;
            if (sinceSync >= 2.3) {
                sinceSync = 0;
                client = server;
            }
            s.timeOfDay(0, (long) Math.floor(client), (float) (client - Math.floor(client)), true, f * FRAME);
            double c = s.clock();
            assertTrue(c >= last, "frame " + f + ": " + c + " < " + last);
            if (f > 12000) {//after the rate and the offset have settled (60 s)
                worst = Math.max(worst, Math.abs(c - server));
                double speed = (c - last) / dt;
                minSpeed = Math.min(minSpeed, speed);
                maxSpeed = Math.max(maxSpeed, speed);
            }
            last = c;
        }
        //The client's time runs up to 26 ticks ahead of the server's between packets; the clock follows its average
        assertTrue(worst < 30, "off the server's time by " + worst + " ticks");
        assertTrue(minSpeed > 2 && maxSpeed < 18, "speed " + minSpeed + ".." + maxSpeed + " ticks a second (the server's is 8.7)");
    }

    @Test
    void aJumpIsFollowedAtOnce() {
        SmoothDayTime s = new SmoothDayTime();
        s.timeOfDay(0, 1000, 0, true, 0);
        s.timeOfDay(0, 1000, 0.5f, true, FRAME);
        s.timeOfDay(0, 9000, 0, true, 2 * FRAME);//time set
        assertEquals(9000, s.clock(), 1e-9);
        s.timeOfDay(0, 9000, 0, false, 3 * FRAME);//daylight cycle off
        s.timeOfDay(0, 9000, 0, true, 4 * FRAME);//and on again
        assertEquals(9000, s.clock(), 1e-9);
    }
}
