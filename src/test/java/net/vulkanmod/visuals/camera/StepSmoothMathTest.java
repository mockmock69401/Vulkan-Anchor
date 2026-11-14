package net.vulkanmod.visuals.camera;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Session 22: the step smoothing's math (StepSmoothMath), its snap cases and its setting. */
class StepSmoothMathTest {
    private static final double LIMIT = StepSmoothMath.stepLimit(0.6);
    private static final double K = StepSmoothMath.tickFactor(StepSmoothMath.STRENGTH_DEFAULT);

    private static StepSmoothMath.Tick walk(double dy, boolean onGround) {
        return new StepSmoothMath.Tick(dy, onGround, true, LIMIT);
    }

    /** A player that has been standing on the ground. */
    private static StepSmoothMath grounded() {
        StepSmoothMath m = new StepSmoothMath();
        m.tick(1, walk(0, true), K);
        return m;
    }

    @Test
    void defaultIsEightyMillisecondsAndStrengthSlows() {
        assertEquals(Math.exp(-0.05 / 0.08), K, 1e-12);
        assertTrue(StepSmoothMath.tickFactor(100) > K);
        assertTrue(StepSmoothMath.tickFactor(10) < K);
        //out of range strengths are held to the slider's ends
        assertEquals(StepSmoothMath.tickFactor(10), StepSmoothMath.tickFactor(0));
        assertEquals(StepSmoothMath.tickFactor(100), StepSmoothMath.tickFactor(500));
    }

    @Test
    void stepUpHoldsTheCameraThenEasesWithoutOvershoot() {
        StepSmoothMath m = grounded();
        m.tick(1, walk(0.5, true), K);
        assertEquals(0.5, m.step(), 1e-12);
        //the real eye is interpolated from the old height (pt 0) to the new (pt 1); the camera starts where it was
        double prev = 0.0;
        for (int tick = 0; tick < 12; tick++) {
            for (int f = 0; f <= 10; f++) {
                double pt = f / 10.0;
                double real = tick == 0 ? 0.5 * pt : 0.5;
                double cam = real + m.offset(K, pt);
                if (tick == 0 && f == 0) assertEquals(0.0, cam, 1e-12);
                assertTrue(cam >= prev - 1e-12, "camera never moves back down");
                assertTrue(cam <= 0.5 + 1e-12, "camera never overshoots the step");
                prev = cam;
            }
            m.tick(1, walk(0, true), K);
        }
        assertEquals(0.0, m.offset(K, 0), 1e-3);
    }

    @Test
    void offsetIsContinuousAcrossTicks() {
        StepSmoothMath m = grounded();
        double[] dys = {0.5, 0, 0.5, 0, 0, -0.08, -0.16, -0.26, 0, 0};
        boolean[] ground = {true, true, true, true, true, false, false, true, true, true};
        for (int i = 0; i < dys.length; i++) {
            double end = m.offset(K, 1.0);
            m.tick(1, walk(dys[i], ground[i]), K);
            assertEquals(end, m.offset(K, 0.0), 1e-12, "tick " + i);
        }
    }

    @Test
    void sameTimeSameOffsetWhateverTheFrameRate() {
        //the offset is a function of (tick, partialTick): frames at 30 or 240 fps sample the same curve
        StepSmoothMath a = grounded(), b = grounded();
        a.tick(1, walk(0.5, true), K);
        b.tick(1, walk(0.5, true), K);
        for (int f = 0; f <= 8; f++) a.offset(K, f / 8.0);
        assertEquals(b.offset(K, 0.75), a.offset(K, 0.75), 1e-15);
        assertEquals(StepSmoothMath.offsetAt(0, 0.5, K, 0.75), a.offset(K, 0.75), 1e-15);
    }

    @Test
    void stepDownIsSmoothedUntilItIsAFall() {
        StepSmoothMath m = grounded();
        //walking off a half-block step: three ticks of falling, then the landing
        m.tick(1, walk(-0.08, false), K);
        assertEquals(-0.08, m.step(), 1e-12);
        m.tick(1, walk(-0.16, false), K);
        assertEquals(-0.16, m.step(), 1e-12);
        m.tick(1, walk(-0.26, true), K);
        assertEquals(-0.26, m.step(), 1e-12);
        assertTrue(m.offset(K, 1.0) > 0, "camera is above the real eye after a step down");

        //off a ledge: smoothed while it could still be a step, then the fall is followed exactly
        StepSmoothMath f = grounded();
        double[] fall = {-0.08, -0.16, -0.23, -0.30, -0.37, -0.44};
        f.tick(1, walk(fall[0], false), K);
        f.tick(1, walk(fall[1], false), K);
        f.tick(1, walk(fall[2], false), K);
        assertNotEquals(0.0, f.step());
        for (int i = 3; i < fall.length; i++) {
            f.tick(1, walk(fall[i], false), K);
            assertEquals(0.0, f.step(), "tick " + i);
        }
        //landing on the ground below is not a step either: the drop was a fall
        f.tick(1, walk(-0.2, true), K);
        assertEquals(0.0, f.step());
        assertTrue(Math.abs(f.offset(K, 0)) < 0.2);
    }

