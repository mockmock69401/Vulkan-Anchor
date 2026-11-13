package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.config.VisualsPresets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Session 20: Soft Shadows (SoftShadowMath, lighting.comp's softLookup). */
class SoftShadowMathTest {
    private static final Path SHADER = Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/lighting.comp");

    @Test
    void offIsAllZeroAndTheSharpPath() {
        assertArrayEquals(new float[]{0, 0, 0}, SoftShadowMath.params(0, 123));
        assertArrayEquals(new float[]{0, 0, 0}, SoftShadowMath.params(-5, 123));
        assertEquals(0, SoftShadowMath.halfWidth(10, SoftShadowMath.params(0, 1)));
    }

    @Test
    void theDefaultSpreadsWithHeightUpToTheWidest() {
        float[] p = SoftShadowMath.params(100, 0);
        assertEquals(SoftShadowMath.SPREAD, p[0], 1e-6);
        assertEquals(SoftShadowMath.MAX_WIDTH, p[1], 1e-6);
        //a post's foot: no spread; a canopy 10 blocks up: 0.15 blocks; far above: the widest
        assertEquals(0, SoftShadowMath.halfWidth(0, p), 1e-6);
        assertEquals(0.15f, SoftShadowMath.halfWidth(10, p), 1e-6);
        assertEquals(SoftShadowMath.MAX_WIDTH, SoftShadowMath.halfWidth(100, p), 1e-6);
        //200% doubles both, more is capped
        float[] d = SoftShadowMath.params(200, 0);
        assertEquals(2 * SoftShadowMath.SPREAD, d[0], 1e-6);
        assertArrayEquals(d, SoftShadowMath.params(500, 0));
    }

    @Test
    void theTurnRepeats() {
        assertEquals(0, SoftShadowMath.params(100, SoftShadowMath.FRAMES)[2]);
        assertEquals(5, SoftShadowMath.params(100, SoftShadowMath.FRAMES * 3L + 5)[2]);
        assertEquals(SoftShadowMath.FRAMES - 1, SoftShadowMath.params(100, -1)[2]);
    }

    @Test
    void theShaderMirrorsTheConstants() throws IOException {
        String s = Files.readString(SHADER);
        assertTrue(s.contains("layout(location = " + SoftShadowMath.SLOT + ") uniform vec4 softParams;"));
        assertTrue(s.contains("const int SOFT_SEARCH_TAPS = " + SoftShadowMath.SEARCH_TAPS + ";"));
        assertTrue(s.contains("const int SOFT_FILTER_TAPS = " + SoftShadowMath.FILTER_TAPS + ";"));
        //off: the soft lookup is never reached
        assertTrue(s.contains("if (soft && softParams.x > 0.0) {"));
        //the far cascade stays sharp, the close and near ones and the entity map are soft
        assertTrue(s.contains("wideBlocks, false, edge);"));
        assertEquals(2, s.split("wideBlocks, true, edge\\);", -1).length - 1);
        assertTrue(s.contains("entityParams.z, fade, wideBlocks, true);"));
    }

    @Test
    void theSettingAndThePresets() {
        assertEquals(100, new VisualsConfig().softShadows);
        VisualsOptionLayout.Item item = VisualsOptionLayout.item("soft_shadows");
        assertEquals(VisualsOptionLayout.Kind.PERCENT, item.kind());
        assertEquals(SoftShadowMath.STRENGTH_MAX, item.max());
        assertEquals("sun_shadows", item.requires());
        assertEquals(0, VisualsPresets.value("soft_shadows", VisualsPresets.LOW));
        assertEquals(100, VisualsPresets.value("soft_shadows", VisualsPresets.HIGH));
    }
}
