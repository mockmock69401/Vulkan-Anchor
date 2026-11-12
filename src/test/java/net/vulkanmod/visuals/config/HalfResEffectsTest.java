package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.SceneCompositeMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 14 follow-up: Half-resolution Weather & Sky (the shader weather, the far clouds and the aurora at half resolution). */
class HalfResEffectsTest {
    @Test
    void offByDefaultOnBelowHigh() {
        assertFalse(new VisualsConfig().halfResEffects);
        assertEquals(VisualsPresets.HIGH, VisualsPresets.detect(new VisualsConfig()), "a fresh file is still High");
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            VisualsPresets.apply(c, level);
            assertEquals(level <= VisualsPresets.MEDIUM, c.halfResEffects, "level " + level);
            assertEquals(level, VisualsPresets.detect(c));
        }
        assertEquals(VisualsOptionLayout.Kind.SWITCH, VisualsOptionLayout.item("half_res_effects").kind());
    }

    @Test
    void aLevelSavedBeforeItKeepsItsLevel(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("v.json");
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            VisualsPresets.apply(c, level);
            c.halfResEffects = false;//the field did not exist
            c.revision = 16;
            c.write(f);
            String text = Files.readString(f).replace("\"halfResEffects\": false,", "").replace(",\n  \"halfResEffects\": false", "");
            Files.writeString(f, text);
            VisualsConfig back = VisualsConfig.read(f);
            assertEquals(level, back.preset, "level " + level + " stays");
            assertEquals(level <= VisualsPresets.MEDIUM, back.halfResEffects, "level " + level);
            assertEquals(VisualsConfig.currentRevision(), back.revision);
        }
        //Custom keeps it off (as before)
        VisualsConfig custom = new VisualsConfig();
        custom.exposure = 130;
        custom.preset = VisualsPresets.CUSTOM;
        custom.revision = 16;
        custom.write(f);
        assertFalse(VisualsConfig.read(f).halfResEffects);
    }

    @Test
    void theUpsampleWeighsTexelsByTheirDistance() {
        assertEquals(17, VisualsConfig.currentRevision());
        assertEquals(0.25f, SceneCompositeMath.halfSkyWeight(0.25f, 100, 100), 1e-6f, "same distance: bilinear");
        assertTrue(SceneCompositeMath.halfSkyWeight(0.25f, 1e6f, 40) < 0.25f / 1000, "the sky behind a hill's edge barely counts on the hill");
        assertEquals(0.25f, SceneCompositeMath.halfSkyWeight(0.25f, 1e6f, 1e6f), 1e-6f, "the sky: both held as HALF_SKY_FAR");
        assertEquals(12, SceneCompositeMath.HALF_SKY_AURORA_STEPS);
    }

    @Test
    void theRendererRunsTheHalfSizePassesOnlyWithTheSetting() throws Exception {
        String post = Files.readString(Path.of("src/main/java/net/vulkanmod/visuals/render/VkPostProcess.java"));
        assertTrue(post.contains("this.halfOn = VisualsConfig.get().halfResEffects;"));
        assertTrue(post.contains("this.copyUniforms.putVec4(COPY_HALF_SLOT, this.skyHalfOn ? 1 : 0, this.skyHalfOn && HALF_AURORA ? 1 : 0, 0, 0);"));
        assertTrue(post.contains("return VisualsConfig.get().halfResEffects && this.weatherUp != null;"));
        String copy = Files.readString(Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/scene_copy.comp"));
        assertTrue(copy.contains("layout(location = 61) uniform vec4 halfParams;"));
        String renderer = Files.readString(Path.of("src/main/java/net/vulkanmod/visuals/render/VisualsRenderer.java"));
        assertTrue(renderer.contains("this.post.halfWeather() ? (height + 1) / 2 : height"), "the streaks' width for the half-size pixel");
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            String json = Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/" + lang + ".json"));
            assertTrue(json.contains("\"vulkanmod.visuals.half_res_effects\"") && json.contains("\"vulkanmod.visuals.half_res_effects.tooltip\""), lang);
        }
    }
}
