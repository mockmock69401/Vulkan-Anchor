package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.config.VisualsPresets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-11 (shader roadmap session 12, the user: the stars' number, size and brightness at night): StarMath and its wiring. */
class StarMathTest {
    @Test
    void theDefaultsAreVanillasStarsExactly() {
        VisualsConfig c = new VisualsConfig();
        assertEquals(100, c.starCount);
        assertEquals(100, c.starSize);
        assertEquals(100, c.starBrightness);
        assertEquals(1500, StarMath.tries(c.starCount));
        assertEquals(1f, StarMath.sizeScale(c.starSize));
        assertEquals(0.15f, StarMath.VANILLA_SIZE_BASE * StarMath.sizeScale(100));
        assertEquals(0.1f, StarMath.VANILLA_SIZE_SPREAD * StarMath.sizeScale(100));
        for (float v = 0; v <= StarMath.VANILLA_BRIGHTNESS_MAX; v += 0.0137f) {
            assertEquals(Float.floatToIntBits(v), Float.floatToIntBits(StarMath.drawValue(v, c.starBrightness)), "b " + v);
        }
    }

    @Test
    void theCountScalesVanillasTriesWithinTheIndexBuffer() {
        assertEquals(0, StarMath.tries(0));
        assertEquals(750, StarMath.tries(50));
        assertEquals(3000, StarMath.tries(200));
        assertEquals(6000, StarMath.tries(StarMath.COUNT_MAX));
        assertEquals(6000, StarMath.tries(1000), "clamped");
        assertEquals(0, StarMath.tries(-5), "clamped");
        int last = -1;
        for (int p = 0; p <= StarMath.COUNT_MAX; p++) {
            int t = StarMath.tries(p);
            assertTrue(t >= last, "monotonic at " + p);
            last = t;
        }
        //every kept try is one quad of 4 vertices; the shared QUADS index buffer holds 65536 vertices
        assertTrue(StarMath.tries(StarMath.COUNT_MAX) * 4 <= 65536);
        assertEquals(65536 / 4, StarMath.MAX_TRIES);
    }

    @Test
    void theSizeScalesEveryStar() {
        assertEquals(0.5f, StarMath.sizeScale(StarMath.SIZE_MIN));
        assertEquals(3f, StarMath.sizeScale(StarMath.SIZE_MAX));
        assertEquals(0.5f, StarMath.sizeScale(0), "clamped to the minimum");
        assertEquals(3f, StarMath.sizeScale(900), "clamped");
        assertEquals(2f, StarMath.sizeScale(200));
    }

    @Test
    void theBrightnessScalesTheAddedLight() {
        float[] nights = {0.01f, 0.1f, 0.25f, 0.4f, StarMath.VANILLA_BRIGHTNESS_MAX};
        for (float v : nights) {
            float vanilla = StarMath.addedLight(v);
            for (int p = 0; p <= StarMath.BRIGHTNESS_MAX; p += 10) {
                float d = StarMath.drawValue(v, p);
                assertTrue(d >= 0 && d <= 1, "the shader colour stays in 0..1");
                assertEquals(vanilla * p / 100f, StarMath.addedLight(d), 1e-5f, "b " + v + " at " + p);
            }
        }
        assertEquals(0f, StarMath.drawValue(0.5f, 0), "0: no draw (vanilla skips b = 0)");
        assertEquals(0f, StarMath.drawValue(0f, 300));
        assertEquals(1f, StarMath.drawValue(StarMath.VANILLA_BRIGHTNESS_MAX, StarMath.BRIGHTNESS_MAX), 1e-6f, "the night's b reaches 1 at the maximum");
        assertEquals(StarMath.drawValue(0.5f, StarMath.BRIGHTNESS_MAX), StarMath.drawValue(0.5f, 9999), "clamped");
    }

    @Test
    void theLayoutHasTheThreeSettingsAroundTheirDefaults() {
        String[][] items = {{"star_count", "0", "" + StarMath.COUNT_MAX}, {"star_size", "" + StarMath.SIZE_MIN, "" + StarMath.SIZE_MAX},
                {"star_brightness", "0", "" + StarMath.BRIGHTNESS_MAX}};
        VisualsConfig c = new VisualsConfig();
        for (String[] i : items) {
            VisualsOptionLayout.Item item = VisualsOptionLayout.item(i[0]);
            assertNotNull(item, i[0]);
            assertEquals(VisualsOptionLayout.Kind.PERCENT, item.kind());
            assertEquals(Integer.parseInt(i[1]), item.min(), i[0]);
            assertEquals(Integer.parseInt(i[2]), item.max(), i[0]);
            assertEquals(VisualsOptionLayout.Impact.NONE, item.impact(), "no cost: " + i[0]);
            assertEquals(100, VisualsOptionLayout.get(c, item), i[0]);
            VisualsOptionLayout.set(c, item, item.max());
            assertEquals(item.max(), VisualsOptionLayout.get(c, item), i[0]);
        }
        assertEquals(StarMath.COUNT_MAX, c.starCount);
        assertEquals(StarMath.SIZE_MAX, c.starSize);
        assertEquals(StarMath.BRIGHTNESS_MAX, c.starBrightness);
    }

    @Test
    void neitherThePresetsNorThePalettesSetThem() {
        for (String key : new String[]{"star_count", "star_size", "star_brightness"}) {
            assertFalse(VisualsPresets.VALUES.containsKey(key), key);
            assertFalse(Arrays.asList(ColourPresets.LOOK_KEYS).contains(key), key);
        }
        VisualsConfig c = new VisualsConfig();
        c.starCount = 20;
        c.starSize = 250;
        c.starBrightness = 30;
        assertEquals(VisualsPresets.DEFAULT, VisualsPresets.validated(c), "look-only settings keep the level");
        for (int level = 0; level < ColourPresets.CUSTOM; level++) {
            VisualsConfig p = new VisualsConfig();
            ColourPresets.apply(p, level);
            p.starCount = 20;
            p.starBrightness = 30;
            assertEquals(level, ColourPresets.validated(p), "palette " + level);
        }
    }

    /** Session 12 follow-up (the user: "the star colour in the colours section too"): ShaderColours.STARS. */
    @Test
    void theStarColourIsWhiteByDefaultAndTintsOnlyThroughTheTintedBuild() throws IOException {
        assertEquals(0xFFFFFF, ShaderColours.Colour.STARS.defaultRgb(), "vanilla's stars are white");
        assertEquals(ShaderColours.Colour.NIGHT_SKY.ordinal() + 1, ShaderColours.Colour.STARS.ordinal(), "next to the night sky");
        VisualsConfig c = new VisualsConfig();
        assertEquals(0xFFFFFF, c.colourStars);
        assertSame(ShaderColours.DEFAULT, ShaderColours.of(c.colours()));
        CelestialUniforms.Values off = CelestialUniforms.off();
        assertFalse(off.tinted(), "the default colour keeps the plain build");
        assertArrayEquals(new float[]{1, 1, 1, 0}, java.util.Arrays.copyOfRange(off.tints(), 20, 24), 0);
        c.colourStars = 0xFFB070;
        ShaderColours picked = ShaderColours.of(c.colours());
        float[] t = CelestialUniforms.tints(picked, false);
        assertArrayEquals(ShaderColours.tint(0xFFB070, 0xFFFFFF), java.util.Arrays.copyOfRange(t, 20, 23), 0);
        assertTrue(new CelestialUniforms.Values(new float[4], new float[4], t).tinted(), "a picked star colour runs the .TINTED build");
        assertFalse(new CelestialUniforms.Values(new float[4], new float[4], t).auroraTinted(), "not the reflection's (no stars there)");
        assertEquals(VisualsOptionLayout.Kind.COLOUR, VisualsOptionLayout.item("colour_stars").kind());
        //the shader: slot 60, applied in the .TINTED build only, the plain build's sum unchanged
        String comp = Files.readString(Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/scene_copy.comp"));
        assertTrue(comp.contains("layout(location = 60) uniform vec4 starTint;"));
        assertTrue(comp.replace("\r\n", "\n").contains("#ifdef ATMOSPHERE_TINTS\n        stars *= vanillaStarTint(a, dir, starTint);\n#endif"));
        assertEquals(60, SceneCompositeMath.STAR_TINT);
        String water = Files.readString(Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/water.glsl"));
        assertTrue(water.contains("return mix(tint.rgb, vec3(1.0), discCover(dir, -a.sun.xyz, VANILLA_MOON_MASK));"));
    }

    @Test
    void theStarTintLeavesVanillasSquareMoonWhite() {
        float[] block = new float[40];
        block[0] = 0;
        block[1] = -1;
        block[2] = 0;
        block[3] = AtmosphereMath.ATMOSPHERE_SKY;
        AtmosphereMath.Atmosphere a = AtmosphereMath.Atmosphere.of(block);
        float[] tint = {1.6f, 0.7f, 0.2f, 0};
        assertArrayEquals(new float[]{1, 1, 1}, WaterMath.vanillaStarTint(a, new float[]{0, 1, 0}, tint), 1e-6f, "the moon (straight up at midnight)");
        float s = (float) Math.sqrt(0.5);
        assertArrayEquals(new float[]{1.6f, 0.7f, 0.2f}, WaterMath.vanillaStarTint(a, new float[]{s, s, 0}, tint), 1e-6f, "a star 45 degrees away");
        float[] white = {1, 1, 1, 0};
        assertArrayEquals(new float[]{1, 1, 1}, WaterMath.vanillaStarTint(a, new float[]{s, s, 0}, white), 0, "the default: exactly 1");
    }

    @Test
    void everyPaletteSetsAStarColourAndAnOlderPaletteFileTakesIt(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        assertEquals(0xFFFFFF, ColourPresets.value(ShaderColours.Colour.STARS, ColourPresets.DEFAULT));
        for (int level = ColourPresets.REALISTIC; level < ColourPresets.CUSTOM; level++) {
            int v = ColourPresets.value(ShaderColours.Colour.STARS, level);
            assertNotEquals(0xFFFFFF, v, "palette " + level);
            VisualsConfig c = new VisualsConfig();
            ColourPresets.apply(c, level);
            assertEquals(v, c.colourStars);
            assertEquals(level, ColourPresets.detect(c));
            c.colourStars = 0x123456;
            assertEquals(ColourPresets.CUSTOM, ColourPresets.detect(c), "a star colour picked by hand shows Custom");
        }
        //Horror's stars are dim: its colour's luminance well under white's
        int h = ColourPresets.value(ShaderColours.Colour.STARS, ColourPresets.HORROR);
        float[] ht = ShaderColours.tint(h, 0xFFFFFF);
        assertTrue(0.2126f * ht[0] + 0.7152f * ht[1] + 0.0722f * ht[2] < 0.4f);
        //a revision 11 file on Horror (no star colour yet: white) takes Horror's and stays on it; a Custom one keeps white stars
        for (int level : new int[]{ColourPresets.HORROR, ColourPresets.ANIME, ColourPresets.DEFAULT}) {
            VisualsConfig before = new VisualsConfig();
            ColourPresets.apply(before, level);
            Path f = dir.resolve("p" + level + ".json");
            before.write(f);
            Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 11")
                    .replaceAll("\\s*\"colourStars\": \\d+,", ""));
            assertFalse(Files.readString(f).contains("colourStars"));
            VisualsConfig read = VisualsConfig.read(f);
            assertEquals(level, read.colourPreset, "palette " + level);
            assertEquals(ColourPresets.value(ShaderColours.Colour.STARS, level), read.colourStars, "palette " + level);
        }
        VisualsConfig custom = new VisualsConfig();
        ColourPresets.apply(custom, ColourPresets.HORROR);
        custom.colourMoon = 0x00FF00;
        custom.colourPreset = ColourPresets.CUSTOM;
        Path f = dir.resolve("custom.json");
        custom.write(f);
        Files.writeString(f, Files.readString(f).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": 11")
                .replaceAll("\\s*\"colourStars\": \\d+,", ""));
        assertEquals(0xFFFFFF, VisualsConfig.read(f).colourStars, "not on a palette: the white stars it had");
    }

    @Test
    void theMixinChangesVanillasStarsOnlyThroughStarSky() throws IOException {
        String mixin = Files.readString(Path.of("src/main/java/net/vulkanmod/mixin/visuals/LevelRendererStarsMixin.java"));
        assertTrue(mixin.contains("@Constant(intValue = StarMath.VANILLA_STARS)"));
        assertTrue(mixin.contains("@Constant(floatValue = StarMath.VANILLA_SIZE_BASE)"));
        assertTrue(mixin.contains("@Constant(floatValue = StarMath.VANILLA_SIZE_SPREAD)"));
        assertTrue(mixin.contains("getStarBrightness(F)F"));
        String json = Files.readString(Path.of("src/main/resources/vulkanmod.mixins.json"));
        assertTrue(json.contains("\"visuals.LevelRendererStarsMixin\""));
        assertTrue(json.contains("\"visuals.LevelRendererStarsInvoker\""));
        String tick = Files.readString(Path.of("src/main/java/net/vulkanmod/mixin/render/MinecraftMixin.java"));
        assertTrue(tick.contains("StarSky.update()"), "rebuilt at the tick's start, outside the recording");
    }
}
