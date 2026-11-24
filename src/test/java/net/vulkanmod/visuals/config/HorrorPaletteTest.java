package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CelestialUniforms;
import net.vulkanmod.visuals.render.CloudMath;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-24 (the user's seven Horror references, revision 30): a noon that looks like the sun is about to set, a grey-green rain, a
 * near-black navy night, cold dark green caves, light bright only near its source with a short reach (the held light too), more fog and
 * bloom, no stars or auroras; then "정오에 물속 일렁임도 없애줘" (no caustics) and "기본 구름을 현실적 구름으로 맞추고 바다와 강의 색도 어두운
 * 초록빛이 돌게 만들어줘" (the realistic clouds, dark green water).
 */
class HorrorPaletteTest {
    private static final String COPY = ShaderFiles.read("post/scene_copy.comp").replace(String.valueOf((char) 13), "");
    private static final String LIGHT = ShaderFiles.read("post/palettelight.glsl").replace(String.valueOf((char) 13), "");

    /** A config on the Horror palette as revisions 21 to 29 saved it. */
    private static VisualsConfig oldHorror() {
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.HORROR);
        for (Colour colour : Colour.values()) {
            c.setColour(colour, colour.ordinal() < ColourPresets.HORROR_BEFORE_30.length ? ColourPresets.HORROR_BEFORE_30[colour.ordinal()] : colour.defaultRgb());
        }
        for (int i = 0; i < ColourPresets.LOOK_KEYS.length; i++) {
            VisualsOptionLayout.set(c, VisualsOptionLayout.item(ColourPresets.LOOK_KEYS[i]), ColourPresets.HORROR_LOOK_BEFORE_30[i]);
        }
        c.cloudStyle = CloudMath.STYLE_VANILLA;
        c.colourPreset = ColourPresets.HORROR;
        return c;
    }

    /** Writes c as a file of revision, without the fields that revision did not have. */
    private static Path saved(VisualsConfig c, Path dir, int revision) throws Exception {
        Path f = dir.resolve("horror" + revision + ".json");
        c.write(f);
        Files.writeString(f, Files.readString(f).replaceAll("\\s*\"(colourCave|lightReach)\":\\s*\\d+,?", ""));
        OldFile.age(f, revision);
        return f;
    }

    @Test
    void horrorIsSetFromTheReferences() {
        VisualsConfig c = new VisualsConfig();
        ColourPresets.apply(c, ColourPresets.HORROR);
        assertEquals(ColourPresets.HORROR, ColourPresets.detect(c));
        //No stars (their colour black) and no auroras
        assertEquals(0, c.colour(Colour.STARS));
        assertEquals(CelestialUniforms.AURORA_OFF, c.aurora);
        //Cold dark green caves: the cave light greener than red, darker than white
        int cave = c.colour(Colour.CAVE);
        assertNotEquals(Colour.CAVE.defaultRgb(), cave);
        assertTrue(((cave >> 8) & 0xFF) > ((cave >> 16) & 0xFF), "green over red");
        //A short light reach, no caustics, the realistic clouds, more fog and bloom than before
        assertTrue(c.lightReach < 100 && c.lightReach >= VisualsOptionLayout.LIGHT_REACH_MIN);
        assertEquals(0, c.caustics);
        assertEquals(CloudMath.STYLE_REALISTIC, c.cloudStyle);
        assertTrue(c.bloomStrength > ColourPresets.HORROR_LOOK_BEFORE_30[0] && c.fogStrength > ColourPresets.HORROR_LOOK_BEFORE_30[2]);
        //"물속 투명도도 더 불투명하게", "해의 눈부심": deeper water colour, a stronger sun glow
        assertTrue(c.waterDepth > 100 && c.sunGlow > 50);
        //"횃불과 랜턴에서 따뜻한 노란색 느낌을 좀 더 줄여줘": the torch light paler than the default swatch (blue closer to red)
        int torch = c.colour(Colour.BLOCK_LIGHT), torchDefault = Colour.BLOCK_LIGHT.defaultRgb();
        assertTrue(((torch >> 16) & 0xFF) - (torch & 0xFF) < ((torchDefault >> 16) & 0xFF) - (torchDefault & 0xFF));
        //Noon about to set: a warm (red over blue) sunlight, sky and sun glow
        for (Colour warm : new Colour[]{Colour.SUNLIGHT, Colour.DAY_SKY, Colour.SUN_GLOW}) {
            int rgb = c.colour(warm);
            assertTrue(((rgb >> 16) & 0xFF) > (rgb & 0xFF), warm.name());
        }
        //A navy night: blue over red; dark green water: green over red and blue
        int night = c.colour(Colour.NIGHT_SKY), water = c.colour(Colour.WATER);
        assertTrue((night & 0xFF) > ((night >> 16) & 0xFF));
        assertTrue(((water >> 8) & 0xFF) > ((water >> 16) & 0xFF) && ((water >> 8) & 0xFF) > (water & 0xFF));
        //The other palettes leave all of it as it was
        for (int level : new int[]{ColourPresets.DEFAULT, ColourPresets.ANIME}) {
            assertEquals(100, ColourPresets.look("light_reach", level));
            assertEquals(100, ColourPresets.look("caustics", level));
            assertEquals(50, ColourPresets.look("sun_glow", level));
            assertEquals(100, ColourPresets.look("water_depth", level));
            assertEquals(Colour.CAVE.defaultRgb(), ColourPresets.value(Colour.CAVE, level));
        }
        assertEquals(100, new VisualsConfig().lightReach);
    }

    /** Each value the palette sets shows Custom once the player changes it. */
    @Test
    void changingAnyOfItShowsCustom() {
        java.util.List<java.util.function.Consumer<VisualsConfig>> changes = java.util.List.of(
                c -> c.lightReach = 100, c -> c.caustics = 100, c -> c.colourCave = 0xFFFFFF, c -> c.colourStars = 0xFFFFFF,
                c -> c.cloudStyle = CloudMath.STYLE_VANILLA, c -> c.colourWater = 0x2E4E46, c -> c.sunGlow = 50, c -> c.waterDepth = 100);
        for (java.util.function.Consumer<VisualsConfig> change : changes) {
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, ColourPresets.HORROR);
            change.accept(c);
            assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(c));
        }
    }

    /** A file saved on the old Horror palette takes the retuned one and stays on Horror; one tuned by hand keeps its values. */
    @Test
    void anOldHorrorFileTakesTheRetunedPalette(@TempDir Path dir) throws Exception {
        VisualsConfig moved = VisualsConfig.read(saved(oldHorror(), dir, 29));
        assertEquals(ColourPresets.HORROR, moved.colourPreset);
        assertEquals(ColourPresets.look("light_reach", ColourPresets.HORROR), moved.lightReach);
        assertEquals(0, moved.caustics);
        assertEquals(0, moved.colour(Colour.STARS));
        assertEquals(ColourPresets.value(Colour.CAVE, ColourPresets.HORROR), moved.colour(Colour.CAVE));
        assertEquals(CloudMath.STYLE_REALISTIC, moved.cloudStyle);
        assertEquals(VisualsConfig.currentRevision(), moved.revision);
        //From before the trimmed rows (revision 20: fog 200)
        VisualsConfig older = oldHorror();
        older.fogStrength = 200;
        assertEquals(ColourPresets.HORROR, VisualsConfig.read(saved(older, dir, 20)).colourPreset);
        //Tuned by hand: kept, and Custom
        VisualsConfig tuned = oldHorror();
        tuned.saturation = 77;
        VisualsConfig kept = VisualsConfig.read(saved(tuned, dir, 29));
        assertEquals(ColourPresets.CUSTOM, kept.colourPreset);
        assertEquals(77, kept.saturation);
        assertEquals(ColourPresets.HORROR_BEFORE_30[Colour.DAY_SKY.ordinal()], kept.colour(Colour.DAY_SKY));
        assertEquals(100, kept.lightReach);
        //Another palette's file is left as it was
        VisualsConfig anime = new VisualsConfig();
        ColourPresets.apply(anime, ColourPresets.ANIME);
        assertEquals(ColourPresets.ANIME, VisualsConfig.read(saved(anime, dir, 29)).colourPreset);
    }

    //palettelight.glsl, ported
    private static double level(double l, double reach) {
        if (reach > 0.999 && reach < 1.001) return l;
        return 15 * Math.pow(Math.max(0, Math.min(1, l / 15)), 1 / Math.max(reach, 0.05));
    }

    private static double core(double l, double reach) {
        double t = Math.max(0, Math.min(1, (l - 11) / 4));
        return 1 + 0.9 * Math.max(1 / Math.max(reach, 0.05) - 1, 0) * t * t * (3 - 2 * t);
    }

    /**
     * Light bright near its source and falling off fast: the source keeps level 15 and burns brighter, a few blocks away it is darker than
     * vanilla's, and at reach 100 nothing changes. Every block light takes it (the held light's too), and caves the cave light.
     */
    @Test
    void lightReachesLessAndBurnsAtItsSource() {
        double reach = ColourPresets.look("light_reach", ColourPresets.HORROR) / 100.0;
        assertEquals(15, level(15, reach), 1e-9);
        assertTrue(level(10, reach) < 10 - 1 && level(5, reach) < 5 - 1, "shorter reach");
        assertTrue(core(15, reach) > 1.2 && core(8, reach) == 1, "a bright core only near the source");
        assertEquals(10, level(10, 1), 1e-9);
        assertEquals(1, core(15, 1), 1e-9);
        assertTrue(COPY.contains("#import <vulkanmod:visuals/post/palettelight.glsl>"));
        assertTrue(COPY.contains("layout(location = 83) uniform vec4 caveLight;"));
        int held = COPY.indexOf("blockT = max(blockT, heldLevelAt(");
        int remap = COPY.indexOf("blockT = lightReachLevel(blockT, caveLight.w);");
        assertTrue(held > 0 && remap > held, "after the held light");
        assertTrue(COPY.contains("vec3 blockShare = (vec3(1.0) - share)*be*lightReachCore(blockT, caveLight.w);"));
        assertTrue(COPY.contains("lit *= caveTint(caveLight.rgb, skyT, blockT);"));
        assertTrue(LIGHT.contains("const float LIGHT_REACH_CORE = 0.9;") && LIGHT.contains("const float LIGHT_REACH_CORE_FROM = 11.0;"));
    }
}
