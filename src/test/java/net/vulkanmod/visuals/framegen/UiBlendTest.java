package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.Bytecode;
import net.vulkanmod.vulkan.shader.PipelineState.BlendState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.vulkanmod.vulkan.shader.PipelineState.BlendState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

class UiBlendTest {
    private static int state(int srcRgb, int dstRgb, int srcA, int dstA) {
        return state(srcRgb, dstRgb, srcA, dstA, VK_BLEND_OP_ADD);
    }

    private static int state(int srcRgb, int dstRgb, int srcA, int dstA, int colourOp) {
        return ENABLE_BIT
                | encode(srcRgb, SRC_RGB_OFFSET, FACTOR_MASK)
                | encode(dstRgb, DST_RGB_OFFSET, FACTOR_MASK)
                | encode(srcA, SRC_A_OFFSET, FACTOR_MASK)
                | encode(dstA, DST_A_OFFSET, FACTOR_MASK)
                | encode(colourOp, COLOR_FUN_OFFSET, OP_MASK)
                | encode(VK_BLEND_OP_ADD, ALPHA_FUN_OFFSET, OP_MASK);
    }

    private static void assertFactors(int s, int srcRgb, int dstRgb, int srcA, int dstA) {
        assertEquals(srcRgb, BlendState.getSrcRgbFactor(s));
        assertEquals(dstRgb, BlendState.getDstRgbFactor(s));
        assertEquals(srcA, BlendState.getSrcAlphaFactor(s));
        assertEquals(dstA, BlendState.getDstAlphaFactor(s));
        assertEquals(VK_BLEND_OP_ADD, BlendState.getAlphaBlendOp(s));
        assertEquals(VK_BLEND_OP_ADD, BlendState.getColorBlendOp(s));
        assertTrue(BlendState.enable(s));
    }

    @Test
    void standardAlphaAccumulatesCoverage() {
        int in = state(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO);
        assertTrue(UiBlend.handled(in));
        assertFactors(UiBlend.remap(in), VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
    }

    @Test
    void premultipliedLikewise() {
        int in = state(VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO);
        assertTrue(UiBlend.handled(in));
        assertFactors(UiBlend.remap(in), VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
    }

    @Test
    void additiveKeepsCoverage() {
        int a = state(VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE);
        int b = state(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO);
        assertTrue(UiBlend.handled(a));
        assertTrue(UiBlend.handled(b));
        assertFactors(UiBlend.remap(a), VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE);
        assertFactors(UiBlend.remap(b), VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE);
    }

    @Test
    void blendingOffIsUnchanged() {
        int off = state(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO) & ~ENABLE_BIT;
        assertTrue(UiBlend.handled(off));
        assertEquals(off, UiBlend.remap(off));
    }

    @Test
    void unhandledKeepsItsColourAddsNoCoverageAndIsReported() {
        //the crosshair's inversion and the vignette's multiply: the colour factors stay, the alpha becomes ZERO, ONE (ADD)
        int inversion = state(VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR, VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO);
        assertFalse(UiBlend.handled(inversion));
        assertFactors(UiBlend.remap(inversion), VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR, VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE);
        assertEquals("ONE_MINUS_DST_COLOR,ONE_MINUS_SRC_COLOR/ONE,ZERO", UiBlend.describe(inversion));
        int multiply = state(VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO);
        assertFalse(UiBlend.handled(multiply));
        assertFactors(UiBlend.remap(multiply), VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE);
    }

    /** Final review M1: the alpha-blend and additive cases hold for the colour op ADD only; any other op is unhandled (no coverage). */
    @Test
    void otherColourOpsAreUnhandled() {
        for (int op : new int[]{VK_BLEND_OP_REVERSE_SUBTRACT, VK_BLEND_OP_MIN, VK_BLEND_OP_MAX}) {
            int alpha = state(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, op);
            int additive = state(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, op);
            for (int in : new int[]{alpha, additive}) {
                assertFalse(UiBlend.handled(in), "op " + op);
                int out = UiBlend.remap(in);
                assertEquals(BlendState.getSrcRgbFactor(in), BlendState.getSrcRgbFactor(out));
                assertEquals(BlendState.getDstRgbFactor(in), BlendState.getDstRgbFactor(out));
                assertEquals(op, BlendState.getColorBlendOp(out), "the colour op stays");
                assertEquals(VK_BLEND_FACTOR_ZERO, BlendState.getSrcAlphaFactor(out), "no coverage, op " + op);
                assertEquals(VK_BLEND_FACTOR_ONE, BlendState.getDstAlphaFactor(out), "no coverage, op " + op);
                assertEquals(VK_BLEND_OP_ADD, BlendState.getAlphaBlendOp(out));
            }
        }
        int min = state(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_MIN);
        assertEquals("SRC_ALPHA,ONE/ONE,ZERO MIN", UiBlend.describe(min), "the log names the op");
    }

    // Tiny blend simulation: out = src * sf + dst * df per channel, op ADD.

    private static float factor(int f, float[] src, float[] dst, int ch) {
        return switch (f) {
            case VK_BLEND_FACTOR_ZERO -> 0f;
            case VK_BLEND_FACTOR_ONE -> 1f;
            case VK_BLEND_FACTOR_SRC_ALPHA -> src[3];
            case VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA -> 1f - src[3];
            case VK_BLEND_FACTOR_DST_ALPHA -> dst[3];
            case VK_BLEND_FACTOR_SRC_COLOR -> src[ch];
            default -> throw new IllegalArgumentException("factor " + f);
        };
    }

    private static float[] blend(int s, float[] src, float[] dst) {
        float[] out = new float[4];
        for (int c = 0; c < 3; c++) {
            out[c] = src[c] * factor(BlendState.getSrcRgbFactor(s), src, dst, c)
                    + dst[c] * factor(BlendState.getDstRgbFactor(s), src, dst, c);
        }
        out[3] = src[3] * factor(BlendState.getSrcAlphaFactor(s), src, dst, 3)
                + dst[3] * factor(BlendState.getDstAlphaFactor(s), src, dst, 3);
        return out;
    }

    @Test
    void theLayerComposesLikeTheDirectDraws() {
        int std = state(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO);
        float[] drawA = {1f, 1f, 1f, 0.5f};
        float[] drawB = {0f, 0f, 0f, 0.5f};

        float[] direct = blend(std, drawB, blend(std, drawA, new float[]{0.2f, 0.2f, 0.2f, 1f}));
        assertEquals(0.3f, direct[0], 1e-6f);

        int remapped = UiBlend.remap(std);
        float[] ui = blend(remapped, drawB, blend(remapped, drawA, new float[]{0f, 0f, 0f, 0f}));
        float world = 0.2f;
        float out = ui[0] + world * (1f - ui[3]);
        assertEquals(0.3f, out, 1e-6f);
    }

    @Test
    void pipelineStateRemapsOnTheUiPass() throws Exception {
        List<String> calls = Bytecode.calls("net/vulkanmod/vulkan/shader/PipelineState", "getCurrentPipelineState");
        assertTrue(calls.contains("MainPass.isUiLayerPass"), calls.toString());
        assertTrue(calls.contains("UiBlend.remap"), calls.toString());
    }
}
