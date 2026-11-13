package net.vulkanmod.visuals.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Session 20: Soft Shadows (revision 19) and Wet Ground (revision 20), which the presets set, in files saved before they existed. */
class Session20PresetsTest {
    private static final String[][] SETTINGS = {{"soft_shadows", "softShadows", "18"}, {"wet_ground", "wetGround", "19"}};

    @Test
    void eachLevelSetsThemAndIsStillDetected() {
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            VisualsPresets.apply(c, level);
            assertEquals(level, VisualsPresets.detect(c));
        }
        assertEquals(VisualsPresets.HIGH, VisualsPresets.detect(new VisualsConfig()), "a fresh file is still High");
    }

    @Test
    void aLevelSavedBeforeThemKeepsItsLevel(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("v.json");
        for (String[] s : SETTINGS) {
            for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
                VisualsConfig c = new VisualsConfig();
                VisualsPresets.apply(c, level);
                c.revision = Integer.parseInt(s[2]);
                c.write(f);
                //the field did not exist
                Files.writeString(f, Files.readString(f).replaceAll("\\s*\"" + s[1] + "\": \\d+,?", ""));
                VisualsConfig back = VisualsConfig.read(f);
                assertEquals(level, back.preset, s[0] + " level " + level + " stays");
                assertEquals(VisualsPresets.value(s[0], level), VisualsOptionLayout.get(back, VisualsOptionLayout.item(s[0])), s[0] + " level " + level);
                assertEquals(VisualsConfig.currentRevision(), back.revision);
            }
        }
    }
}
