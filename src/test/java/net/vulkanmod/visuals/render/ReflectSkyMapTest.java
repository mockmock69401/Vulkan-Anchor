package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-20: the reflected sky map's window (the azimuths the view's water can mirror) and its mirror image. */
class ReflectSkyMapTest {
    private static final double TAU = 2 * Math.PI;

    /** Directions below the horizon at the given azimuths (degrees, atan2(x, z)) and one elevation (degrees, negative = down). */
    private static float[] dirs(double elevation, double... azimuths) {
        float[] out = new float[azimuths.length * 3];
        for (int i = 0; i < azimuths.length; i++) {
            double a = Math.toRadians(azimuths[i]), e = Math.toRadians(elevation);
            out[3 * i] = (float) (Math.sin(a) * Math.cos(e));
            out[3 * i + 1] = (float) Math.sin(e);
            out[3 * i + 2] = (float) (Math.cos(a) * Math.cos(e));
        }
        return out;
    }

    /** Whether azimuth a (radians) lies in the window. */
    private static boolean inside(float[] w, double a) {
        double u = ((a - w[0]) % TAU + TAU) % TAU;
        return u <= w[1] + 1e-5;
    }

    @Test
    void theWindowCoversTheViewsAzimuthsWithTheMargin() {
        float[] w = ReflectSkyMap.window(dirs(-10, -50, -20, 0, 30, 50));
        assertEquals(Math.toRadians(100) + 2 * ReflectSkyMap.MARGIN, w[1], 1e-4);
        assertEquals(Math.toRadians(-50) - ReflectSkyMap.MARGIN, w[0], 1e-4);
        for (double a = -50; a <= 50; a += 5) assertTrue(inside(w, Math.toRadians(a)), "azimuth " + a);
        assertTrue(!inside(w, Math.toRadians(90)) && !inside(w, Math.toRadians(-90)) && !inside(w, Math.PI));
    }

    @Test
    void aViewAcrossTheBackAzimuthStaysOneWindow() {
        float[] w = ReflectSkyMap.window(dirs(-10, 150, 180, -150));
        assertEquals(Math.toRadians(60) + 2 * ReflectSkyMap.MARGIN, w[1], 1e-4);
        for (double a : new double[]{150, 170, 180, -170, -150}) assertTrue(inside(w, Math.toRadians(a)), "azimuth " + a);
        assertTrue(!inside(w, 0));
    }

    @Test
    void aSteepViewOrAWideOneGoesAllAroundAndAnUpwardOneHasNone() {
        assertArrayEquals(new float[]{0, (float) TAU}, ReflectSkyMap.window(dirs(-85, 0)), 1e-6f);
        assertArrayEquals(new float[]{0, (float) TAU}, ReflectSkyMap.window(dirs(-5, -150, -120, -90, -60, -30, 0, 30, 60, 90, 120, 150, 180)), 1e-6f);
        //five azimuths with a widest gap of 90 degrees: the other 270 and the margins
        float[] wide = ReflectSkyMap.window(dirs(-5, -170, -90, 0, 90, 170));
        assertEquals(Math.toRadians(270) + 2 * ReflectSkyMap.MARGIN, wide[1], 1e-4);
        assertTrue(inside(wide, Math.toRadians(-170)) && inside(wide, Math.toRadians(170)) && !inside(wide, Math.toRadians(-45)));
        assertNull(ReflectSkyMap.window(dirs(20, -30, 0, 30)));
        //directions above the horizon do not widen it
        float[] mixed = new float[18];
        System.arraycopy(dirs(-10, -10, 10), 0, mixed, 0, 6);
        System.arraycopy(dirs(30, -170, 170, 90, 120), 0, mixed, 6, 12);
        assertEquals(Math.toRadians(20) + 2 * ReflectSkyMap.MARGIN, ReflectSkyMap.window(mixed)[1], 1e-4);
    }

    @Test
    void theViewDirectionsFollowTheCamerasTurn() {
        Matrix4f invProj = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000).invert();
        //view space looks down -z; invView turned by the yaw: the forward direction's azimuth is the turn
        for (double yaw : new double[]{0, 90, 180, -45}) {
            Matrix4f invView = new Matrix4f().rotateY((float) Math.toRadians(yaw)).rotateX((float) Math.toRadians(-30));
            float[] w = ReflectSkyMap.window(ReflectSkyMap.viewDirections(invProj, invView));
            float[] centre = new float[3];
            org.joml.Vector3f f = invView.transformDirection(new org.joml.Vector3f(0, 0, -1));
            centre[0] = f.x;
            centre[2] = f.z;
            assertTrue(w != null && w[1] < Math.PI, "yaw " + yaw);
            assertTrue(inside(w, Math.atan2(centre[0], centre[2])), "yaw " + yaw);
        }
    }

    @Test
    void theMirrorImageLiesAsFarUnderTheSurfaceAsTheCameraAboveIt() {
        assertEquals(-22, ReflectSkyMap.mirrorY(63, 74), 1e-6);
        assertEquals(0, ReflectSkyMap.mirrorY(63, 62), 1e-6);
        assertArrayEquals(new float[4], ReflectSkyMap.params(null, -22, 0), 0);
        assertArrayEquals(new float[4], ReflectSkyMap.params(new float[]{0, 1}, Float.NaN, 0), 0);
        assertArrayEquals(new float[]{0.5f, 1, -22, 1}, ReflectSkyMap.params(new float[]{0.5f, 1}, -22, 0), 0);
        assertArrayEquals(new float[]{0.5f, 1, -22, 2}, ReflectSkyMap.params(new float[]{0.5f, 1}, -22, 1), 0);
    }

    /** The tile flags: the made share first, then a frame's read share by its parity, the other parity's the next one. */
    @Test
    void theTileSharesFollowTheFramesParity() {
        int tiles = ReflectSkyMap.tiles();
        assertEquals((ReflectSkyMap.COLUMNS / ReflectSkyMap.TILE) * (ReflectSkyMap.ROWS / ReflectSkyMap.TILE), tiles);
        assertEquals(4L * tiles, ReflectSkyMap.readShareOffset(0));
        assertEquals(8L * tiles, ReflectSkyMap.readShareOffset(1));
        String comp = ShaderFiles.read("post/reflection.comp");
        assertTrue(comp.contains("return tiles*((odd == thisFrame) ? 2 : 1);"));
        assertTrue(comp.contains("bool odd = skyMapParams.w > 1.5;"));
    }
}
