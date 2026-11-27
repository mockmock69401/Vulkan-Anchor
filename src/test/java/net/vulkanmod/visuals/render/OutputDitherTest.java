package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-26: the Output Dither setting (off, fine, strong) and the passes after TAA rounding into 8 bits with it (dither.glsl), so TAA's
 * smooth colour does not band.
 */
class OutputDitherTest {
    private static final String NOISE = "fract(52.9829189*fract(dot(pixel, vec2(0.06711056, 0.00583715))))";

    @Test
    void theSettingHasThreeChoicesAndKeepsTheFineDitherByDefault() {
        assertEquals(PostMath.DITHER_FINE, new VisualsConfig().outputDither);
        assertEquals(VisualsOptionLayout.Kind.CHOICE, VisualsOptionLayout.item("output_dither").kind());
        assertEquals(PostMath.DITHER_STRONG, VisualsOptionLayout.item("output_dither").max());
        assertEquals(0f, PostMath.ditherMode(0));
        assertEquals(1f, PostMath.ditherMode(1));
        assertEquals(2f, PostMath.ditherMode(2));
        assertEquals(1f, PostMath.ditherMode(7));
    }

    @Test
    void theShadersMirrorTheAmountsAndTheNoise() {
        String d = ShaderFiles.read("post/dither.glsl");
        assertTrue(d.contains("const float OUTPUT_DITHER = 0.0039137257;"));
        assertEquals(0.0039137257f, PostMath.DITHER, 0, "the same amount as postfinal.glsl's DITHER");
        assertTrue(d.contains("const float OUTPUT_DITHER_STRONG = 0.003921569;"));
        assertEquals(0.003921569f, PostMath.DITHER_STRONG_STEP, 1e-9);
        assertTrue(d.contains(NOISE), "the same noise as postmath.glsl's");
        assertTrue(PostMath.IGN_SCALE == 52.9829189f && PostMath.IGN_X == 0.06711056f && PostMath.IGN_Y == 0.00583715f);
        //Fine: just under one step peak to peak, so a value on the 8-bit grid rounds back to itself
        assertTrue(PostMath.DITHER * 255 < 1);
        assertTrue(d.contains("return d + (n + outputDitherNoise(at.yx) - 1.0)*OUTPUT_DITHER_STRONG;"));
        String fin = ShaderFiles.read("post/postfinal.glsl");
        assertTrue(fin.contains("layout(location = " + PostUniforms.DITHER + ") uniform vec4 ditherParams;"));
        assertTrue(fin.contains("d = ditherOutput(d, fragCoord, ditherParams.x);"));
        //SMAA's blend is generated from third_party/smaa/smaa-blend.comp (SmaaShadersTest), which inlines dither.glsl's functions
        String smaa = ShaderFiles.read("post/smaa_blend.comp");
        assertTrue(smaa.contains("layout(location = " + VkSmaa.BLEND_DITHER + ")uniform vec4 smaaDither;"));
        assertTrue(smaa.contains("const float OUTPUT_DITHER = 0.0039137257;") && smaa.contains("const float OUTPUT_DITHER_STRONG = 0.003921569;"));
        assertTrue(smaa.contains("fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))))"));
        assertTrue(smaa.contains("ditherOutput(blended . rgb, vec2(coord), smaaDither . x)"));
        String blit = ShaderFiles.read("post/post_blit.frag");
        assertTrue(blit.contains("#import <vulkanmod:visuals/post/dither.glsl>"));
        assertTrue(blit.contains("ditherOutput(veil > 0.0 ? lensVeil(c, veil) : c, gl_FragCoord.xy, sharpen.z)"));
    }

    @Test
    void theStrongDithersGrainIsTheSameAtEveryShadeOfAGradient() {
        //A gradient over two 8-bit steps, each shade rounded at 4096 pixels: the fine dither's noise vanishes where a shade sits in a
        // step's middle (a flat band there, the contour that stays visible), the strong one's is the same at every shade (Wannamaker et al.)
        double minFine = 1e9, maxFine = 0, minStrong = 1e9, maxStrong = 0;
        for (int s = 0; s <= 40; s++) {
            double v = (100 + 2.0 * s / 40) / 255;
            double f1 = 0, f2 = 0, s1 = 0, s2 = 0;
            int n = 0;
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++, n++) {
                    float a = ign(x, y), b = ign(y, x);
                    double fine = Math.round((v + (a - 0.5) * PostMath.DITHER) * 255) - v * 255;
                    double strong = Math.round((v + (a + b - 1.0) * PostMath.DITHER_STRONG_STEP) * 255) - v * 255;
                    f1 += fine;
                    f2 += fine * fine;
                    s1 += strong;
                    s2 += strong * strong;
                }
            }
            double varFine = f2 / n - (f1 / n) * (f1 / n), varStrong = s2 / n - (s1 / n) * (s1 / n);
            minFine = Math.min(minFine, varFine);
            maxFine = Math.max(maxFine, varFine);
            minStrong = Math.min(minStrong, varStrong);
            maxStrong = Math.max(maxStrong, varStrong);
        }
        assertTrue(minFine < 0.02, "the fine dither leaves a step's middle flat: " + minFine);
        assertTrue(minStrong > 0.23 && maxStrong < 0.27, "the strong dither's grain is even: " + minStrong + ".." + maxStrong);
    }

    private static float ign(float x, float y) {
        double a = x * PostMath.IGN_X + y * PostMath.IGN_Y;
        double b = PostMath.IGN_SCALE * (a - Math.floor(a));
        return (float) (b - Math.floor(b));
    }
}
