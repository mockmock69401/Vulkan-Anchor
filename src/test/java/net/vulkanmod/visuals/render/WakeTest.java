package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 26: the wakes (WakeMath: trails, packing, the wave; wake.glsl's consts) and the water on the lens (LensWater, lenswater.glsl). */
class WakeTest {
    private static float glsl(String file, String name) {
        String src = ShaderFiles.read(file);
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+" + name + "\\s*=\\s*([-0-9.]+)\\s*;").matcher(src);
        assertTrue(m.find(), name + " missing in " + file);
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theShaderAgreesWithTheMirror() {
        String f = "post/wake.glsl";
        assertEquals(WakeMath.MAX_CHUNKS, glsl(f, "WAKE_MAX_CHUNKS"));
        assertEquals(WakeMath.CHUNK + 1, glsl(f, "WAKE_MAX_SOURCES"));
        //every chunk of every trail has a header
        assertTrue(WakeMath.MAX_CHUNKS >= WakeMath.MAX_TRAILS * Math.ceil((WakeMath.MAX_SOURCES - 1) / (double) WakeMath.CHUNK));
        assertEquals(WakeMath.WAVELENGTH, glsl(f, "WAKE_WAVELENGTH"));
        assertEquals(WakeMath.SLOPE, glsl(f, "WAKE_SLOPE"));
        assertEquals(WakeMath.SPREAD, glsl(f, "WAKE_SPREAD"));
        assertEquals(WakeMath.Y_RANGE, glsl(f, "WAKE_Y_RANGE"));
        assertEquals(WakeMath.SHINE, glsl(f, "WAKE_SHINE"));
        assertEquals(WakeMath.FOAM, glsl(f, "WAKE_FOAM"));
        assertEquals(WakeMath.FOAM_AGE, glsl(f, "WAKE_FOAM_AGE"));
        assertEquals(WakeMath.FOAM_WHITE, glsl(f, "WAKE_FOAM_WHITE"));
        assertEquals(WakeMath.FADE_NONE, glsl(f, "WAKE_FADE_NONE"));
        assertEquals(WakeMath.FADE_FULL, glsl(f, "WAKE_FADE_FULL"));
        //every source of a trail fits (LIFE / STEP of them, one more for the frame's rounding)
        assertTrue(WakeMath.MAX_SOURCES >= Math.ceil(WakeMath.LIFE / WakeMath.STEP) + 1);
        String reflection = ShaderFiles.read("post/reflection.comp");
        assertTrue(reflection.contains("layout(binding = " + WakeMath.BINDING + ", std430) readonly restrict buffer WakeData { vec4 wakeData[]; };"));
        assertTrue(reflection.contains("layout(location = " + WakeMath.SLOT + ") uniform vec4 wakeParams;"));
        assertTrue(reflection.contains("#import <vulkanmod:visuals/post/wake.glsl>"));
        //the water on the lens
        assertEquals(LensWater.DURATION, glsl("post/lenswater.glsl", "LENS_DURATION"));
        assertEquals(LensWater.CLEAR_TIME, glsl("post/lenswater.glsl", "LENS_CLEAR_TIME"));
        assertTrue(ShaderFiles.read("post/postfinal.glsl").contains("layout(location = " + LensWater.FINAL_SLOT + ") uniform vec4 lensParams;"));
        assertTrue(ShaderFiles.read("post/post_blit.frag").contains("layout(location = " + LensWater.BLIT_SLOT + ") uniform vec4 lensParams;"));
    }

    @Test
    void theRingsRunWithTheSpeedAndTheWavesGrowWithIt() {
        assertEquals(WakeMath.RING_MIN, WakeMath.ringSpeed(0));
        assertEquals(WakeMath.KELVIN * 4, WakeMath.ringSpeed(4), 1e-6);
        assertEquals(WakeMath.RING_MAX, WakeMath.ringSpeed(100));
        assertEquals(0, WakeMath.amplitude(0, WakeMath.SIZE_WIDTH), 1e-6);
        assertEquals(WakeMath.IDLE, WakeMath.ringAmplitude(WakeMath.SIZE_WIDTH), 1e-6);
        assertEquals(1, WakeMath.amplitude(WakeMath.FULL_SPEED, WakeMath.SIZE_WIDTH), 1e-6);
        assertTrue(WakeMath.amplitude(2, 1.375f) > WakeMath.amplitude(2, 0.6f));
    }

    /** A trail moved from x0 to x1 (z 0, at the surface y 63) over seconds at 60 frames a second, from time 10. */
    private static WakeMath.Trail swim(double x0, double x1, double seconds) {
        WakeMath.Trail t = new WakeMath.Trail();
        int frames = (int) Math.round(seconds * 60);
        for (int i = 0; i <= frames; i++) {
            double k = (double) i / frames;
            t.advance(x0 + (x1 - x0) * k, 63, 0, 10 + seconds * k, true, 63.5, 0.6f);
        }
        return t;
    }

    @Test
    void aTrailSendsASourceEveryStepWhileAtTheSurface() {
        WakeMath.Trail t = swim(0, 0, 1);
        assertEquals(Math.round(1 / WakeMath.STEP) + 1, t.size(), 1);
        //newest first, one step apart, all joined but the first one sent
        for (int i = 0; i + 1 < t.size(); i++) {
            assertEquals(WakeMath.STEP, t.get(i).time() - t.get(i + 1).time(), 1e-6);
            assertTrue(t.get(i).joined());
        }
        assertFalse(t.get(t.size() - 1).joined());
        //off the surface: no new source, and the next one is not joined to the last
        int n = t.size();
        t.advance(0, 63, 0, 11.2, false, Double.NaN, 0.6f);
        assertTrue(t.size() <= n);
        t.advance(0, 63, 0, 11.4, true, 63.5, 0.6f);
        assertFalse(t.get(0).joined());
        //sources live LIFE seconds
        t.advance(0, 63, 0, 11.4 + WakeMath.LIFE + 0.01, false, Double.NaN, 0.6f);
        assertEquals(0, t.size());
    }

    private static float[] at(WakeMath.Trail t, double now, float x, float z) {
        float[] data = new float[WakeMath.VEC4S * 4];
        int[] n = WakeMath.pack(List.of(t), 0, 63.5, 0, now, data);
        assertEquals(1, n[0]);
        assertEquals((int) Math.ceil((t.size() - 1) / (double) WakeMath.CHUNK), n[1]);
        return WakeMath.wakeAt(data, WakeMath.params(1, n[1]), x, 0, z, 0.001f);
    }

    @Test
    void aStillBodySendsOutRings() {
        WakeMath.Trail t = swim(0, 0, 3);
        double now = 13;
        //within the rings' reach the surface tilts, and along the radius
        float[] s = null;
        for (float r = 0.2f; r < 0.9f && (s == null || Math.abs(s[0]) < 1e-4); r += 0.05f) s = at(t, now, r, 0);
        assertTrue(Math.abs(s[0]) > 1e-4, "no ring near the body");
        assertEquals(0, s[1], 1e-5);
        //beyond the oldest ring nothing
        float reach = WakeMath.RING_MIN * WakeMath.LIFE;
        float[] far = at(t, now, reach + 0.5f, 0);
        assertEquals(0, far[0], 0);
        assertEquals(0, far[1], 0);
    }

    @Test
    void aFastSwimmerLeavesAV() {
        //5 blocks a second along x for two seconds: rings at 1.7, a V of half angle asin(1.7 / 5), about 20 degrees
        WakeMath.Trail t = swim(0, 10, 2);
        double now = 12;
        float inside = 0, outside = 0;
        for (float z = -1.2f; z <= 1.2f; z += 0.1f) {
            float[] s = at(t, now, 6, z);
            inside += Math.abs(s[0]) + Math.abs(s[1]);
        }
        //beyond the arms (about 1.5 blocks out 4 blocks behind) and the packet's reach past them
        for (float z = 2.2f + 3 * WakeMath.PACKET_WIDTH; z <= 4.5f; z += 0.1f) {
            float[] s = at(t, now, 6, z);
            float[] m = at(t, now, 6, -z);
            outside += Math.abs(s[0]) + Math.abs(s[1]) + Math.abs(m[0]) + Math.abs(m[1]);
        }
        assertTrue(inside > 0.05f, "no wake inside the V: " + inside);
        assertEquals(0, outside, 0, "a wake outside the V");
        //ahead of the swimmer nothing
        float[] ahead = at(t, now, 11.5f, 0);
        assertEquals(0, Math.abs(ahead[0]) + Math.abs(ahead[1]), 0);
        //and the V is mirrored across the path
        float[] a = at(t, now, 7, 0.5f), b = at(t, now, 7, -0.5f);
        assertEquals(a[0], b[0], 1e-4);
        assertEquals(a[1], -b[1], 1e-4);
    }

    @Test
    void aTrailFarFromThePixelOrOffItsWaterIsSkipped() {
        WakeMath.Trail t = swim(0, 0, 1);
        float[] data = new float[WakeMath.VEC4S * 4];
        int n = WakeMath.pack(List.of(t), 0, 63.5, 0, 11, data)[1];
        //the oldest chunk's circle holds its rings
        int last = 2 * (n - 1) * 4;
        assertTrue(data[last + 2] >= WakeMath.RING_MIN * 1 + WakeMath.WAVELENGTH - 1e-4);
        assertEquals(0, data[3], 1e-6);
        float[] below = WakeMath.wakeAt(data, WakeMath.params(1, n), 0.3f, -2, 0, 0.001f);
        assertEquals(0, below[0], 0);
        //off: nothing
        assertEquals(0, WakeMath.params(0, n)[0]);
        assertEquals(0, WakeMath.params(1, 0)[1]);
    }

    @Test
    void theLensComesOutAsWetAsTheDive() {
        assertEquals(LensWater.WET_MIN, LensWater.wetness(0.01), 1e-6);
        assertEquals(1, LensWater.wetness(5), 1e-6);
        assertEquals(0.5f, LensWater.wetness(0.3), 1e-6);
    }
}
