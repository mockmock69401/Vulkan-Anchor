package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P7o design 4.1-4.2: the vanilla sections cast into the near cascade. */
class VanillaShadowCastersTest {
    private record S(boolean drawable, int blockX, int blockY, int blockZ, Object area) implements VanillaShadowCasters.Section {
    }

    /** VulkanMod's DrawBuffers.encodeSectionOffset, as its bytecode reads. */
    private static int encode(int x, int y, int z, int minHeight) {
        return (x & 127) | (z & 127) << 8 | ((y - minHeight) & 127) << 16;
    }

    private static Matrix4f noonCascade() {
        return ShadowMath.cascade(new Vector3f(0.3f, 1, 0), 0, 70, 0, ShadowMath.NEAR_RADIUS).viewProjection();
    }

    /** Review focus 3: the instance index carries VulkanMod's section offset; decoded with terrain.vsh's bits. */
    @Test
    void theInstanceIndexDecodesLikeTerrainVsh() {
        for (int[] c : new int[][]{{0, 0, 0}, {16, 32, 48}, {112, 96, 80}, {127, 127, 127}}) {
            assertArrayEquals(c, VanillaShadowCasters.baseOffset(encode(c[0], c[1] - 64, c[2], -64)), c[0] + "," + c[1] + "," + c[2]);
        }
    }

    @Test
    void theLightBoxKeepsWhatCanCast() {
        Matrix4f vp = noonCascade();
        assertTrue(VanillaShadowCasters.inLightBox(vp, 0, -6, 0), "under the camera");
        assertTrue(VanillaShadowCasters.inLightBox(vp, 150, -6, 0), "near the edge");
        assertFalse(VanillaShadowCasters.inLightBox(vp, 400, -6, 0), "far outside");
        assertFalse(VanillaShadowCasters.inLightBox(vp, 0, -6, 400), "far outside in z");
        assertTrue(VanillaShadowCasters.inLightBox(vp, 0, 300, 0), "high above, toward the sun: within the depth margin");
    }

    /** Review focus 2: sections out of the camera's view still cast; empty or uncompiled ones do not; grouped by area. */
    @Test
    void collectsDrawableSectionsByArea() {
        Object a = new Object(), b = new Object();
        List<S> grid = List.of(new S(true, 0, 64, 0, a), new S(false, 16, 64, 0, a), new S(true, -32, 48, -16, b), new S(true, 16, 64, 16, a),
                new S(true, 4000, 64, 0, b), new S(true, 128, 64, 0, b));
        List<VanillaShadowCasters.Area> areas = VanillaShadowCasters.collect(grid, noonCascade(), 0.5, 70, 0.5, 64);
        assertEquals(2, areas.size());
        assertSame(a, areas.get(0).area());
        assertEquals(2, areas.get(0).sections().size(), "the empty section is left out");
        assertSame(b, areas.get(1).area());
        assertEquals(1, areas.get(1).sections().size(), "the far section and the one past the reach (128 blocks, inside the cascade) are left out");
    }

    /** The reach is an argument: 64 keeps today's selection, 256 takes the sections between, anything past it stays out. */
    @Test
    void radiusLimitsCasters() {
        Matrix4f wide = ShadowMath.cascade(new Vector3f(0.3f, 1, 0), 0, 70, 0, 400).viewProjection();
        Object area = new Object();
        List<S> grid = new java.util.ArrayList<>();
        //A lattice of 16-block sections, 33 by 33 around the camera (x, z in -256..256), on the camera's level
        for (int x = -256; x <= 256; x += 16) {
            for (int z = -256; z <= 256; z += 16) grid.add(new S(true, x, 64, z, area));
        }
        for (float radius : new float[]{64, 256}) {
            java.util.Set<List<Integer>> expected = new java.util.HashSet<>();
            for (S s : grid) {
                //Same rule as the lattice: the section's centre within radius + 8 on both axes
                if (Math.max(Math.abs(s.blockX() + 8), Math.abs(s.blockZ() + 8)) <= radius + 8) expected.add(List.of(s.blockX(), s.blockZ()));
            }
            java.util.Set<List<Integer>> got = new java.util.HashSet<>();
            for (VanillaShadowCasters.Area a : VanillaShadowCasters.collect(grid, wide, 0, 70, 0, radius)) {
                for (VanillaShadowCasters.Section s : a.sections()) got.add(List.of(s.blockX(), s.blockZ()));
            }
            assertEquals(expected, got, "radius " + radius);
        }
        //64 is the old fixed reach: what 64 takes is a strict subset of what 256 takes
        int n64 = VanillaShadowCasters.collect(grid, wide, 0, 70, 0, 64).get(0).sections().size();
        int n256 = VanillaShadowCasters.collect(grid, wide, 0, 70, 0, 256).get(0).sections().size();
        assertTrue(n64 < n256);
        //A section at x 200 is a caster at 256 and not at 64
        assertEquals(0, VanillaShadowCasters.collect(List.of(new S(true, 200, 64, 0, area)), wide, 0, 70, 0, 64).size());
        assertEquals(1, VanillaShadowCasters.collect(List.of(new S(true, 200, 64, 0, area)), wide, 0, 70, 0, 256).size());
        assertEquals(0, VanillaShadowCasters.collect(List.of(new S(true, 300, 64, 0, area)), wide, 0, 70, 0, 256).size());
    }

    @Test
    void theShadersMirrorVulkanModsTerrainDecoding() throws Exception {
        String v = read("assets/vulkanmod/visuals/shaders/shadow/vanilla.vert"), f = read("assets/vulkanmod/visuals/shaders/shadow/vanilla.frag");
        assertTrue(v.contains("ivec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceID + gl_BaseInstance) >> ivec3(0, 16, 8), 0, 8);"));
        assertTrue(v.contains("vec3 pos = fma(vec3(Position.xyz), vec3(1.0/2048.0), areaOffset.xyz + vec3(baseOffset));"));
        //Review focus 3: GlslForVulkan turns gl_InstanceID into gl_InstanceIndex - gl_BaseInstance, so the sum is the full index
        String vk = net.vulkanmod.visuals.vk.GlslForVulkan.toVulkan(v, java.util.Map.of()).source();
        assertTrue(vk.replace(" ", "").contains("ivec3((gl_InstanceIndex-gl_BaseInstance)+gl_BaseInstance)"), vk);
        assertTrue(f.contains("if (textureLod(atlas, uv, 0.0).a < cutout) {"), "cutouts cast their own shape, by the layer's cutout");
        assertTrue(v.contains("cutout = areaOffset.w;"), "final review I1: the layer's cutout comes with the area offset");
    }

    private static String read(String path) throws Exception {
        try (InputStream in = VanillaShadowCastersTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