    @Test
    void jumpsAreNotSmoothed() {
        StepSmoothMath m = grounded();
        m.tick(1, walk(0.42, false), K);
        assertEquals(0.0, m.step());
        m.tick(1, walk(0.33, false), K);
        assertEquals(0.0, m.step());
        //coming down from the jump is a fall, even the last few tenths
        m.tick(1, walk(-0.1, false), K);
        assertEquals(0.0, m.step());
        m.tick(1, walk(-0.2, true), K);
        assertEquals(0.0, m.step());
        assertEquals(0.0, m.offset(K, 0.5), 1e-12);
    }

    @Test
    void flyingSwimmingRidingAreNotSmoothed() {
        StepSmoothMath m = grounded();
        m.tick(1, new StepSmoothMath.Tick(0.5, true, false, LIMIT), K);
        assertEquals(0.0, m.step());
        m.tick(1, new StepSmoothMath.Tick(-0.3, false, false, LIMIT), K);
        assertEquals(0.0, m.step());
        assertEquals(0.0, m.offset(K, 0.3), 1e-12);
    }

    @Test
    void tallMovesAndTeleportsSnap() {
        StepSmoothMath m = grounded();
        m.tick(1, walk(0.5, true), K);
        assertNotEquals(0.0, m.offset(K, 0.5));
        //a block and a half up on the ground (a teleport, a server correction): the camera is at the real place at once
        m.tick(1, walk(1.5, true), K);
        assertEquals(0.0, m.offset(K, 0.0));
        assertEquals(0.0, m.offset(K, 0.7));
        m.tick(1, walk(-20, true), K);
        assertEquals(0.0, m.offset(K, 0.5));
    }

    @Test
    void stepTallerThanTheStepHeightIsNotSmoothed() {
        StepSmoothMath m = grounded();
        //a full block up on the ground with vanilla's step height (only a step-height mod lets the player do that)
        m.tick(1, walk(1.0, true), K);
        assertEquals(0.0, m.step());
        //with a step height of 1 it is smoothed; more than a block never is
        StepSmoothMath big = grounded();
        double limit = StepSmoothMath.stepLimit(1.0);
        big.tick(1, new StepSmoothMath.Tick(1.0, true, true, limit), K);
        assertEquals(1.0, big.step(), 1e-12);
        assertEquals(StepSmoothMath.stepLimit(5.0), limit);
    }

    @Test
    void longGapDropsTheOffset() {
        StepSmoothMath m = grounded();
        m.tick(1, walk(0.5, true), K);
        m.tick(StepSmoothMath.MAX_TICK_GAP + 1, walk(0, true), K);
        assertEquals(0.0, m.offset(K, 0.0));
        //a short gap only fades it as many ticks
        StepSmoothMath g = grounded();
        g.tick(1, walk(0.5, true), K);
        double end = g.offset(K, 1.0);
        g.tick(3, walk(0, true), K);
        assertEquals(end * K * K, g.offset(K, 0.0), 1e-12);
    }

    @Test
    void offsetIsHeldToAMaximum() {
        StepSmoothMath m = grounded();
        double slow = StepSmoothMath.tickFactor(100);
        for (int i = 0; i < 10; i++) {
            m.tick(1, walk(0.6, true), slow);
            for (int f = 0; f <= 4; f++) assertTrue(Math.abs(m.offset(slow, f / 4.0)) <= StepSmoothMath.MAX_OFFSET + 1e-9);
        }
    }

    @Test
    void ceilingLimitsTheOffsetAboveAndItFadesFromThere() {
        StepSmoothMath m = grounded();
        m.tick(1, walk(-0.08, false), K);
        m.tick(1, walk(-0.16, false), K);
        m.tick(1, walk(-0.26, true), K);
        double before = m.offset(K, 0.4);
        assertTrue(before > 0.1);
        assertEquals(0.05, m.limitAbove(0.05, K, 0.4), 1e-12);
        assertEquals(0.05, m.offset(K, 0.4), 1e-12);
        assertTrue(m.offset(K, 0.8) < 0.05);
        //no room at all: the camera is at the real eye
        assertEquals(0.0, m.limitAbove(-0.2, K, 0.8), 1e-12);
        //enough room: unchanged
        StepSmoothMath n = grounded();
        n.tick(1, walk(-0.3, true), K);
        double o = n.offset(K, 0.5);
        assertEquals(o, n.limitAbove(1.0, K, 0.5));
    }

    @Test
    void settingDefaultsAndLabels(@TempDir Path dir) throws Exception {
        Config c = new Config();
        assertTrue(c.smoothSteps);
        assertEquals(StepSmoothMath.STRENGTH_DEFAULT, c.stepSmoothing);
        Path file = dir.resolve("vulkanmod_settings.json");
        Files.writeString(file, "{\"fpsOverlay\": 1}");
        Config old = Config.load(file);
        assertTrue(old.smoothSteps);
        assertEquals(StepSmoothMath.STRENGTH_DEFAULT, old.stepSmoothing);
        Path lang = Path.of("src/main/resources/assets/vulkanmod/lang");
        for (String l : new String[]{"en_us", "ko_kr"}) {
            JsonObject json = JsonParser.parseString(Files.readString(lang.resolve(l + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : new String[]{"smoothSteps", "smoothSteps.tooltip", "stepSmoothing", "stepSmoothing.tooltip"}) {
                assertTrue(json.has("vulkanmod.options." + key), l + " " + key);
            }
        }
    }
}
