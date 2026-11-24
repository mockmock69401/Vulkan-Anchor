package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CelestialUniforms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-23 (the user: "높음 이상인 경우 오로라 밤과 엔드로, 해와 달은 둥글게"): from High up the presets turn the auroras on at night and in
 * the End and make the sun and moon round; below High vanilla's squares.
 */
class RoundSkyPresetTest {
    @Test
    void highAndUltraHaveTheRoundSkyAndTheNightAuroras() {
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            VisualsPresets.apply(c, level);
            boolean high = level >= VisualsPresets.HIGH;
            assertEquals(high ? CelestialUniforms.SHAPE_ROUND : CelestialUniforms.SHAPE_SQUARE, c.celestialShape, "level " + level);
            if (high) assertEquals(CelestialUniforms.AURORA_NIGHT_AND_END, c.aurora, "level " + level);
        }
        VisualsConfig fresh = new VisualsConfig();
        assertEquals(CelestialUniforms.SHAPE_ROUND, fresh.celestialShape, "a fresh config is High");
        assertEquals(VisualsPresets.HIGH, VisualsPresets.detect(fresh));
    }

    /** A level saved before the presets set the shape takes the level's and stays on it; a Custom file keeps its shape. */
    @Test
    void anOlderFileOnALevelTakesItsShape(@TempDir Path dir) throws Exception {
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            VisualsPresets.apply(c, level);
            c.celestialShape = level >= VisualsPresets.HIGH ? CelestialUniforms.SHAPE_SQUARE : CelestialUniforms.SHAPE_ROUND;
            Path f = dir.resolve("l" + level + ".json");
            c.write(f);
            OldFile.age(f, 26);
            VisualsConfig read = VisualsConfig.read(f);
            assertEquals(VisualsPresets.value("celestial_shape", level), read.celestialShape, "level " + level);
            assertEquals(level, read.preset, "level " + level);
        }
        VisualsConfig custom = new VisualsConfig();
        VisualsPresets.apply(custom, VisualsPresets.CUSTOM);
        custom.celestialShape = CelestialUniforms.SHAPE_SQUARE;
        Path f = dir.resolve("custom.json");
        custom.write(f);
        OldFile.age(f, 26);
        assertEquals(CelestialUniforms.SHAPE_SQUARE, VisualsConfig.read(f).celestialShape);
    }
}
