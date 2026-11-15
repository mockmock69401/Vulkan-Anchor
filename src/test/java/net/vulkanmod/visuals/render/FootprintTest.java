package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 25: footprints (FootprintMath: the map's words, the shape, the fade, the ring, the uploads; footprint.glsl's constants). */
class FootprintTest {
    private static final String GLSL = ShaderFiles.read("post/footprint.glsl");

    private static float glsl(String name) {
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+" + name + "\\s*=\\s*([-0-9.]+)\\s*;").matcher(GLSL);
        assertTrue(m.find(), name + " missing in footprint.glsl");
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theShaderAgreesWithTheMirror() {
        assertEquals(FootprintMath.MAP, glsl("FOOT_MAP"));
        assertEquals(FootprintMath.MASK, glsl("FOOT_MASK"));
        assertEquals(FootprintMath.PER_BLOCK, glsl("FOOT_PER_BLOCK"));
        assertEquals(FootprintMath.MAP_SHIFT, glsl("FOOT_MAP_SHIFT"));
        assertEquals(FootprintMath.MAP, 1 << FootprintMath.MAP_SHIFT);
        assertEquals(FootprintMath.TIME_WRAP, glsl("FOOT_TIME_WRAP"));
        assertEquals(FootprintMath.TIME_STEP, glsl("FOOT_TIME_STEP"));
        assertEquals(FootprintMath.CAMERA_WRAP, glsl("FOOT_CAMERA_WRAP"));
        assertEquals(FootprintMath.SNOW_FADE, glsl("FOOT_SNOW_FADE"));
        assertEquals(FootprintMath.SAND_FADE, glsl("FOOT_SAND_FADE"));
        assertEquals(FootprintMath.HOLD, glsl("FOOT_HOLD"));
        assertEquals(FootprintMath.END, glsl("FOOT_END"));
        assertEquals(FootprintMath.FADE_END, glsl("FOOT_FADE_END"));
        //the composite reads it at the slots and the binding the post-process writes
        String copy = ShaderFiles.read("post/scene_copy.comp");
        assertTrue(copy.contains("layout(location = " + FootprintMath.SLOT + ") uniform vec4 footParams;"));
        assertTrue(copy.contains("layout(location = " + FootprintMath.CAMERA_SLOT + ") uniform vec4 footCamera;"));
        assertTrue(copy.contains("layout(binding = " + FootprintMath.BINDING + ") uniform usampler2D footprintMap;"));
    }

    @Test
    void aWordKeepsItsFields() {
        int w = FootprintMath.pack(4100, 200, 70, -1000, 3000, true);
        assertEquals(4100 % FootprintMath.TIME_WRAP, FootprintMath.timeOf(w));
        assertEquals(200, FootprintMath.depthOf(w));
        assertEquals(70 & 15, (w >>> 20) & 15);
        assertEquals(FootprintMath.tag(-1000, 3000), (w >>> 24) & 15);
        assertTrue((w & 0x80000000) != 0);
        assertEquals(0, FootprintMath.pack(0, 0, 0, 0, 0, false) & 0x80000000);
        //the tag tells the four wraps apart along each axis
        assertTrue(FootprintMath.tag(0, 0) != FootprintMath.tag(FootprintMath.MAP, 0));
        assertTrue(FootprintMath.tag(0, 0) != FootprintMath.tag(0, FootprintMath.MAP));
        assertEquals(FootprintMath.tag(0, 0), FootprintMath.tag(4 * FootprintMath.MAP, -4 * FootprintMath.MAP));
    }

    @Test
    void theAgeWrapsAndTheFadeHoldsThenGoesSmoothly() {
        int now = FootprintMath.timeStep(100), then = FootprintMath.timeStep(90);
        assertEquals(10, FootprintMath.age(then, now), 1e-4);
        //across the wrap
        assertEquals(1, FootprintMath.age(FootprintMath.TIME_WRAP - 4, 4), 1e-4);
        assertEquals(1, FootprintMath.fade(0, 20), 0);
        assertEquals(1, FootprintMath.fade(20 * FootprintMath.HOLD, 20), 1e-6);
        assertEquals(0, FootprintMath.fade(20, 20), 0);
        assertEquals(0, FootprintMath.fade(-1, 20), 0);
        float last = 1;
        for (float a = 0; a <= 20; a += 0.25f) {
            float f = FootprintMath.fade(a, 20);
            assertTrue(f <= last + 1e-6 && f >= 0);
            last = f;
        }
        assertEquals(0.5f, FootprintMath.fade(20 * (FootprintMath.HOLD + (1 - FootprintMath.HOLD) / 2), 20), 1e-5);
    }

    @Test
    void theShapeIsAnOvalAlongTheWalk() {
        assertEquals(1, FootprintMath.shape(0, 0), 0);
        assertEquals(0, FootprintMath.shape(FootprintMath.HALF_LENGTH, 0), 1e-6);
        assertEquals(0, FootprintMath.shape(0, FootprintMath.HALF_WIDTH), 1e-6);
        //longer than wide
        assertTrue(FootprintMath.shape(FootprintMath.HALF_LENGTH * 0.8f, 0) > FootprintMath.shape(0, FootprintMath.HALF_WIDTH * 0.8f));
        //a print of a player walking +z is long along z
        int[] map = new int[FootprintMath.MAP * FootprintMath.MAP];
        int[] box = FootprintMath.raster(map, new FootprintMath.Print(10.5, 20.5, 63, 0, 5, false));
        assertNotNull(box);
        int cx = (int) (10.5 * FootprintMath.PER_BLOCK), cz = (int) (20.5 * FootprintMath.PER_BLOCK);
        int alongZ = 0, alongX = 0;
        for (int d = -8; d <= 8; d++) {
            if (FootprintMath.depthOf(map[FootprintMath.index(cx, cz + d)]) > 0) alongZ++;
            if (FootprintMath.depthOf(map[FootprintMath.index(cx + d, cz)]) > 0) alongX++;
        }
        assertTrue(alongZ > alongX, alongZ + " vs " + alongX);
    }

    @Test
    void rasterThenClearLeavesTheMapEmptyButKeepsANewerPrintOverIt() {
        int[] map = new int[FootprintMath.MAP * FootprintMath.MAP];
        FootprintMath.Print a = new FootprintMath.Print(-3.2, 7.9, 64, 45, 10, true);
        FootprintMath.Print b = new FootprintMath.Print(-3.15, 7.95, 64, 45, 11, true);
        FootprintMath.raster(map, a);
        int drawn = 0;
        for (int w : map) if (w != 0) drawn++;
        assertTrue(drawn > 10 && drawn < 100, "a few texels: " + drawn);
        FootprintMath.clear(map, a);
        for (int w : map) assertEquals(0, w);
        FootprintMath.raster(map, a);
        FootprintMath.raster(map, b);
        FootprintMath.clear(map, a);
        int left = 0;
        for (int w : map) if (w != 0) {
            left++;
            assertEquals(FootprintMath.timeStep(11), FootprintMath.timeOf(w));
        }
        assertTrue(left > 0);
        assertNull(FootprintMath.clear(new int[FootprintMath.MAP * FootprintMath.MAP], a));
    }

    @Test
    void theRingKeepsTheNewestAndExpiresByFadeTime() {
        FootprintMath.Ring ring = new FootprintMath.Ring(3);
        FootprintMath.Print p1 = new FootprintMath.Print(0, 0, 0, 0, 1, false);
        FootprintMath.Print p2 = new FootprintMath.Print(0, 0, 0, 0, 2, true);
        FootprintMath.Print p3 = new FootprintMath.Print(0, 0, 0, 0, 3, false);
        FootprintMath.Print p4 = new FootprintMath.Print(0, 0, 0, 0, 4, false);
        assertNull(ring.add(p1));
        assertNull(ring.add(p2));
        assertNull(ring.add(p3));
        assertEquals(p1, ring.add(p4));
        assertEquals(3, ring.size());
        //p2 (sand) expires SAND_FADE after it was made, not before
        assertNull(ring.expire(2 + FootprintMath.SAND_FADE - 0.1));
        assertEquals(p2, ring.expire(2 + FootprintMath.SAND_FADE));
        assertNull(ring.expire(3 + FootprintMath.SNOW_FADE - 0.1));
        assertEquals(p3, ring.expire(3 + FootprintMath.SNOW_FADE));
        assertEquals(p4, ring.expire(1000));
        assertNull(ring.expire(1000));
        assertEquals(0, ring.size());
        //a clock gone back (a new level's flow) expires them
        ring.add(p4);
        assertEquals(p4, ring.expire(0));
    }

    @Test
    void uploadsSplitWhereTheMapWraps() {
        List<int[]> one = FootprintMath.split(new int[]{10, 20, 13, 25});
        assertEquals(1, one.size());
        assertEquals(List.of(10, 20, 4, 6), List.of(one.get(0)[0], one.get(0)[1], one.get(0)[2], one.get(0)[3]));
        List<int[]> four = FootprintMath.split(new int[]{-2, -3, 1, 2});
        assertEquals(4, four.size());
        int area = 0;
        for (int[] r : four) {
            assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= FootprintMath.MAP && r[1] + r[3] <= FootprintMath.MAP);
            area += r[2] * r[3];
        }
        assertEquals(4 * 6, area);
    }

