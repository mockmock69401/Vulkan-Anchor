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
    void theNearMapsTexelsAndThePixelsFootprintAreFiltered() {
        float close = 2 * ShadowMath.CLOSE_RADIUS / ShadowMath.CLOSE_HEIGHT;
        float near = 2 * ShadowMath.NEAR_RADIUS / ShadowMath.NEAR_HEIGHT;
        assertEquals(1 / 16f, close, 1e-6);
        assertTrue(close < SoftShadowMath.AA_TEXEL_MIN && near > SoftShadowMath.AA_TEXEL_MIN);
        //blocks per pixel per block of distance at 1440 rows, fov 70
        float perBlock = 2 / (1.4281480f * 1440);
        //5 blocks out on the floor, the eye 1.6 above it: under the close map's texel either way, the sharp lookup as before
        float[] f5 = SoftShadowMath.footprint(5 * perBlock, 1.6f / 5);
        assertTrue(SoftShadowMath.least(close, f5)[0] < close);
        //15 blocks out the flecks fall between the pixels along the view, not across it
        float[] f15 = SoftShadowMath.footprint(15 * perBlock, 1.6f / 15);
        assertTrue(f15[0] > close && f15[1] < close, "15 blocks " + f15[0] + " " + f15[1]);
        //the near map's texels past the close cascade; the along half width capped
        assertEquals(1.5f * near, SoftShadowMath.least(near, SoftShadowMath.footprint(40 * perBlock, 1))[1], 1e-6);
        assertEquals(SoftShadowMath.AA_ALONG_MAX, SoftShadowMath.footprint(150 * perBlock, 0.01f)[0], 1e-6);
        assertEquals(SoftShadowMath.footprint(1, 0.01f)[0], SoftShadowMath.footprint(1, SoftShadowMath.AA_COS_MIN)[0], 1e-6);
        //the taps hold still at the least width and turn fully from twice it
        assertEquals(0, SoftShadowMath.turnShare(0.1f, 0.2f));
        assertEquals(0, SoftShadowMath.turnShare(0.2f, 0.2f));
        assertEquals(0.5f, SoftShadowMath.turnShare(0.3f, 0.2f), 1e-6);
        assertEquals(1, SoftShadowMath.turnShare(0.5f, 0.2f));
        assertEquals(1, SoftShadowMath.turnShare(0.1f, 0));
    }

    @Test
    void theShaderMirrorsTheConstants() throws IOException {
        String s = Files.readString(SHADER);
        assertTrue(s.contains("layout(location = " + SoftShadowMath.SLOT + ") uniform vec4 softParams;"));
        assertTrue(s.contains("const int SOFT_SEARCH_TAPS = " + SoftShadowMath.SEARCH_TAPS + ";"));
        assertTrue(s.contains("const int SOFT_FILTER_TAPS = " + SoftShadowMath.FILTER_TAPS + ";"));
        //off: the soft lookup is never reached
        assertTrue(s.contains("if (soft && softParams.x > 0.0) {"));
        assertTrue(s.contains("const float SOFT_AA_TEXELS = " + SoftShadowMath.AA_TEXELS + ";"));
        assertTrue(s.contains("const float SOFT_AA_TEXEL_MIN = " + SoftShadowMath.AA_TEXEL_MIN + ";"));
        assertTrue(s.contains("const float SOFT_AA_PIXELS = " + SoftShadowMath.AA_PIXELS + ";"));
        assertTrue(s.contains("const float SOFT_AA_COS_MIN = " + SoftShadowMath.AA_COS_MIN + ";"));
        assertTrue(s.contains("const float SOFT_AA_ALONG_MAX = " + SoftShadowMath.AA_ALONG_MAX + ";"));
        assertTrue(s.contains("return max(softFootprint, vec2(texel > SOFT_AA_TEXEL_MIN ? SOFT_AA_TEXELS*texel : 0.0));"));
        assertTrue(s.contains("softFootprint = vec2(max(min(across/max(abs(dot(n, v)), SOFT_AA_COS_MIN), SOFT_AA_ALONG_MAX), across), across);"));
        assertTrue(s.contains("float turn = softTurn*clamp((width - least.y)/max(least.y, 1e-6), 0.0, 1.0);"));
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
