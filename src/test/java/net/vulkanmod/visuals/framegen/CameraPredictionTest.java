package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.framegen.CameraPrediction.CameraSample;
import net.vulkanmod.visuals.framegen.CameraPrediction.Pose;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraPredictionTest {
    private static final long MS = 1_000_000L;
    private static final double CAM_X = 100.0;

    private static CameraSample sample(long ms, float pt, double x0, double x1, float yaw, float pitch) {
        return new CameraSample(ms * MS, pt, x0, 64, 0, x1, 64, 0, CAM_X, 64, 0, yaw, pitch);
    }

    private static CameraSample rot(long ms, float yaw, float pitch) {
        return sample(ms, 0.5f, 0, 0, yaw, pitch);
    }

    @Test void insideTheTickItInterpolates() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(sample(0, 0.25f, 0, 1, 0, 0));
        assertEquals(CAM_X + 0.5, p.predict(25 * MS).x(), 1e-6);
    }

    @Test void pastTheTickItExtrapolates() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(sample(0, 0.9f, 0, 1, 0, 0));
        assertEquals(CAM_X + 0.2, p.predict(10 * MS).x(), 1e-6);
    }

    @Test void rotationContinuesAtItsVelocity() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(rot(0, 0, 0));
        p.realFrame(rot(20, 2, 0));
        assertEquals(3f, p.predict(30 * MS).yaw(), 1e-4);
    }

    @Test void yawWrapsAcross180() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(rot(0, 179, 0));
        p.realFrame(rot(20, -179, 0));
        assertEquals(-178f, p.predict(30 * MS).yaw(), 1e-3);
    }

    @Test void fastTurnsAreClamped() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(rot(0, 0, 0));
        p.realFrame(rot(20, 30, 0));
        assertEquals(30f + 7.2f, p.predict(30 * MS).yaw(), 1e-3);
    }

    @Test void aStoppedMouseDoesNotDrift() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(rot(0, 10, 5));
        p.realFrame(rot(20, 40, 5));
        p.realFrame(rot(40, 40, 5));
        Pose r = p.predict(50 * MS);
        assertEquals(40f, r.yaw(), 1e-6);
        assertEquals(5f, r.pitch(), 1e-6);
    }

    @Test void theFirstFrameDoesNotRotate() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(rot(0, 33, 12));
        assertEquals(33f, p.predict(30 * MS).yaw(), 1e-6);
        p.reset();
        p.realFrame(rot(100, 33, 12));
        assertEquals(33f, p.predict(130 * MS).yaw(), 1e-6);
    }

    @Test void pitchStaysInRange() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(rot(0, 0, 88f));
        p.realFrame(rot(10, 0, 89f));
        assertEquals(90f, p.predict(30 * MS).pitch(), 1e-6);
    }

    /** The eye at (10, 64, -5), the camera boom metres behind it along the view of yaw/pitch (vanilla's third person). */
    private static final double EX = 10, EY = 64, EZ = -5, BOOM = 4;

    private static double[] behind(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        //the look direction of yaw/pitch: yaw 0 looks along +Z, pitch 90 straight down
        double lx = -Math.sin(y) * Math.cos(p), ly = -Math.sin(p), lz = Math.cos(y) * Math.cos(p);
        return new double[]{EX - BOOM * lx, EY - BOOM * ly, EZ - BOOM * lz};
    }

    private static CameraSample thirdPerson(long ms, float yaw, float pitch) {
        double[] c = behind(yaw, pitch);
        return new CameraSample(ms * MS, 0.5f, EX, EY, EZ, EX, EY, EZ, c[0], c[1], c[2], yaw, pitch);
    }

    /** Final review I1: a third-person camera orbits the eye with the predicted rotation; it does not keep its world-space offset. */
    @Test void thirdPersonOrbitsTheEye() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(thirdPerson(0, 0, 10));
        p.realFrame(thirdPerson(20, 2, 12));
        Pose r = p.predict(30 * MS);
        assertEquals(3f, r.yaw(), 1e-4);
        assertEquals(13f, r.pitch(), 1e-4);
        double[] expected = behind(r.yaw(), r.pitch());
        assertEquals(expected[0], r.x(), 1e-3);
        assertEquals(expected[1], r.y(), 1e-3);
        assertEquals(expected[2], r.z(), 1e-3);
        assertEquals(BOOM, Math.sqrt(Math.pow(r.x() - EX, 2) + Math.pow(r.y() - EY, 2) + Math.pow(r.z() - EZ, 2)), 1e-3);
    }

    /** The orbit follows the moving eye: the eye path's step plus the rotated boom. */
    @Test void thirdPersonFollowsTheEyePath() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(thirdPerson(0, 0, 0));
        double[] c = behind(2, 0);
        //eye(pt) = eye0 + (eye1 - eye0) pt: at pt 0.5 it is the fixed eye, 10 ms later (pt 0.7) 0.2 further along +X
        p.realFrame(new CameraSample(20 * MS, 0.5f, EX - 0.5, EY, EZ, EX + 0.5, EY, EZ, c[0], c[1], c[2], 2, 0));
        Pose r = p.predict(30 * MS);
        double[] expected = behind(r.yaw(), r.pitch());
        assertEquals(expected[0] + 0.2, r.x(), 1e-3);
        assertEquals(expected[1], r.y(), 1e-3);
        assertEquals(expected[2], r.z(), 1e-3);
    }

    /** First person (the camera at the eye) turns in place: the position is the eye path's alone. */
    @Test void firstPersonTurnsInPlace() {
        CameraPrediction p = new CameraPrediction();
        p.realFrame(new CameraSample(0, 0.5f, EX, EY, EZ, EX, EY, EZ, EX, EY, EZ, 0, 0));
        p.realFrame(new CameraSample(20 * MS, 0.5f, EX, EY, EZ, EX, EY, EZ, EX, EY, EZ, 30, -20));
        Pose r = p.predict(30 * MS);
        assertEquals(EX, r.x(), 1e-9);
        assertEquals(EY, r.y(), 1e-9);
        assertEquals(EZ, r.z(), 1e-9);
    }

    @Test void modelViewMatchesVanillaAxes() {
        Vector3f out = new Vector3f();
        CameraPrediction.modelView(0, 0).transformDirection(new Vector3f(0, 0, 1), out);
        assertEquals(0, out.x, 1e-5); assertEquals(0, out.y, 1e-5); assertEquals(-1, out.z, 1e-5);
        CameraPrediction.modelView(90, 0).transformDirection(new Vector3f(-1, 0, 0), out);
        assertEquals(0, out.x, 1e-5); assertEquals(0, out.y, 1e-5); assertEquals(-1, out.z, 1e-5);
        CameraPrediction.modelView(0, 90).transformDirection(new Vector3f(0, -1, 0), out);
        assertEquals(0, out.x, 1e-5); assertEquals(0, out.y, 1e-5); assertEquals(-1, out.z, 1e-5);
    }
}