    @Test
    void theCameraTexelIsExactAndItsWrapKeepsTheTag() {
        float[] c = FootprintMath.camera(-0.01, 1000.3);
        assertEquals(FootprintMath.CAMERA_WRAP - 1, c[0], 0);
        assertEquals(Math.floorMod(16004, FootprintMath.CAMERA_WRAP), c[1], 0);
        assertEquals(0.84f, c[2], 1e-4f);
        assertEquals(0.8f, c[3], 1e-3f);
        //the texel modulo the camera's wrap has the same tag as the world texel
        for (int t : new int[]{-5000, -1, 0, 511, 512, 3000, 123456}) {
            assertEquals(FootprintMath.tag(t, t), FootprintMath.tag(Math.floorMod(t, FootprintMath.CAMERA_WRAP), Math.floorMod(t, FootprintMath.CAMERA_WRAP)));
        }
        float[] p = FootprintMath.params(100, 50, 64.5);
        assertEquals(1, p[0], 0);
        assertEquals(FootprintMath.timeStep(50), p[1], 0);
        assertEquals(0, FootprintMath.params(0, 50, 0)[0], 0);
    }

    @Test
    void newPrintsAreUploadedEvenWhenNoFullUploadIsDue() {
        Footprints.takeFullUpload();
        Footprints.takeDirty();
        int[] box = {1, 2, 3, 4};
        Footprints.markDirty(box);
        //no full upload due: the box stays for takeDirty (it was dropped here before, so no print ever reached the map)
        assertTrue(!Footprints.takeFullUpload());
        List<int[]> d = Footprints.takeDirty();
        assertEquals(1, d.size());
        assertTrue(Footprints.takeDirty().isEmpty());
        //a full upload sends every texel: the boxes before it are moot
        Footprints.markDirty(box);
        Footprints.requestFullUpload();
        assertTrue(Footprints.takeFullUpload());
        assertTrue(Footprints.takeDirty().isEmpty());
    }
}
