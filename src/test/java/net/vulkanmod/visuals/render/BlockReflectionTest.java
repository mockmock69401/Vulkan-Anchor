package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29: the sun's highlights and the reflections on polished and metal blocks (blockreflect.glsl, BlockReflectionMath). */
class BlockReflectionTest {
    private static final String GLSL = ShaderFiles.read("post/blockreflect.glsl");
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp");
    private static final String COMP = ShaderFiles.read("post/reflection.comp");
    private static final Set<String> JAVA_ONLY = Set.of("OFF", "HIGHLIGHTS", "REFLECTIONS");

    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(GLSL);
        int found = 0;
        while (m.find()) {
            Field f = BlockReflectionMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 19, "constants found: " + found);
        for (Field f : BlockReflectionMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || (f.getType() != float.class && f.getType() != int.class) || JAVA_ONLY.contains(f.getName())) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(GLSL).find(), f.getName() + " missing in the shader");
        }
        assertFalse(Pattern.compile("(?m)^\\s*(layout|uniform)\\b").matcher(GLSL).find(), "no uniforms or bindings");
    }

    @Test
    void theMarksAreFoliageMarkersAndTheShadersTakeThem() {
        assertEquals(LightingMath.METAL_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.METAL_ALPHA));
        assertEquals(LightingMath.POLISHED_ALPHA_CODE, LightingMath.alphaCode(FoliageMarker.POLISHED_ALPHA));
        String lighting = ShaderFiles.read("post/lighting.glsl");
        assertTrue(lighting.contains("const int METAL_ALPHA_CODE = " + LightingMath.METAL_ALPHA_CODE + ";"));
        assertTrue(lighting.contains("const int POLISHED_ALPHA_CODE = " + LightingMath.POLISHED_ALPHA_CODE + ";"));
        //both import it after lighting.glsl and water.glsl
        for (String s : new String[]{COPY, COMP}) {
            int water = s.indexOf("visuals/post/water.glsl>"), block = s.indexOf("visuals/post/blockreflect.glsl>");
            assertTrue(water > 0 && block > water);
        }
        assertTrue(COPY.contains("blockReflectionMark(smoothClass, glowParams.z)"));
        assertTrue(COMP.contains("blockReflectClass(mark) > 0"));
    }

    @Test
    void blockMarksAreNeitherWaterNorGlassAndRaysMayStopOnThem() {
        for (float mark : new float[]{BlockReflectionMath.POLISHED_MARK, BlockReflectionMath.METAL_MARK}) {
            assertTrue(WaterMath.reflectAcceptsHit(mark, 1, 10), "a smooth block is ground");
            assertTrue(BlockReflectionMath.blockReflectClass(mark) > 0);
        }
        assertFalse(WaterMath.reflectAcceptsHit(WaterMath.WATER_REFLECTION, 1, 10));
        assertFalse(WaterMath.reflectAcceptsHit(-WaterMath.GLASS_REFLECTION, 1, 10));
        assertTrue(WaterMath.reflectAcceptsHit(0, 1, 10));
        assertEquals(1, BlockReflectionMath.blockReflectClass(BlockReflectionMath.POLISHED_MARK));
        assertEquals(2, BlockReflectionMath.blockReflectClass(BlockReflectionMath.METAL_MARK));
        assertEquals(0, BlockReflectionMath.blockReflectClass(-WaterMath.GLASS_REFLECTION));
        assertEquals(0, BlockReflectionMath.blockReflectClass(Float.NaN));
        //A mark only at the second level
        assertEquals(0f, BlockReflectionMath.blockReflectionMark(2, BlockReflectionMath.HIGHLIGHTS));
        assertEquals(BlockReflectionMath.METAL_MARK, BlockReflectionMath.blockReflectionMark(2, BlockReflectionMath.REFLECTIONS));
        assertEquals(BlockReflectionMath.POLISHED_MARK, BlockReflectionMath.blockReflectionMark(1, BlockReflectionMath.REFLECTIONS));
        assertEquals(0f, BlockReflectionMath.blockReflectionMark(0, BlockReflectionMath.REFLECTIONS));
    }

    @Test
    void smoothBlocksByName() {
        for (String metal : new String[]{"iron_block", "gold_block", "copper_block", "waxed_exposed_cut_copper", "cut_copper_stairs", "netherite_block",
                "steel_block", "iron_door", "iron_bars", "chain", "damaged_anvil", "heavy_weighted_pressure_plate", "cauldron", "hopper"}) {
            assertEquals(FoliageMarker.METAL, FoliageMarker.smoothKind(metal), metal);
        }
        for (String polished : new String[]{"polished_andesite", "polished_deepslate_stairs", "polished_blackstone_bricks", "quartz_block", "smooth_quartz_slab",
                "quartz_pillar", "white_glazed_terracotta", "obsidian", "packed_ice", "blue_ice", "diamond_block", "prismarine_bricks", "dark_prismarine_slab"}) {
            assertEquals(FoliageMarker.POLISHED, FoliageMarker.smoothKind(polished), polished);
        }
        for (String rough : new String[]{"stone", "andesite", "oxidized_copper", "weathered_cut_copper", "iron_ore", "raw_iron_block", "grass_block",
                "white_concrete", "terracotta", "oak_planks", "cobblestone", "nether_quartz_ore"}) {
            assertEquals(FoliageMarker.ROUGH, FoliageMarker.smoothKind(rough), rough);
        }
        assertEquals(FoliageMarker.ROUGH, FoliageMarker.smoothKind((String) null));
    }

    @Test
    void lightSourcesAndOresKeepTheirMarks() {
        assertEquals(FoliageMarker.EMISSIVE_ALPHA, FoliageMarker.alpha(null, 1, true, false, FoliageMarker.POLISHED));
        assertEquals(FoliageMarker.ORE_ALPHA, FoliageMarker.alpha(null, 1, false, true, FoliageMarker.METAL));
        assertEquals(FoliageMarker.METAL_ALPHA, FoliageMarker.alpha(null, 1, false, false, FoliageMarker.METAL));
        assertEquals(FoliageMarker.POLISHED_ALPHA, FoliageMarker.alpha(null, 1, false, false, FoliageMarker.POLISHED));
        assertEquals(1f, FoliageMarker.alpha(null, 1, false, false, FoliageMarker.ROUGH));
    }

    @Test
    void reflectivityRisesToGrazingAndMetalTakesItsColour() {
        float[] white = {1, 1, 1};
        float[] straight = BlockReflectionMath.blockReflectivity(1, 1, white), grazing = BlockReflectionMath.blockReflectivity(1, 0.05f, white);
        assertEquals(BlockReflectionMath.POLISHED_WEIGHT * BlockReflectionMath.POLISHED_F0, straight[0], 1e-6f);
        assertTrue(grazing[0] > 5 * straight[0] && grazing[0] <= BlockReflectionMath.POLISHED_WEIGHT);
        float[] gold = BlockReflectionMath.metalTint(new float[]{0.9f, 0.7f, 0.05f});
        assertEquals(1f, gold[0], 1e-6f);
        assertEquals(BlockReflectionMath.METAL_TINT_MIN, gold[2], 1e-6f);
        float[] f = BlockReflectionMath.blockReflectivity(2, 1, gold);
        assertTrue(f[0] > f[1] && f[1] > f[2], "gold reflects warm");
        assertEquals(0f, BlockReflectionMath.blockReflectivity(0, 0.5f, white)[0]);
        float[] black = BlockReflectionMath.metalTint(new float[]{0, 0, 0});
        assertEquals(1f, black[1]);
    }

    @Test
    void theHighlightPeaksAtTheMirrorDirectionAndStaysCapped() {
        float[] up = {0, 1, 0}, white = {1, 1, 1}, sun = {1, 1, 1};
        float s = (float) Math.sqrt(0.5);
        float[] l = {s, s, 0}, mirrorV = {-s, s, 0}, offV = {0, s, s};
        float peak = BlockReflectionMath.blockHighlight(1, up, mirrorV, l, sun, white)[0];
        float off = BlockReflectionMath.blockHighlight(1, up, offV, l, sun, white)[0];
        assertTrue(peak > 0.1f && peak < 2, "polished stone: a soft highlight, " + peak);
        assertTrue(off < peak * 0.1f, "far from the mirror direction it fades");
        float metal = BlockReflectionMath.blockHighlight(2, up, mirrorV, l, sun, white)[0];
        assertTrue(metal > peak && metal <= BlockReflectionMath.HIGHLIGHT_MAX);
        //no light from below the surface, none seen from behind
        assertEquals(0f, BlockReflectionMath.blockHighlight(1, up, mirrorV, new float[]{s, -s, 0}, sun, white)[0]);
        assertEquals(0f, BlockReflectionMath.blockHighlight(1, up, new float[]{-s, -s, 0}, l, sun, white)[0]);
        float[] bright = {1000, 1000, 1000};
        assertEquals(BlockReflectionMath.HIGHLIGHT_MAX, BlockReflectionMath.blockHighlight(2, up, mirrorV, l, bright, white)[0], 1e-4f);
    }

    @Test
    void theSettingTurnsThePassOnAtItsSecondLevelOnly() {
        VisualsConfig c = new VisualsConfig();
        assertEquals(BlockReflectionMath.REFLECTIONS, c.blockReflections);
        c.waterReflections = 0;
        assertTrue(LightingChoice.reflectionPass(PostProcessSettings.from(c)));
        c.blockReflections = BlockReflectionMath.HIGHLIGHTS;
        assertFalse(LightingChoice.reflectionPass(PostProcessSettings.from(c)));
        c.blockReflections = 7;
        assertEquals(PostProcessSettings.BLOCK_REFLECTIONS_MAX, PostProcessSettings.from(c).blockReflections());
    }
}
