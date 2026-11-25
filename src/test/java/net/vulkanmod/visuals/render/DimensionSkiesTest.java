package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-25 (NightfallCraft's custom dimensions, "색상프리셋 관계없이 통일해서 적용해줘"): DimensionSkies. */
class DimensionSkiesTest {
    @Test
    void theUsersDimensionsHaveTheirLooks() {
        assertSame(DimensionSkies.PURPLE, DimensionSkies.look("epicfight_arachne:arachne_nest"));
        assertSame(DimensionSkies.YELLOW, DimensionSkies.look("epicfight_leonidas:rockwind_ruins"));
        assertSame(DimensionSkies.DARK_RED, DimensionSkies.look("mimic:the_lethean_sea"));
        assertSame(DimensionSkies.BLACK, DimensionSkies.look("wraithon:sanctum_of_the_wraithon"));
        assertSame(DimensionSkies.BLACK, DimensionSkies.look("pbf1:sanctum_of_the_battle1"));
        assertNull(DimensionSkies.look("minecraft:overworld"));
        assertNull(DimensionSkies.look(null));
        //"공포 프리셋 베이스로", "공포 프리셋을 재활용하는 걸로": every look starts from Horror
        for (DimensionSkies.Look l : DimensionSkies.LOOKS.values()) assertEquals(ColourPresets.HORROR, l.palette());
    }

    /** Whatever palette the player chose, the dimension shows the same; the saved settings are not touched. */
    @Test
    void theSameWhateverThePalette() {
        for (int level : new int[]{ColourPresets.DEFAULT, ColourPresets.ANIME, ColourPresets.HORROR}) {
            VisualsConfig saved = new VisualsConfig();
            ColourPresets.apply(saved, level);
            int[] before = saved.colours();
            VisualsConfig shown = DimensionSkies.apply(saved, DimensionSkies.PURPLE);
            assertNotSame(saved, shown);
            assertArrayEquals(before, saved.colours());
            assertEquals(level, saved.colourPreset);
            assertEquals(ColourPresets.HORROR, shown.colourPreset);
            assertEquals(0x8E6AA8, shown.colour(Colour.DAY_SKY));
            assertEquals(ColourPresets.value(Colour.NIGHT_SKY, ColourPresets.HORROR), shown.colour(Colour.NIGHT_SKY));
        }
        VisualsConfig saved = new VisualsConfig();
        assertSame(saved, DimensionSkies.apply(saved, null));
    }

    /** The sky colours grade as the user named them: purple (blue over green), dark red (red well over the rest), near black. */
    @Test
    void theColoursAreTheOnesNamed() {
        int purple = DimensionSkies.PURPLE.colours().get(Colour.DAY_SKY);
        assertTrue(((purple >> 16) & 0xFF) > ((purple >> 8) & 0xFF) && (purple & 0xFF) > ((purple >> 8) & 0xFF) + 40);
        int red = DimensionSkies.DARK_RED.colours().get(Colour.DAY_SKY);
        assertTrue(((red >> 16) & 0xFF) > 2 * ((red >> 8) & 0xFF) && ((red >> 16) & 0xFF) < 0xA0);
        int black = DimensionSkies.BLACK.colours().get(Colour.DAY_SKY);
        assertTrue(((black >> 16) & 0xFF) < 0x40 && ((black >> 16) & 0xFF) > ((black >> 8) & 0xFF));
        assertTrue(DimensionSkies.YELLOW.colours().isEmpty());
    }

    /** "엔드 하늘로", "오버월드 밤 하늘로": the overworld's sky is drawn where there is sky light and not the End's effects, or where listed. */
    @Test
    void whereTheOverworldsSkyIsDrawn() {
        assertTrue(DimensionSkies.skyLit("minecraft:overworld", true, false));
        assertFalse(DimensionSkies.skyLit("minecraft:the_nether", false, false));
        assertFalse(DimensionSkies.skyLit("minecraft:the_end", false, true));
        assertFalse(DimensionSkies.skyLit("cataclysm_dimension:cataclysm_bastion_lost", true, true));
        assertTrue(DimensionSkies.skyLit("cataclysm_dimension:cataclysm_eternal_frosthold", false, false));
        assertTrue(DimensionSkies.skyLit("epicfight_arachne:arachne_nest", true, false));
    }

    @Test
    void aCopyHasEverySetting() throws IllegalAccessException {
        VisualsConfig c = new VisualsConfig();
        c.bloomStrength = 77;
        c.colourDaySky = 0x123456;
        c.enabled = !c.enabled;
        VisualsConfig copy = c.copy();
        for (Field f : VisualsConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            assertEquals(f.get(c), f.get(copy), f.getName());
        }
    }
}
