package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.particle.VanillaParticles;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import net.vulkanmod.visuals.render.StarMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-11 (shader roadmap session 13): the particle groups' and star type's settings, their colours and older files. */
class ParticleLookConfigTest {
    @Test
    void everyGroupHasItsEffectAndColour() {
        for (VanillaParticles.Group g : VanillaParticles.Group.values()) {
            ParticleEffect e = g.effect();
            assertEquals("particle_" + g.key(), e.key());
            assertEquals(1, e.typeMax());
            assertNull(e.requires());
            assertEquals("colour_particle_" + g.key(), e.colour().key());
            assertNotNull(VisualsOptionLayout.item(e.itemKey(ParticleEffect.Part.TYPE)));
            //The colour's picker is on the colours page and its field is found by its key
            assertNotNull(VisualsOptionLayout.item(e.colour().key()));
            assertEquals("colourParticle" + Character.toUpperCase(g.key().charAt(0)) + g.key().substring(1), VisualsConfig.colourField(e.colour()));
        }
        assertEquals("colourStars", VisualsConfig.colourField(Colour.STARS));
    }

    @Test
    void defaultsAreVanilla() {
        VisualsConfig c = new VisualsConfig();
        for (VanillaParticles.Group g : VanillaParticles.Group.values()) {
            assertEquals(0, g.effect().get(c, ParticleEffect.Part.TYPE));
            assertEquals(100, g.effect().get(c, ParticleEffect.Part.BRIGHTNESS));
            assertEquals(g.effect().colour().defaultRgb(), c.colour(g.effect().colour()));
        }
        assertEquals(StarMath.TYPE_VANILLA, c.starType);
        assertEquals(StarMath.TYPE_MAX, VisualsOptionLayout.item("star_type").max());
    }

    @Test
    void anOlderFileOnAPaletteTakesItsParticleColours(@TempDir Path dir) throws Exception {
        //A revision 14 file on Horror: every colour Horror's but the particle colours, which it did not have
        VisualsConfig horror = new VisualsConfig();
        ColourPresets.apply(horror, ColourPresets.HORROR);
        StringBuilder json = new StringBuilder("{\"colourPreset\": " + ColourPresets.HORROR + ", \"revision\": 14");
        for (Colour colour : Colour.values()) {
            if (colour.key().startsWith("colour_particle_")) continue;
            json.append(", \"").append(VisualsConfig.colourField(colour)).append("\": ").append(horror.colour(colour));
        }
        for (String key : ColourPresets.LOOK_KEYS) {
            json.append(", \"").append(VisualsConfig.class.getField(fieldOf(key)).getName()).append("\": ").append(ColourPresets.look(key, ColourPresets.HORROR));
        }
        json.append('}');
        Path file = dir.resolve("old.json");
        Files.writeString(file, json.toString());
        VisualsConfig c = VisualsConfig.read(file);
        assertEquals(ColourPresets.HORROR, c.colourPreset, "stays on the palette");
        for (VanillaParticles.Group g : VanillaParticles.Group.values()) {
            assertEquals(ColourPresets.value(g.effect().colour(), ColourPresets.HORROR), c.colour(g.effect().colour()));
        }
        //A file off any palette keeps the default particle colours (the picture it had)
        Files.writeString(file, "{\"colourPreset\": " + ColourPresets.CUSTOM + ", \"colourSunset\": 1193046, \"revision\": 14}");
        VisualsConfig custom = VisualsConfig.read(file);
        for (VanillaParticles.Group g : VanillaParticles.Group.values()) {
            assertEquals(g.effect().colour().defaultRgb(), custom.colour(g.effect().colour()));
        }
    }

    private static String fieldOf(String key) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : key.toCharArray()) {
            if (ch == '_') up = true;
            else {
                b.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return b.toString();
    }

    @Test
    void roundStars() {
        assertEquals(1f, StarMath.roundAlpha(0f), 0f);
        assertEquals(1f, StarMath.roundAlpha(StarMath.ROUND_CORE), 0f);
        assertEquals(0f, StarMath.roundAlpha(1f), 0f);
        float last = 1f;
        for (float d = StarMath.ROUND_CORE; d <= 1f; d += 0.01f) {
            float a = StarMath.roundAlpha(d);
            assertTrue(a <= last + 1e-6f);
            last = a;
        }
        //Vanilla's corners (-1, -1), (-1, 1), (1, 1), (1, -1) get u, v (0, 0), (0, 1), (1, 1), (1, 0)
        float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        for (int i = 0; i < 4; i++) {
            assertEquals(uv[i][0], StarMath.cornerU(i), 0f);
            assertEquals(uv[i][1], StarMath.cornerV(i), 0f);
        }
        //The round point adds about the light of the square it replaces: its alpha's mean over its quad x ROUND_SIZE^2 near 1
        double sum = 0;
        int n = 200;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
                sum += StarMath.roundAlpha((float) Math.sqrt(dx * dx + dy * dy));
            }
        }
        double mean = sum / (n * (double) n);
        assertEquals(1.0, mean * StarMath.ROUND_SIZE * StarMath.ROUND_SIZE, 0.25);
    }
}
