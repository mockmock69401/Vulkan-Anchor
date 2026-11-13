package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 (shader roadmap session 19, the user: AstraLex's Nether heat, portal light and End galaxy sky): galaxy.glsl and heat.glsl
 * against their mirrors (GalaxyMath, HeatMath), DimensionUniforms' rules, and where the composite and the final colour use them.
 */
class DimensionEffectsTest {
    private static final String GALAXY = ShaderFiles.read("post/galaxy.glsl");
    private static final String HEAT = ShaderFiles.read("post/heat.glsl");
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp");
    private static final String FINAL = ShaderFiles.read("post/postfinal.glsl");

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static void constantsMatch(String glsl, Class<?> mirror, int atLeast) throws Exception {
        int found = 0;
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(glsl);
        while (m.find()) {
            Field f = mirror.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= atLeast, mirror.getSimpleName() + " constants found: " + found);
        for (Field f : mirror.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class)) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(glsl).find(), f.getName() + " missing in the shader");
        }
        assertFalse(Pattern.compile("(?m)^\\s*(layout|uniform)\\b").matcher(glsl).find(), "no uniforms: any shader imports it");
    }

    private static float[] dir(float azimuthDeg, float elevationDeg) {
        double a = Math.toRadians(azimuthDeg), e = Math.toRadians(elevationDeg);
        return new float[]{(float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e), (float) (-Math.cos(e) * Math.cos(a))};
    }

    @Test
    void everyShaderConstantEqualsItsMirrorsField() throws Exception {
        constantsMatch(GALAXY, GalaxyMath.class, 30);
        constantsMatch(HEAT, HeatMath.class, 18);
        assertTrue(GALAXY.contains("0x7feb352du") && GALAXY.contains("0x846ca68bu"));
        assertTrue(GALAXY.contains("vec3 galaxy(vec3 dir, float strength, float pixelAngle, vec3 mapTexel)"));
        assertTrue(HEAT.contains("float heatAmount(float strength, float dist, float camY, float dirY, float lava)"));
        //The galaxy's frame: unit pole and core at right angles
        float px = GalaxyMath.GALAXY_POLE_X, py = GalaxyMath.GALAXY_POLE_Y, pz = GalaxyMath.GALAXY_POLE_Z;
        float cx = GalaxyMath.GALAXY_CORE_X, cy = GalaxyMath.GALAXY_CORE_Y, cz = GalaxyMath.GALAXY_CORE_Z;
        assertEquals(1, px * px + py * py + pz * pz, 1e-3);
        assertEquals(1, cx * cx + cy * cy + cz * cz, 1e-3);
        assertEquals(0, px * cx + py * cy + pz * cz, 1e-3);
        //The literature named where it is used
        assertTrue(GALAXY.contains("Mellinger 2009") && HEAT.contains("Hecht"));
    }

    @Test
    void theGalaxyIsABrightBandWithStarsAndNothingWithoutStrength() {
        float[] none = {0, 0, 0};
        assertArrayEquals(new float[3], GalaxyMath.galaxy(dir(30, 40), 0, 0.001f, new float[]{1, 1, 1}));
        float pixel = 0.00085f;
        //The band (the baked light): directions in its plane are brighter on average than ones 60 degrees off it
        double in = 0, off = 0;
        int n = 0;
        float[] pole = {GalaxyMath.GALAXY_POLE_X, GalaxyMath.GALAXY_POLE_Y, GalaxyMath.GALAXY_POLE_Z};
        float[] core = {GalaxyMath.GALAXY_CORE_X, GalaxyMath.GALAXY_CORE_Y, GalaxyMath.GALAXY_CORE_Z};
        float[] side = {pole[1] * core[2] - pole[2] * core[1], pole[2] * core[0] - pole[0] * core[2], pole[0] * core[1] - pole[1] * core[0]};
        for (int i = 0; i < 360; i += 3) {
            double l = Math.toRadians(i);
            float[] d0 = new float[3], d1 = new float[3];
            for (int k = 0; k < 3; k++) {
                d0[k] = (float) (Math.cos(l) * core[k] + Math.sin(l) * side[k]);
                d1[k] = (float) (Math.cos(Math.toRadians(60)) * d0[k] + Math.sin(Math.toRadians(60)) * pole[k]);
            }
            float[] a = GalaxyMap.light(d0), b = GalaxyMap.light(d1);
            in += PostMath.luminance(a[0], a[1], a[2]);
            off += PostMath.luminance(b[0], b[1], b[2]);
            n++;
        }
        assertTrue(in / n > 4 * off / n, "band " + in / n + " vs off " + off / n);
        //The map stores the light's square root over GALAXY_MAP_SCALE, which galaxy() squares back; the light scales with the strength
        float[] c = GalaxyMap.light(core);
        float[] texel = new float[3];
        for (int k = 0; k < 3; k++) texel[k] = (float) Math.sqrt(Math.min(1, c[k] / GalaxyMath.GALAXY_MAP_SCALE));
        float[] atCore = GalaxyMath.galaxy(core, 1, pixel, texel), twice = GalaxyMath.galaxy(core, 2, pixel, texel);
        for (int k = 0; k < 3; k++) {
            assertTrue(c[k] < GalaxyMath.GALAXY_MAP_SCALE, "the core fits the map's range");
            assertTrue(atCore[k] >= c[k] - 1e-5f);
            assertEquals(2 * atCore[k], twice[k], 1e-5f);
        }
        //Stars: some directions of a fine grid off the band hold a star far brighter than the sky around it
        int stars = 0;
        for (int a = 0; a < 200; a++) {
            for (int e = 0; e < 50; e++) {
                float[] d = dir(150 + a * 0.02f, -60 + e * 0.02f);
                float[] s = GalaxyMath.galaxyStars(d, GalaxyMath.GALAXY_FAINT_CELLS, GalaxyMath.GALAXY_FAINT_BASE, GalaxyMath.GALAXY_FAINT_LIGHT, 1, pixel * GalaxyMath.GALAXY_STAR_SIGMA);
                if (s[0] > 0.05f) stars++;
            }
        }
        assertTrue(stars > 0 && stars < 2000, "star pixels " + stars);
        assertArrayEquals(new float[3], GalaxyMath.galaxy(dir(30, 40), -1, pixel, none));
    }

    @Test
    void theMapIsEquirectangularAndBakesInRange() {
        for (float[] d : new float[][]{dir(10, 20), dir(200, -45), dir(359, 80), dir(90, 0)}) {
            float[] uv = GalaxyMath.galaxyMapCoord(d);
            float[] back = GalaxyMath.mapDirection(uv[0], uv[1]);
            for (int k = 0; k < 3; k++) assertEquals(d[k], back[k], 1e-4f);
        }
        byte[] map = GalaxyMap.bake(64, 32);
        assertEquals(64 * 32 * 4, map.length);
        int lit = 0;
        for (int i = 0; i < map.length; i += 4) {
            assertEquals((byte) 255, map[i + 3]);
            if ((map[i] & 0xFF) > 40) lit++;
        }
        assertTrue(lit > 20 && lit < 64 * 32 / 2, "lit texels " + lit);
        assertEquals(4096, GalaxyMap.WIDTH);
        assertEquals(2048, GalaxyMap.HEIGHT);
    }

    @Test
    void theHeatGrowsWithDistanceAndNearLavaAndNeverNearTheCamera() {
        assertEquals(0, HeatMath.heatAmount(0, 100, 60, 0, 1));
        //Nothing right at the camera (no shimmer on what is close)
        assertEquals(0, HeatMath.heatAmount(1, HeatMath.HEAT_NEAR, 60, 0, 0), 1e-6f);
        float near = HeatMath.heatAmount(1, 20, 70, 0, 0), far = HeatMath.heatAmount(1, 120, 70, 0, 0);
        assertTrue(far > near && near > 0);
        //Over the lava sea it is hotter than high up
        assertTrue(HeatMath.heatAmount(1, 80, 34, 0, 0) > HeatMath.heatAmount(1, 80, 100, 0, 0));
        //Lava right below on screen adds heat; a nearer pixel than the lava takes none of it
        assertTrue(HeatMath.heatAmount(1, 40, 90, 0, HeatMath.heatLavaWeight(0.2f, 40, 40)) > HeatMath.heatAmount(1, 40, 90, 0, 0));
        assertEquals(0, HeatMath.heatLavaWeight(0, 10, 40), 1e-6f);
        assertTrue(HeatMath.heatAmount(2, 300, 32, 0, 1) <= 2 * HeatMath.HEAT_MAX + 1e-6f);
        //The offset scales with the heat and the screen height, and stays within its reach
        float[] a = HeatMath.heatOffsetPixels(dir(10, 5), 3, 1, 1440), b = HeatMath.heatOffsetPixels(dir(10, 5), 3, 0.5f, 720);
        assertEquals(a[0] / 4, b[0], 1e-4f);
        for (float t = 0; t < 20; t += 0.7f) {
            float[] o = HeatMath.heatOffsetPixels(dir(t * 13, 2), t, 1, 1440);
            assertTrue(Math.abs(o[0]) <= HeatMath.HEAT_PIXELS + 1e-4f && Math.abs(o[1]) <= HeatMath.HEAT_PIXELS + 1e-4f);
        }
    }

    @Test
    void theUniformsFollowTheDimensionAndTheSettings() {
        PostProcessSettings s = settings(c -> {
        });
        DimensionUniforms.Values end = DimensionUniforms.compute(s, DimensionUniforms.Kind.END, 70, 12.5);
        assertEquals(1, end.galaxy(), 1e-6f);
        assertFalse(end.heatOn());
        DimensionUniforms.Values hot = DimensionUniforms.compute(settings(c -> c.netherHeatHaze = 150), DimensionUniforms.Kind.HOT, 40, 12.5);
        assertEquals(0, hot.galaxy());
        assertEquals(1.5f, hot.heat(), 1e-6f);
        assertTrue(hot.heatOn());
        assertEquals(40, hot.cameraY());
        assertFalse(DimensionUniforms.compute(s, DimensionUniforms.Kind.OTHER, 70, 1).heatOn());
        assertEquals(0, DimensionUniforms.compute(settings(c -> c.endGalaxy = 0), DimensionUniforms.Kind.END, 70, 1).galaxy());
        assertEquals(0, DimensionUniforms.compute(settings(c -> c.enabled = false), DimensionUniforms.Kind.END, 70, 1).galaxy());
        //The time wraps
        assertEquals(5, DimensionUniforms.compute(s, DimensionUniforms.Kind.HOT, 0, HeatMath.HEAT_TIME_WRAP + 5).seconds(), 1e-2f);
        assertEquals(100, new VisualsConfig().netherHeatHaze);
        assertEquals(100, new VisualsConfig().endGalaxy);
    }

    @Test
    void theCompositeAndTheFinalColourUseThem() {
        String c = COPY.replace(String.valueOf((char) 13), "");
        assertTrue(c.contains("#import <vulkanmod:visuals/post/galaxy.glsl>") && c.contains("#import <vulkanmod:visuals/post/heat.glsl>"));
        assertTrue(c.contains("layout(location = 62) uniform vec4 dimensionParams;"));
        assertEquals(DimensionUniforms.COPY_DIMENSION, 62);
        //The galaxy behind the End's aurora on the no-sky sky
        assertTrue(c.contains("galaxy(dir, dimensionParams.x,") && c.contains("textureLod(galaxyMap, galaxyMapCoord(dir), 0.0).rgb")
                && c.contains("return vec4((scene + stars + auroraScreen(dir, p))"));
        assertTrue(c.contains("layout(binding = 26) uniform sampler2D galaxyMap;"));
        assertTrue(c.contains("layout(binding = 25, rgba16f) uniform restrict image2D heatField;"));
        assertTrue(FINAL.contains("layout(binding = 4) uniform sampler2D heatTex;") && FINAL.contains("layout(location = 5) uniform vec4 heatParams;"));
        assertTrue(FINAL.contains("uv += texture(heatTex, uv).rg;"));
    }
}
