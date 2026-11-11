package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.config.VisualsPresets;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-10 (session 8, the colour pickers): ShaderColours' tints, the config and the option page, and that the defaults leave every
 * uniform exactly as it was (the picture before the pickers) while a picked colour moves the colour it is for.
 */
class ShaderColoursTest {
    private static final ShaderColours DEFAULTS = ShaderColours.DEFAULT;
    //vanilla's time of day (0 noon, 0.5 midnight): the sun 3 degrees up in the west (dusk) and in the east (dawn)
    private static final float NOON = 0, MIDNIGHT = 0.5f, DUSK = (float) (Math.acos(0.05) / (2 * Math.PI)), DAWN = 1 - DUSK;

    private static AtmosphereFrame frame(float timeOfDay, float rain) {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000);
        return new AtmosphereFrame(1, proj.invert(new Matrix4f()), proj.invert(new Matrix4f()), new Matrix4f(), new Matrix4f(proj), 120, 63,
                0.7f, 0.8f, 1f, false, false, timeOfDay, rain, 0, null, new float[]{0.47f, 0.65f, 1}, 256, null,
                new CloudFrame(192, 100, 200, new float[]{1, 1, 1}));
    }

    private static PostProcessSettings settings(VisualsConfig c) {
        return PostProcessSettings.from(c);
    }

    private static VisualsConfig with(Colour colour, int rgb) {
        VisualsConfig c = new VisualsConfig();
        c.setColour(colour, rgb);
        return c;
    }

    @Test
    void everyDefaultIsATintOfExactlyOne() {
        for (Colour c : Colour.values()) {
            assertArrayEquals(new float[]{1, 1, 1}, DEFAULTS.tint(c), 0, c.name());
            for (int i = 0; i < 3; i++) assertTrue(((c.defaultRgb() >> (16 - 8 * i)) & 0xFF) >= 1, c + " has a channel of 0");
            //and the same through the division, as a config holding the default as a number gives it
            assertArrayEquals(new float[]{1, 1, 1}, ShaderColours.tint(c.defaultRgb(), c.defaultRgb()), 0);
        }
        assertTrue(DEFAULTS.allDefault());
        assertEquals("defaults", DEFAULTS.describe());
        assertSame(DEFAULTS, ShaderColours.of(new VisualsConfig().colours()), "the defaults reuse the instance");
    }

    @Test
    void aTintIsTheLinearRatioAndMovesTheHue() {
        //twice as bright in linear terms in red only
        int def = 0x808080;
        float lin = PostMath.displayToLinear(0x80 / 255f);
        int r = Math.round(PostMath.linearToDisplay(Math.min(1, lin * 2)) * 255);
        float[] t = ShaderColours.tint((r << 16) | 0x8080, def);
        assertEquals(2, t[0], 0.05f);
        assertEquals(1, t[1], 0);
        assertEquals(1, t[2], 0);
        assertArrayEquals(new float[]{0, 0, 0}, ShaderColours.tint(0, def), 0, "black darkens to nothing");
        //mix keeps exactly 1 between two defaults (a (1 - t) + b t need not)
        for (float k = 0; k <= 1; k += 0.037f) assertArrayEquals(new float[]{1, 1, 1}, ShaderColours.mix(new float[]{1, 1, 1}, new float[]{1, 1, 1}, k), 0);
    }

    @Test
    void theFixedColoursSwatchesAreTheShadersConstants() {
        //a fixed colour's swatch is its constant, scaled to its largest channel (within the 8-bit rounding)
        float[][] fixed = {{AuroraMath.AURORA_GREEN_R, AuroraMath.AURORA_GREEN_G, AuroraMath.AURORA_GREEN_B},
                {WaterMath.WATER_BODY_RED, WaterMath.WATER_BODY_GREEN, WaterMath.WATER_BODY_BLUE},
                {WaterMath.MOON_RED, WaterMath.MOON_GREEN, WaterMath.MOON_BLUE}};
        Colour[] colours = {Colour.AURORA_MAIN, Colour.WATER, Colour.MOON};
        for (int k = 0; k < fixed.length; k++) {
            float m = Math.max(fixed[k][0], Math.max(fixed[k][1], fixed[k][2]));
            for (int i = 0; i < 3; i++) {
                float swatch = PostMath.displayToLinear(((colours[k].defaultRgb() >> (16 - 8 * i)) & 0xFF) / 255f);
                assertEquals(fixed[k][i] / m, swatch, 0.01f, colours[k] + " channel " + i);
            }
        }
        int g = Colour.AURORA_MAIN.defaultRgb();
        assertTrue(((g >> 8) & 0xFF) == 255 && (g & 0xFF) > ((g >> 16) & 0xFF), "green, toward teal");
    }

    @Test
    void theDefaultsChangeNoUniform() {
        //the default colours' atmosphere is the one before session 8 in its first 28 floats, and the tints are (1, 1, 1, 0)
        PostProcessSettings s = settings(new VisualsConfig());
        for (float time : new float[]{NOON, 0.1f, DUSK, 0.26f, MIDNIGHT, DAWN}) {
            for (float rain : new float[]{0, 1}) {
                float[] b = AtmosphereUniforms.block(s, frame(time, rain), true);
                assertEquals(AtmosphereUniforms.BLOCK_FLOATS, b.length);
                for (int v = 0; v < 3; v++) {
                    for (int i = 0; i < 3; i++) assertEquals(1, b[AtmosphereUniforms.TINTS + v * 4 + i], 0);
                    assertEquals(0, b[AtmosphereUniforms.TINTS + v * 4 + 3], 0);
                }
            }
        }
        float[] t = CelestialUniforms.tints(DEFAULTS, false);
        assertEquals(24, t.length);
        assertFalse(CelestialUniforms.off().tinted());
        for (int v = 0; v < 6; v++) assertArrayEquals(new float[]{1, 1, 1, 0}, java.util.Arrays.copyOfRange(t, v * 4, v * 4 + 4), 0);
    }

    @Test
    void theSkyColoursMoveTheSkyByTheSunsHeight() {
        //noon: the day sky's tint, not the sunset's; a sunset (sun in the west, low): the sunset's, not the sunrise's
        PostProcessSettings def = settings(new VisualsConfig());
        PostProcessSettings purple = settings(with(Colour.DAY_SKY, 0x9070F0));
        PostProcessSettings pink = settings(with(Colour.SUNSET, 0xFF60A0));
        PostProcessSettings morning = settings(with(Colour.SUNRISE, 0xFF60A0));
        float[] noon = AtmosphereUniforms.block(def, frame(NOON, 0), true), noonPurple = AtmosphereUniforms.block(purple, frame(NOON, 0), true);
        assertTrue(noonPurple[4] / noonPurple[5] > noon[4] / noon[5] * 1.2f, "a purple sky is redder over green at noon");
        assertArrayEquals(noon, AtmosphereUniforms.block(pink, frame(NOON, 0), true), 0, "the sunset colour does nothing at noon");
        float[] dusk = AtmosphereUniforms.block(def, frame(DUSK, 0), true), duskPink = AtmosphereUniforms.block(pink, frame(DUSK, 0), true);
        assertTrue(duskPink[6] / duskPink[5] > dusk[6] / dusk[5] * 1.2f, "a pink sunset is bluer over green");
        assertArrayEquals(dusk, AtmosphereUniforms.block(morning, frame(DUSK, 0), true), 0, "the sunrise colour does nothing at dusk");
        assertFalse(java.util.Arrays.equals(AtmosphereUniforms.block(def, frame(DAWN, 0), true), AtmosphereUniforms.block(morning, frame(DAWN, 0), true)),
                "the sunrise colour moves the dawn");
        //night: the night sky's tint on the moonlight and the dome
        float[] night = AtmosphereUniforms.block(def, frame(MIDNIGHT, 0), true);
        float[] red = AtmosphereUniforms.block(settings(with(Colour.NIGHT_SKY, 0x804050)), frame(MIDNIGHT, 0), true);
        assertTrue(red[16] / red[18] > night[16] / night[18] * 1.5f, "a red night sky");
    }

    @Test
    void theFogOvercastAndMoonTintsReachTheStructAndItsMirror() {
        PostProcessSettings s = settings(with(Colour.FOG, 0xFFC0C0));
        float[] b = AtmosphereUniforms.block(s, frame(NOON, 0), true);
        AtmosphereMath.Atmosphere a = AtmosphereMath.Atmosphere.of(b), d = AtmosphereMath.Atmosphere.of(AtmosphereUniforms.block(settings(new VisualsConfig()), frame(NOON, 0), true));
        assertTrue(a.fogTint()[0] > 1 && a.fogTint()[2] < 1);
        float[] dir = {1, 0, 0};
        float[] fog = AtmosphereMath.fogColour(a, dir), fogDef = AtmosphereMath.fogColour(d, dir);
        for (int i = 0; i < 3; i++) assertEquals(fogDef[i] * a.fogTint()[i], fog[i], 1e-6f);
        //the aerial haze: tinted near the camera, the sky's own far away (no seam at the horizon)
        float[] near = AtmosphereMath.hazeTint(a, 0, 16), far = AtmosphereMath.hazeTint(a, 16, 16);
        assertArrayEquals(new float[]{a.fogTint()[0], a.fogTint()[1], a.fogTint()[2]}, near, 1e-6f);
        assertArrayEquals(new float[]{1, 1, 1}, far, 0);
        assertArrayEquals(new float[]{1, 1, 1}, AtmosphereMath.hazeTint(d, 3, 16), 0, "exactly 1 at the default");
        //the moon disc
        AtmosphereMath.Atmosphere m = AtmosphereMath.Atmosphere.of(AtmosphereUniforms.block(settings(with(Colour.MOON, 0xFF8040)), frame(MIDNIGHT, 0), true));
        AtmosphereMath.Atmosphere md = AtmosphereMath.Atmosphere.of(AtmosphereUniforms.block(settings(new VisualsConfig()), frame(MIDNIGHT, 0), true));
        float[] moon = WaterMath.moonRadiance(m), moonDef = WaterMath.moonRadiance(md);
        assertTrue(moon[0] / moon[2] > moonDef[0] / moonDef[2] * 2, "an orange moon");
    }

    @Test
    void theOtherColoursReachTheirSlots() {
        //aurora: the Overworld's three, or the End's in all three
        VisualsConfig c = new VisualsConfig();
        c.setColour(Colour.AURORA_MAIN, 0xFF8000);
        c.setColour(Colour.AURORA_END, 0x40FF40);
        ShaderColours colours = ShaderColours.of(c.colours());
        float[] over = CelestialUniforms.tints(colours, false), end = CelestialUniforms.tints(colours, true);
        assertArrayEquals(colours.tint(Colour.AURORA_MAIN), java.util.Arrays.copyOfRange(over, 4, 7), 0);
        assertArrayEquals(new float[]{1, 1, 1}, java.util.Arrays.copyOfRange(over, 0, 3), 0);
        for (int v = 0; v < 3; v++) assertArrayEquals(colours.tint(Colour.AURORA_END), java.util.Arrays.copyOfRange(end, v * 4, v * 4 + 3), 0);
        float[] dir = {0, 0.6f, 0.8f}, params = {1, 100, 0, 0};
        float[] plain = AuroraMath.aurora(dir, params, 12, 0.5f);
        float[] orange = AuroraMath.aurora(dir, params, new float[]{1, 1, 1}, colours.tint(Colour.AURORA_MAIN), new float[]{1, 1, 1}, 12, 0.5f);
        if (plain[1] > 0) assertTrue(orange[0] / Math.max(orange[1], 1e-6f) > plain[0] / plain[1], "an orange body");
        assertArrayEquals(plain, AuroraMath.aurora(dir, params, new float[]{1, 1, 1}, new float[]{1, 1, 1}, new float[]{1, 1, 1}, 12, 0.5f), 0);
        //underwater: the light slot, exactly as before at the default
        AtmosphereFrame wet = frame(NOON, 0).withCameraFluid(AtmosphereFrame.CameraFluid.WATER);
        float[] l = UnderwaterUniforms.compute(settings(new VisualsConfig()), wet, 1, true, 3, 1, true).light();
        float[] g = UnderwaterUniforms.compute(settings(with(Colour.UNDERWATER, 0x30C060)), wet, 1, true, 3, 1, true).light();
        assertArrayEquals(l, UnderwaterUniforms.light(settings(new VisualsConfig()), wet, true), 0);
        assertTrue(g[1] / g[2] > l[1] / l[2] * 1.5f, "a green underwater");
    }

    /**
     * Session 8 follow-up: the sunlight's and shade light's swatches are ColourMath's colours at the sun 37 degrees up (height 0.6) and the
     * default warmth; their tints reach slots 22 and 23 (exactly as before at the defaults); the torch light colour's reaches the glow.
     */
    @Test
    void theLightColoursReachTheLightAndTheGlow() {
        float[] sun = ColourMath.sunLight(0.6f, 1), shade = ColourMath.skyShade(1, 0.6f);
        assertEquals(ShaderColours.display(sun[0], sun[1], sun[2]), Colour.SUNLIGHT.defaultRgb(), ShaderColours.hex(ShaderColours.display(sun[0], sun[1], sun[2])));
        assertEquals(ShaderColours.display(shade[0], shade[1], shade[2]), Colour.SHADE_LIGHT.defaultRgb());
        VisualsConfig c = new VisualsConfig();
        c.setColour(Colour.SUNLIGHT, 0xFF8040);
        c.setColour(Colour.SHADE_LIGHT, 0x80C0FF);
        ShaderColours picked = ShaderColours.of(c.colours());
        LightingUniforms.Values v = LightingUniforms.off().tinted(picked);
        assertArrayEquals(picked.tint(Colour.SUNLIGHT), v.sunLightTint(), 0);
        assertArrayEquals(picked.tint(Colour.SHADE_LIGHT), v.shadeTint(), 0);
        assertArrayEquals(new float[]{1, 1, 1}, LightingUniforms.off().tinted(DEFAULTS).sunLightTint(), 0);
        assertArrayEquals(new float[]{1, 1, 1}, LightingUniforms.off().tinted(DEFAULTS).shadeTint(), 0);
        //the torch light colour tints the glow (and so runs the composite's .TINTED build); the sunlight and shade need no build of their own
        ShaderColours blue = ShaderColours.of(with(Colour.BLOCK_LIGHT, 0x60A0FF).colours());
        assertArrayEquals(blue.tint(Colour.BLOCK_LIGHT), java.util.Arrays.copyOfRange(CelestialUniforms.tints(blue, false), 16, 19), 0);
        assertTrue(new CelestialUniforms.Values(new float[4], new float[4], CelestialUniforms.tints(blue, false)).tinted());
        assertFalse(new CelestialUniforms.Values(new float[4], new float[4], CelestialUniforms.tints(picked, false)).tinted());
    }

    @Test
    void theConfigSavesTheColoursAndAnOldFileKeepsTheDefaults(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old.json");
        Files.writeString(old, "{\"enabled\": true, \"preset\": 3, \"revision\": 6}");
        VisualsConfig read = VisualsConfig.read(old);
        assertArrayEquals(ShaderColours.defaults(), read.colours());
        assertEquals(VisualsConfig.currentRevision(), read.revision);
        assertEquals(VisualsPresets.HIGH, read.preset, "a revision 6 file keeps its preset");
        VisualsConfig c = new VisualsConfig();
        c.setColour(Colour.SUNSET, 0xFF3366);
        c.setColour(Colour.WATER, 0x00FF80);
        Path f = dir.resolve("new.json");
        c.write(f);
        VisualsConfig back = VisualsConfig.read(f);
        assertEquals(0xFF3366, back.colour(Colour.SUNSET));
        assertEquals(0x00FF80, back.colour(Colour.WATER));
        assertEquals(c.preset, back.preset, "colours are not preset settings");
        //the presets never touch a colour
        for (int level = VisualsPresets.POTATO; level <= VisualsPresets.ULTRA; level++) {
            VisualsPresets.apply(back, level);
            assertEquals(0xFF3366, back.colour(Colour.SUNSET));
        }
        //each colour has its own field
        Set<Integer> seen = new HashSet<>();
        VisualsConfig k = new VisualsConfig();
        for (Colour col : Colour.values()) {
            k.setColour(col, col.ordinal() + 1);
            seen.add(k.colour(col));
        }
        assertEquals(ShaderColours.COUNT, seen.size());
    }

    @Test
    void everyColourIsAnItemOnTheColoursPageWithBothLabels() throws Exception {
        String en = Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/en_us.json"));
        String ko = Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/ko_kr.json"));
        Set<String> keys = new HashSet<>();
        for (VisualsOptionLayout.Page page : VisualsOptionLayout.PAGES) {
            for (VisualsOptionLayout.Block block : page.blocks()) {
                for (VisualsOptionLayout.Item item : block.items()) {
                    if (item.kind() != VisualsOptionLayout.Kind.COLOUR) continue;
                    assertEquals("colours", page.key());
                    keys.add(item.key());
                    Colour c = Colour.byKey(item.key());
                    assertNotNull(c, item.key());
                    assertEquals(c.defaultRgb(), VisualsOptionLayout.defaultValue(item));
                    VisualsConfig cfg = new VisualsConfig();
                    VisualsOptionLayout.set(cfg, item, 0x123456);
                    assertEquals(0x123456, cfg.colour(c));
                    assertEquals(0x123456, VisualsOptionLayout.get(cfg, item));
                    for (String lang : new String[]{en, ko}) {
                        assertTrue(lang.contains("\"vulkanmod.visuals." + item.key() + "\""), item.key());
                        assertTrue(lang.contains("\"vulkanmod.visuals." + item.key() + ".tooltip\""), item.key());
                    }
                }
            }
        }
        assertEquals(ShaderColours.COUNT, keys.size(), "every colour once");
        for (String lang : new String[]{en, ko}) {
            for (String k : new String[]{"vulkanmod.visuals.page.colours", "vulkanmod.visuals.reset_colours", "vulkanmod.options.colour.before",
                    "vulkanmod.options.colour.now", "vulkanmod.options.colour.default"}) assertTrue(lang.contains("\"" + k + "\""), k);
        }
        assertFalse(VisualsPresets.VALUES.keySet().stream().anyMatch(k -> Colour.byKey(k) != null), "no colour is a preset setting");
    }
}
