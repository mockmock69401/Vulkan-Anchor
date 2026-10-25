package net.vulkanmod.visuals.framegen;

import org.joml.Matrix3d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Predicts the camera pose of a generated frame from the last real frames (FG design 3.3).
 * Rotation continues at the velocity of the last two real frames, clamped. Position follows the
 * entity eye path between ticks (interpolated, then extrapolated at the last tick velocity) plus
 * the real camera's offset from the eye held in the camera's own frame: a third-person camera
 * (vanilla F5, Shoulder Surfing) orbits the eye as the view turns, as the game would place it,
 * and a first-person camera (no offset) turns in place. Pure: no clock.
 */
public final class CameraPrediction {
    public static final float MAX_TURN_DEG_PER_S = 720f;
    public static final long TICK_NANOS = 50_000_000L;

    /** eye0/eye1 are the camera entity's eye position at the previous and current tick. */
    public record CameraSample(long nanos, float partialTick,
                               double eyeX0, double eyeY0, double eyeZ0,
                               double eyeX1, double eyeY1, double eyeZ1,
                               double camX, double camY, double camZ,
                               float yaw, float pitch) {}

    public record Pose(double x, double y, double z, float yaw, float pitch) {}

    private CameraSample latest;
    private CameraSample previous;

    public void realFrame(CameraSample s) {
        previous = latest;
        latest = s;
    }

    public void reset() {
        latest = null;
        previous = null;
    }

    /** Pose at {@code contentNanos}; null before the first real frame. */
    public Pose predict(long contentNanos) {
        CameraSample s = latest;
        if (s == null) return null;

        long dt = contentNanos - s.nanos();
        double ptN = s.partialTick();
        double ptT = ptN + (double) dt / TICK_NANOS;
        // eye(pt) is linear in pt, so beyond 1 it continues at eye1 - eye0 per tick
        double eyeX = s.eyeX0() + (s.eyeX1() - s.eyeX0()) * ptT;
        double eyeY = s.eyeY0() + (s.eyeY1() - s.eyeY0()) * ptT;
        double eyeZ = s.eyeZ0() + (s.eyeZ1() - s.eyeZ0()) * ptT;

        float yaw = s.yaw();
        float pitch = s.pitch();
        CameraSample p = previous;
        if (p != null && s.nanos() > p.nanos()) {
            double span = (s.nanos() - p.nanos()) * 1e-9;
            double dyaw = wrap180(s.yaw() - p.yaw());
            double yawVel = clamp(dyaw / span, MAX_TURN_DEG_PER_S);
            double pitchVel = clamp((s.pitch() - p.pitch()) / span, MAX_TURN_DEG_PER_S);
            double sec = dt * 1e-9;
            yaw = (float) (s.yaw() + yawVel * sec);
            pitch = (float) Math.max(-90.0, Math.min(90.0, s.pitch() + pitchVel * sec));
        }
        // the camera's offset from the eye in the sample's view frame, back into the world at the predicted rotation
        Vector3d off = new Vector3d(s.camX() - (s.eyeX0() + (s.eyeX1() - s.eyeX0()) * ptN),
                s.camY() - (s.eyeY0() + (s.eyeY1() - s.eyeY0()) * ptN),
                s.camZ() - (s.eyeZ0() + (s.eyeZ1() - s.eyeZ0()) * ptN));
        if (yaw != s.yaw() || pitch != s.pitch()) rotation(yaw, pitch).transformTranspose(rotation(s.yaw(), s.pitch()).transform(off));
        return new Pose(eyeX + off.x, eyeY + off.y, eyeZ + off.z, yaw, pitch);
    }

    /** {@link #modelView}'s rotation in double precision (world to view; its inverse is its transpose). */
    private static Matrix3d rotation(float yaw, float pitch) {
        return new Matrix3d().rotationX(Math.toRadians(pitch)).rotateY(Math.toRadians(yaw + 180.0));
    }

    /** Same rotation as GameRenderer.renderLevel: rotationX(pitch) then rotationY(yaw + 180). */
    public static Matrix4f modelView(float yaw, float pitch) {
        return new Matrix4f().rotationX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw + 180f));
    }

    private static double wrap180(double d) {
        d = (d + 180.0) % 360.0;
        if (d < 0) d += 360.0;
        return d - 180.0;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }
}
